package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 宿主进程内唯一的推理引擎：按需解包原生依赖、按固定哈希加载用户自备的模型，之后在单一 worker 上
 * 串行推理。引擎寿命等于进程；会话拿到的 synthesizer 的 close 不关闭共享引擎。
 * 预热只在后台 worker 上执行，绝不阻塞宿主主线程或解码线程。
 */
internal class FrameInterpolationEngineHolder(
    private val moduleApk: File,
    private val hostCodeCache: File,
    /** 在推理 worker 上解析模型目录（可能需要从远程文件复制约 10 MB）；null 表示模型不可用。 */
    private val modelSource: () -> File?,
    private val log: (String, String) -> Unit,
    private val protectStaticDetails: Boolean = true,
    private val workObserver: ((WorkSample) -> Unit)? = null
) {
    constructor(moduleApk: File, hostCodeCache: File, modelDirectory: File, log: (String, String) -> Unit,
                protectStaticDetails: Boolean = true, workObserver: ((WorkSample) -> Unit)? = null) :
        this(moduleApk, hostCodeCache, { modelDirectory }, log, protectStaticDetails, workObserver)

    /**
     * 本机实测可持续插帧的最大像素数（运行时能力门）：预热后在常见尺寸上各计时 3 次，取中位数不超过
     * [CAPABILITY_LIMIT_MS] 的最大尺寸。未实测的 GPU（Mali、Immortalis、Xclipse 等）由此自适应，
     * 达不到的尺寸由捕获桥拒绝并保持原生播放。0 表示尚未测定。
     */
    @Volatile var maxPixels: Long = 0L
        private set

    /** 引擎就绪后在推理 worker 上调用一次：用于迟接管冷启动时已开始原生播放的解码器。 */
    @Volatile var onReady: (() -> Unit)? = null
    data class WorkSample(val wallNs: Long, val cpuNs: Long, val bypassed: Boolean, val status: FrameInterpolationBackend.Status)
    enum class State { IDLE, PREPARING, READY, FAILED }

    @Volatile var state = State.IDLE
        private set
    @Volatile private var backend: FrameInterpolationBackend? = null
    /** 因静止画面而省掉的推理次数（诊断）。 */
    val staticSkips = java.util.concurrent.atomic.AtomicLong()
    val sceneCutSkips = java.util.concurrent.atomic.AtomicLong()
    @Volatile var lastGuardUs = 0L
        private set
    @Volatile var lastNativeUs = 0L
        private set
    private val warmed = java.util.Collections.synchronizedSet(HashSet<Pair<Int, Int>>())
    private val executor = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(2)) { task ->
        Thread(task, "innocent-frame-infer").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(false) }

    fun ready(): Boolean = state == State.READY

    @Synchronized fun warmUp() {
        if (state != State.IDLE) return
        state = State.PREPARING
        val accepted = runCatching { executor.execute { load() } }.isSuccess
        if (!accepted) state = State.FAILED
    }

    private fun load() {
        val native = runCatching { FrameInterpolationNative.prepare(moduleApk, hostCodeCache) }
            .getOrDefault(FrameNativeLoader.Status.FAILED)
        if (native != FrameNativeLoader.Status.READY) { fail("native:$native"); return }
        // ≤720p 档位：fp16、四分之一分辨率光流、GPU RGB8 转换（真机 720p 中位 16.5 ms）。
        val modelDirectory = runCatching { modelSource() }.getOrNull() ?: run { fail("model:unavailable"); return }
        val opened = FrameInterpolationBackend.openResearchModel(modelDirectory, FrameInterpolationBackend.Backend.VULKAN,
            1, fp16 = true, flowScale = 4, gpuIo = true, protectStaticDetails = protectStaticDetails)
        val engine = opened.engine
        if (opened.status != FrameInterpolationBackend.Status.OK || engine == null) { fail("model:${opened.status}"); return }
        backend = engine
        // 真机：首次推理某分辨率时分配显存与编译着色器，远超槽位预算；先在常见 ≤720p 尺寸上各跑一次。
        for ((width, height) in WARM_SIZES) warm(engine, width, height)
        maxPixels = WARM_SIZES.filter { (width, height) -> medianMs(engine, width, height) <= CAPABILITY_LIMIT_MS }
            .maxOfOrNull { (width, height) -> width.toLong() * height } ?: 0L
        if (maxPixels == 0L) { fail("capability"); return }
        state = State.READY
        log("engine.ready", "${modelDirectory.name} maxPixels=$maxPixels")
        runCatching { onReady?.invoke() }.onFailure { log("engine.onReady", it.javaClass.simpleName) }
    }

    /** 为宿主实际尺寸预热（异步、每尺寸一次）；引擎未就绪时忽略。 */
    fun prewarm(width: Int, height: Int) {
        val engine = backend ?: return
        if (!warmed.add(width to height)) return
        runCatching { executor.execute { warm(engine, width, height) } }
    }

    private fun warm(engine: FrameInterpolationBackend, width: Int, height: Int) {
        warmed += width to height
        val bytes = width * height * 3
        val started = System.nanoTime()
        val status = runCatching {
            engine.process(ByteBuffer.allocateDirect(bytes), ByteBuffer.allocateDirect(bytes),
                ByteBuffer.allocateDirect(bytes), width, height, 0.5f)
        }.getOrDefault(FrameInterpolationBackend.Status.INFERENCE_FAILED)
        log("engine.warm", "${width}x$height $status ${(System.nanoTime() - started) / 1_000_000}ms")
    }

    private fun medianMs(engine: FrameInterpolationBackend, width: Int, height: Int): Double {
        val bytes = width * height * 3
        val left = ByteBuffer.allocateDirect(bytes)
        val right = ByteBuffer.allocateDirect(bytes)
        val out = ByteBuffer.allocateDirect(bytes)
        val times = List(3) {
            val started = System.nanoTime()
            engine.process(left, right, out, width, height, 0.5f)
            (System.nanoTime() - started) / 1e6
        }.sorted()
        log("engine.capability", "${width}x$height median=%.1fms".format(times[1]))
        return times[1]
    }

    private fun fail(reason: String) {
        state = State.FAILED
        log("engine.failed", reason)
    }

    private companion object {
        val WARM_SIZES = listOf(1280 to 720, 854 to 480, 640 to 360)
        /** 独立计时中位数上限：宿主内同一推理约慢一倍（与宿主 UI 合成共用 GPU），20 ms 仍可支撑 30→60。 */
        const val CAPABILITY_LIMIT_MS = 20.0
    }

    fun worker(): FrameInterpolationSession.Worker =
        FrameInterpolationSession.Worker { task -> runCatching { executor.execute(task) }.isSuccess }

    fun synthesizer(): FrameInterpolationSession.Synthesizer = object : FrameInterpolationSession.Synthesizer {
        override fun process(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer, format: FrameBufferLedger.Format,
                             alpha: Float, leftId: Long, rightId: Long): FrameInterpolationBackend.Status {
            val began = System.nanoTime()
            val cpu = if (workObserver != null) android.os.Debug.threadCpuTimeNanos() else 0L
            val result = processFrame(left, right, output, format, alpha, leftId, rightId)
            workObserver?.let { observer -> runCatching {
                observer(WorkSample(System.nanoTime() - began, android.os.Debug.threadCpuTimeNanos() - cpu,
                    lastNativeUs == 0L, result))
            } }
            return result
        }

        private fun processFrame(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer, format: FrameBufferLedger.Format,
                                 alpha: Float, leftId: Long, rightId: Long): FrameInterpolationBackend.Status {
            val engine = backend ?: return FrameInterpolationBackend.Status.NOT_LOADED
            val started = System.nanoTime()
            lastNativeUs = 0L
            val content = FrameInterpolationNative.analyzeScene(left, right, format.width, format.height, format.stride)
            lastGuardUs = (System.nanoTime() - started) / 1_000L
            if (content < 0) return FrameInterpolationBackend.Status.INVALID_ARGUMENT
            val still = content == 1
            val cut = content == 2
            if (still || cut) {
                // 静止画面省掉推理；切镜保持前一帧，下一张源帧到时再切换，避免提前泄露后一幕。
                output.duplicate().also { it.clear() }.put(left.duplicate().also { it.position(0); it.limit(format.bytes) })
                if (still) staticSkips.incrementAndGet() else sceneCutSkips.incrementAndGet()
                return FrameInterpolationBackend.Status.OK
            }
            val nativeStarted = System.nanoTime()
            val result = engine.process(left, right, output, format.width, format.height, alpha,
                format.stride, format.stride, format.stride, leftId, rightId)
            lastNativeUs = (System.nanoTime() - nativeStarted) / 1_000L
            return result
        }
        override fun close() = Unit
    }
}
