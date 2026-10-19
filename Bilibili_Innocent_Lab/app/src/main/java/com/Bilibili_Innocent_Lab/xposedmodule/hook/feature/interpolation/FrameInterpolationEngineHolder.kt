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
    private val workObserver: ((WorkSample) -> Unit)? = null,
    /** 能力测量尺寸；默认到 1440p（手机面板），面板达 4K 时由调用方加入 3840×2160。 */
    private val capabilitySizes: List<Pair<Int, Int>> = DEFAULT_CAPABILITY_SIZES
) {
    constructor(moduleApk: File, hostCodeCache: File, modelDirectory: File, log: (String, String) -> Unit,
                protectStaticDetails: Boolean = true, workObserver: ((WorkSample) -> Unit)? = null) :
        this(moduleApk, hostCodeCache, { modelDirectory }, log, protectStaticDetails, workObserver)

    /**
     * 本机实测可持续插帧的像素上限（运行时能力门），按光流档分开：预热后在 [capabilitySizes] 上按宿主稳态
     * （按帧 ID 复用已上传的前一帧）各计时 3 次，取中位数不超过 [CAPABILITY_LIMIT_MS] 的最大尺寸。
     * 未实测的 GPU（Mali、Immortalis、Xclipse 等）由此自适应，处理尺寸由 [FrameProcessingSize.choose] 据此选择。
     */
    @Volatile var capability = FrameProcessingSize.Capability(0L, 0L)
        private set
    /** 两档中较大的可持续像素数；0 表示尚未测定。 */
    val maxPixels: Long get() = maxOf(capability.standard, capability.high)

    /**
     * 720p 以上、1080p 及以下的光流比例档：按 1/4 → 1/6 → 1/8 依次实测 1080p，取第一个满足预算的；
     * 1/4 只在明显宽裕（≤ [COMFORTABLE_MS]）时选用，因为 1/6 画质几乎相同而 GPU 负载与功耗低得多。
     * 2026-10-19 真机：1080p 1/4 为 22 ms、1/6 为 14 ms，两者对真值的 PSNR 相同（50.7 dB），1/8 降到 39.6 dB。
     */
    @Volatile var standardFlowScale = 4
        private set

    /** 本次插帧的光流比例：≤720p 用 1/4，≤1080p 用实测档，以上用 1/8（光流输入约 480×270 或更小）。 */
    fun flowScaleFor(pixels: Long): Int = when {
        pixels <= 1280L * 720L -> 4
        pixels <= FrameProcessingSize.STANDARD_FLOW_PIXELS -> standardFlowScale
        else -> 8
    }

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
        // fp16、GPU RGB8 转换、自适应光流（≤1080p 用 1/4，以上用 1/8，光流输入约 480×270）。
        // 2026-10-19 真机（Adreno 840，宿主稳态）：720p 11 ms、1080p 21 ms、1440p 16 ms、4K 32 ms。
        val modelDirectory = runCatching { modelSource() }.getOrNull() ?: run { fail("model:unavailable"); return }
        val largest = capabilitySizes.maxOf { (width, height) -> width.toLong() * height }
        val opened = FrameInterpolationBackend.openResearchModel(modelDirectory, FrameInterpolationBackend.Backend.VULKAN,
            1, fp16 = true, flowScale = 4, gpuIo = true, protectStaticDetails = protectStaticDetails,
            maxPixels = maxOf(FrameInterpolationBackend.MAX_PIXELS, largest), adaptiveFlow = true)
        val engine = opened.engine
        if (opened.status != FrameInterpolationBackend.Status.OK || engine == null) { fail("model:${opened.status}"); return }
        backend = engine
        // 真机：首次推理某分辨率时分配显存与编译着色器，远超槽位预算；先在各尺寸上各跑一次。
        for ((width, height) in WARM_SIZES + capabilitySizes) warm(engine, width, height)
        val passing = capabilitySizes.filter { (width, height) ->
            val pixels = width.toLong() * height
            val tiers = if (pixels > 1280L * 720L && pixels <= FrameProcessingSize.STANDARD_FLOW_PIXELS) STANDARD_TIERS
                else listOf(flowScaleFor(pixels))
            val tier = tiers.firstOrNull { scale -> medianMs(engine, width, height, scale) <= tierLimitMs(scale, tiers.size > 1) }
            if (tier != null && tiers.size > 1) standardFlowScale = tier
            tier != null
        }.map { (width, height) -> width.toLong() * height }
        capability = FrameProcessingSize.Capability(
            standard = passing.filter { it <= FrameProcessingSize.STANDARD_FLOW_PIXELS }.maxOrNull() ?: 0L,
            high = passing.filter { it > FrameProcessingSize.STANDARD_FLOW_PIXELS }.maxOrNull() ?: 0L)
        if (maxPixels == 0L) { fail("capability"); return }
        state = State.READY
        log("engine.ready", "${modelDirectory.name} standard=${capability.standard} high=${capability.high} tier=1/$standardFlowScale")
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
                ByteBuffer.allocateDirect(bytes), width, height, 0.5f, flowScale = flowScaleFor(width.toLong() * height))
        }.getOrDefault(FrameInterpolationBackend.Status.INFERENCE_FAILED)
        log("engine.warm", "${width}x$height $status ${(System.nanoTime() - started) / 1_000_000}ms")
    }

    private fun medianMs(engine: FrameInterpolationBackend, width: Int, height: Int, flowScale: Int): Double {
        val bytes = width * height * 3
        val frames = List(4) { ByteBuffer.allocateDirect(bytes) }
        val out = ByteBuffer.allocateDirect(bytes)
        // 宿主稳态：每次插帧只有后一帧是新上传的，前一帧按 ID 命中显存缓存。ID 取在账本永远达不到的区间。
        val base = CAPABILITY_ID_BASE + (width.toLong() * 65_536L + height) * 16L + flowScale
        fun step(i: Int) = engine.process(frames[i % 4], frames[(i + 1) % 4], out, width, height, 0.5f,
            leftId = base + i * 0x1_0000_0000L, rightId = base + (i + 1) * 0x1_0000_0000L, flowScale = flowScale)
        step(0)
        val times = List(3) { index ->
            val started = System.nanoTime()
            step(index + 1)
            (System.nanoTime() - started) / 1e6
        }.sorted()
        log("engine.capability", "${width}x$height flow=1/$flowScale median=%.1fms".format(times[1]))
        return times[1]
    }

    private fun fail(reason: String) {
        state = State.FAILED
        log("engine.failed", reason)
    }

    private companion object {
        val WARM_SIZES = listOf(854 to 480, 640 to 360)
        val DEFAULT_CAPABILITY_SIZES = listOf(1280 to 720, 1920 to 1080, 2560 to 1440)
        val STANDARD_TIERS = listOf(4, 6, 8)
        /** 1080p 档选 1/4 的门槛：留出余量给宿主 UI 与温控，否则用画质相同的 1/6。 */
        const val COMFORTABLE_MS = 16.0
        fun tierLimitMs(scale: Int, tiered: Boolean) = if (tiered && scale == 4) COMFORTABLE_MS else CAPABILITY_LIMIT_MS
        /**
         * 独立计时中位数上限。30→60 每 33.3 ms 需一次补帧；留约 9 ms 给同时进行的捕获、呈现与宿主 UI 合成。
         * 呈现延迟已放宽（用户接受更多延迟），单次偶发超时由时间线丢槽与恢复策略吸收，持续超时回原生。
         */
        const val CAPABILITY_LIMIT_MS = 24.0
        /** 能力测量用的帧 ID 区间；账本 ID 从 0 递增，不可能到达。 */
        const val CAPABILITY_ID_BASE = 1L shl 60
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
                format.stride, format.stride, format.stride, leftId, rightId,
                flowScale = flowScaleFor(format.width.toLong() * format.height))
            lastNativeUs = (System.nanoTime() - nativeStarted) / 1_000L
            return result
        }
        override fun close() = Unit
    }
}
