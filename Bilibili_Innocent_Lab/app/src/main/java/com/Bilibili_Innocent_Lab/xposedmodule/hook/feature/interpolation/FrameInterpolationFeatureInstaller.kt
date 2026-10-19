package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCrypto
import android.media.MediaFormat
import android.app.Activity
import android.view.Surface
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * 主播放器插帧（≤720p → 60 fps）。只接管主播放器设置给 IjkMediaPlayer 的显示 Surface 所对应的视频解码器；
 * 广告、开屏、动图、编辑器等其他 ijk/MediaCodec 使用者不受影响。
 *
 * 真机：coreV2 主播放器的 IjkMediaPlayer 与 MediaCodec 运行在独立进程 tv.danmaku.bili:ijkservice
 * （清单中该进程只承载 IjkMediaPlayerService），Surface 经 Binder 传入后由
 * services.IjkMediaPlayerClient.setSurface 直接交给原生 _setVideoSurface；广告/开屏在主进程本地创建播放器。
 * 真机还表明：解码器 configure 拿到的是主进程 TextureView 的 SurfaceTexture，并不经过上述 Java 设置器。
 * 因此在该专用进程里，凡带 Surface 的视频解码器 configure 都视为主播放器（仍受加密、尺寸、准入约束），
 * 引擎在进程启动后即预热；主进程仍只认 IjkMediaPlayAdapter 设置的 Surface。
 *
 * 启用条件：用户开关且 [FrameInterpolationEntryGate.entryOpen]（原生库、模型、能力与媒体准入仍在播放时逐次检查，任一不满足即保持原生输出）。
 * 仅 debug 验证构建额外允许：宿主外部私有目录存在 innocent-frame-verify/enable 标记时在该进程启用，
 * 模型同样只从该目录按固定哈希读取，不随包分发。
 */
internal class FrameInterpolationFeatureInstaller(
    private val enabled: Boolean,
    private val verificationAllowed: Boolean,
    private val moduleApk: File?,
    /** 打开模块写入的 LSPosed 远程文件（模型）；null 时只能使用调试验证目录。 */
    private val remoteFile: ((String) -> android.os.ParcelFileDescriptor?)? = null
) : FeatureInstaller {
    override val id: String = ID

    private val mainSurfaces = Collections.synchronizedMap(WeakHashMap<Surface, Boolean>())
    private val bridges = Collections.synchronizedMap(WeakHashMap<MediaCodec, FrameCaptureBridge>())
    private val audioTracks = Collections.synchronizedMap(WeakHashMap<AudioTrack, FrameAudioDelay>())
    private val inMainDisplay = ThreadLocal<Boolean>()
    private val inMainOp = ThreadLocal<Boolean>()
    private val writingSilence = ThreadLocal<Boolean>()
    @Volatile private var speed = 1.0
    @Volatile private var audioDelayUs = 0L
    @Volatile private var active: Boolean? = null
    @Volatile private var engine: FrameInterpolationEngineHolder? = null
    private var playerProcess = false
    private lateinit var environment: HookEnvironment
    private val displayRequests = WeakHashMap<Activity, Triple<Int, Float, Float?>>()
    private val displayProbes = WeakHashMap<Activity, AutoCloseable>()

    /** Explicit debug opt-in, shared by the UI and dedicated decoder processes. */
    private fun verificationTarget(): Int? {
        if (!verificationAllowed) return null
        val external = ReflectAccess.currentApplication()?.getExternalFilesDir(null) ?: return null
        val directory = File(external, VERIFY_DIR)
        if (!File(directory, "enable").isFile) return null
        return runCatching { File(directory, "target-fps").takeIf { it.length() in 1..8 }
            ?.readText()?.trim()?.toIntOrNull() }.getOrNull()?.takeIf { it in setOf(60, 120) }
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled && !verificationAllowed) return skipped(environment, "disabled")
        val loader = environment.classLoader ?: return skipped(environment, "missing-class-loader")
        val apk = moduleApk ?: return skipped(environment, "missing-module-apk")
        this.environment = environment
        playerProcess = environment.processName == PLAYER_PROCESS
        val adapter = runCatching { Class.forName(ADAPTER, false, loader) }.getOrNull()
            ?: return skipped(environment, "missing-adapter")
        val player = runCatching { Class.forName(PLAYER, false, loader) }.getOrNull()
            ?: return skipped(environment, "missing-player")
        var hooks = 0
        fun hook(name: String, block: () -> Unit) {
            if (runCatching(block).onFailure { environment.logError("$ID.$name", it.javaClass.simpleName) }.isSuccess) hooks++
        }
        val registrar = environment.registrar
        if (verificationAllowed && !playerProcess) {
            // A TextureView is consumed by the host UI, so a producer-only Surface vote is insufficient.
            // Public, window-scoped request; restore the host's original values when it leaves playback.
            hook("display.resume") {
                registrar.exact("$ID.display.resume", Activity::class.java, "onResume") {
                    after {
                        val activity = instance as? Activity ?: return@after
                        if (activity.javaClass.name != "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity") return@after
                        val target = verificationTarget() ?: return@after
                        val window = activity.window ?: return@after
                        val attributes = window.attributes
                        displayRequests.putIfAbsent(activity, Triple(attributes.preferredDisplayModeId,
                            attributes.preferredRefreshRate,
                            if (android.os.Build.VERSION.SDK_INT >= 35) window.decorView.requestedFrameRate else null))
                        attributes.preferredRefreshRate = target.toFloat()
                        @Suppress("DEPRECATION")
                        val display = activity.windowManager.defaultDisplay
                        attributes.preferredDisplayModeId = display.supportedModes.firstOrNull {
                            kotlin.math.abs(it.refreshRate - target) < .1f &&
                                it.physicalWidth == display.mode.physicalWidth &&
                                it.physicalHeight == display.mode.physicalHeight
                        }?.modeId ?: 0
                        window.attributes = attributes
                        if (android.os.Build.VERSION.SDK_INT >= 35) window.decorView.requestedFrameRate = target.toFloat()
                        displayProbes.remove(activity)?.let { runCatching { it.close() } }
                        runCatching {
                            Class.forName("com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameHostDisplayProbe",
                                false, FrameInterpolationFeatureInstaller::class.java.classLoader)
                                .getMethod("acquire", Activity::class.java, Int::class.javaPrimitiveType)
                                .invoke(null, activity, target) as? AutoCloseable
                        }.onSuccess { if (it != null) displayProbes[activity] = it }
                            .onFailure { log("display.probe", it.cause?.toString() ?: it.toString()) }
                        log("display.request", "$target mode=${attributes.preferredDisplayModeId}")
                    }
                }
            }
            hook("display.pause") {
                registrar.exact("$ID.display.pause", Activity::class.java, "onPause") {
                    after {
                        val activity = instance as? Activity ?: return@after
                        displayProbes.remove(activity)?.let { runCatching { it.close() } }
                        val previous = displayRequests.remove(activity) ?: return@after
                        activity.window?.let { window ->
                            val attributes = window.attributes
                            attributes.preferredDisplayModeId = previous.first
                            attributes.preferredRefreshRate = previous.second
                            window.attributes = attributes
                            if (android.os.Build.VERSION.SDK_INT >= 35 && previous.third != null)
                                window.decorView.requestedFrameRate = previous.third!!
                        }
                        log("display.release", activity.javaClass.simpleName)
                    }
                }
            }
        }
        hook("display") {
            registrar.all("$ID.display", ADAPTER, "setVideoDisPlay") {
                before { inMainDisplay.set(true) }
                after { inMainDisplay.set(false) }
            }
        }
        hook("op") {
            registrar.all("$ID.op", ADAPTER, "invokeOp") {
                before { inMainOp.set(true) }
                after { inMainOp.set(false) }
            }
        }
        hook("surface") {
            registrar.exact("$ID.surface", player, "setSurface", Surface::class.java) {
                before {
                    val surface = args[0] as? Surface ?: return@before
                    if ((playerProcess || inMainDisplay.get() == true) && activate(apk)) mainSurfaces[surface] = true
                }
            }
        }
        if (playerProcess) {
            // setSurface 与外部渲染等路径最终都进入原生 _setVideoSurface；两处都登记，重复登记无副作用。
            for (method in listOf("setSurface", "_setVideoSurface")) hook("client.$method") {
                registrar.all("$ID.client.$method", CLIENT, method) {
                    before {
                        val surface = args.getOrNull(0) as? Surface ?: return@before
                        if (activate(apk)) mainSurfaces[surface] = true
                    }
                }
            }
            // 倍速设置器名称以真机为准；缺失只影响倍速证据（时钟按 1.0 推算，倍速变化表现为不连续而重建）。
            runCatching {
                registrar.all("$ID.client.speed", CLIENT, "setSpeed") {
                    before {
                        val value = (args.getOrNull(0) as? Float)?.toDouble() ?: return@before
                        if (value.isFinite() && value > 0.0) speed = value
                    }
                }
                hooks++
            }.onFailure { log("hook.missing", "client.setSpeed") }
        }
        hook("speed") {
            registrar.exact("$ID.speed", player, "setSpeed", Float::class.javaPrimitiveType!!) {
                before {
                    val value = (args[0] as? Float)?.toDouble() ?: return@before
                    if ((playerProcess || inMainOp.get() == true) && value.isFinite() && value > 0.0) speed = value
                }
            }
        }
        hook("configure") {
            registrar.exact("$ID.configure", MediaCodec::class.java, "configure", MediaFormat::class.java,
                Surface::class.java, MediaCrypto::class.java, Int::class.javaPrimitiveType!!) {
                before {
                    val codec = instance as? MediaCodec ?: return@before
                    val display = args[1] as? Surface ?: return@before
                    val format = args[0] as? MediaFormat ?: return@before
                    if (verificationAllowed && ReflectAccess.currentApplication()?.getExternalFilesDir(null)?.let {
                            File(it, "$VERIFY_DIR/native-only").isFile
                        } == true) return@before
                    if (!playerProcess && mainSurfaces[display] != true) return@before
                    val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull() ?: return@before
                    if (!mime.startsWith("video/")) return@before
                    val holder = engine ?: return@before
                    bridges.remove(codec)?.close()
                    if (!holder.ready()) {
                        // 冷启动：模型仍在后台加载。保持原生播放，就绪后迟接管（见 attachPending），不阻塞解码线程。
                        if (holder.state != FrameInterpolationEngineHolder.State.FAILED)
                            pending[codec] = PendingDecoder(format, display, args[2])
                        return@before
                    }
                    val bridge = FrameCaptureBridge.create(FrameCaptureBridge.Config(), codec, format, display, args[2],
                        holder, bridgeHost, requestedTargetFps = verificationTarget()) ?: return@before
                    bridges[codec] = bridge
                    args[1] = bridge.captureSurface
                }
                after {
                    // configure 失败时宿主会放弃这个解码器：立刻拆除，避免持有显示 Surface。
                    if (hasThrowable) (instance as? MediaCodec)?.let { bridges.remove(it)?.close() }
                }
            }
        }
        hook("flush") {
            registrar.exact("$ID.flush", MediaCodec::class.java, "flush") {
                before { (instance as? MediaCodec)?.let { bridges[it]?.onFlush() } }
            }
        }
        for (method in listOf("stop", "release")) hook(method) {
            registrar.exact("$ID.$method", MediaCodec::class.java, method) {
                before { (instance as? MediaCodec)?.let { pending.remove(it) } }
                after { (instance as? MediaCodec)?.let { bridges.remove(it)?.close() } }
            }
        }
        hook("audio.ctor") { installAudio(registrar) }
        // 专用播放进程：Application 就绪后立即决定是否启用并预热引擎，首个解码器 configure 时即可接管。
        if (playerProcess) environment.postToMain?.invoke { activate(apk) }
        environment.reportStatus(STATUS, "installed:$hooks")
        log("installed", "hooks=$hooks process=${environment.processName}")
        return if (hooks >= REQUIRED_HOOKS) FeatureInstallResult.Installed(hooks)
            else FeatureInstallResult.Installed(hooks, complete = false)
    }

    /** ijk 经 J4A 用旧式 6 参构造创建 PCM 输出；只有这些轨道参与音画补偿。 */
    private fun installAudio(registrar: com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar) {
        val int = Int::class.javaPrimitiveType!!
        val constructor = AudioTrack::class.java.getConstructor(int, int, int, int, int, int)
        registrar.constructor("$ID.audio.ctor", constructor) {
            after {
                if (hasThrowable) return@after
                val track = instance as? AudioTrack ?: return@after
                val bytes = when (args[3] as? Int) { 2 -> 2; 3 -> 1; 4 -> 4; else -> return@after }
                val channels = Integer.bitCount((args[2] as? Int) ?: return@after)
                val rate = (args[1] as? Int) ?: return@after
                runCatching { FrameAudioDelay(rate, channels, bytes) }.getOrNull()?.let { audioTracks[track] = it }
                audioRates[track] = rate
            }
        }
        installAudioClock(registrar)
        val bytes = ByteArray::class.java
        for (parameters in listOf(arrayOf(bytes, int, int), arrayOf(bytes, int, int, int))) {
            registrar.exact("$ID.audio.write${parameters.size}", AudioTrack::class.java, "write", *parameters) {
                before {
                    // 只有找不到 ijk 读取的时钟接口时才退回写静音：静音占用固定大小的 AudioTrack 缓冲并扰动 ijk 时钟，
                    // 宿主内真机 A/B 证实会造成音频卡顿与视频顿挫。
                    if (audioMode != AudioMode.SILENCE || writingSilence.get() == true) return@before
                    val track = instance as? AudioTrack ?: return@before
                    val delay = audioTracks[track] ?: return@before
                    delay.setTargetDelayUs(audioDelayUs)
                    val size = (args[2] as? Int) ?: return@before
                    val plan = delay.onWrite(size)
                    if (plan.silenceBytes > 0) {
                        writingSilence.set(true)
                        try { track.write(ByteArray(plan.silenceBytes), 0, plan.silenceBytes, AudioTrack.WRITE_BLOCKING) }
                        finally { writingSilence.set(false) }
                    }
                    if (plan.dropBytes > 0) {
                        // 整段被追回：不调用原方法，向宿主报告全部写入，宿主自身的计数保持不变。
                        if (plan.dropBytes >= size) { result = size; return@before }
                        setObjectExtra(DROPPED, plan.dropBytes)
                        args[1] = (args[1] as Int) + plan.dropBytes
                        args[2] = size - plan.dropBytes
                    }
                }
                after {
                    val dropped = getObjectExtra(DROPPED) as? Int ?: return@after
                    val written = result as? Int ?: return@after
                    if (written >= 0) result = written + dropped
                }
            }
        }
        registrar.exact("$ID.audio.flush", AudioTrack::class.java, "flush") {
            before { (instance as? AudioTrack)?.let { audioTracks[it]?.onFlush() } }
        }
    }

    /**
     * 音画补偿改为平移 ijk 读取的音频时钟，不动音频数据：ijk 按 AudioTrack 回报的播放位置/时延推算主时钟并据此
     * 释放视频帧。把回报的播放位置前移 delay（或时延减去 delay），ijk 就提前 delay 释放视频帧，管线再延后 delay，
     * 画面与未改动的声音同步。真机先统计 ijk 实际调用哪个接口，再只平移该接口。
     */
    private fun installAudioClock(registrar: com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookRegistrar) {
        runCatching {
            registrar.exact("$ID.audio.timestamp", AudioTrack::class.java, "getTimestamp", AudioTimestamp::class.java) {
                after {
                    val track = instance as? AudioTrack ?: return@after
                    val rate = audioRates[track] ?: return@after
                    timestampCalls.incrementAndGet()
                    if (audioMode == AudioMode.TIMESTAMP && result == true)
                        (args[0] as? AudioTimestamp)?.let { it.framePosition += shiftFrames(rate) }
                }
            }
        }.onFailure { log("hook.missing", "audio.getTimestamp") }
        runCatching {
            registrar.exact("$ID.audio.head", AudioTrack::class.java, "getPlaybackHeadPosition") {
                after {
                    val track = instance as? AudioTrack ?: return@after
                    val rate = audioRates[track] ?: return@after
                    headCalls.incrementAndGet()
                    val head = result as? Int ?: return@after
                    if (audioMode == AudioMode.HEAD) result = head + shiftFrames(rate).toInt()
                }
            }
        }.onFailure { log("hook.missing", "audio.getPlaybackHeadPosition") }
        runCatching {
            registrar.exact("$ID.audio.latency", AudioTrack::class.java, "getLatency") {
                after {
                    val track = instance as? AudioTrack ?: return@after
                    if (audioRates[track] == null) return@after
                    latencyCalls.incrementAndGet()
                    val latency = result as? Int ?: return@after
                    if (audioMode == AudioMode.LATENCY) result = latency - (audioDelayUs / 1_000L).toInt()
                }
            }
        }.onFailure { log("hook.missing", "audio.getLatency") }
    }

    private fun shiftFrames(rate: Int): Long = audioDelayUs * rate / 1_000_000L

    private class PendingDecoder(val format: MediaFormat, val display: Surface, val crypto: Any?)
    private val pending = Collections.synchronizedMap(WeakHashMap<MediaCodec, PendingDecoder>())

    /** 引擎就绪后接管冷启动期间已原生播放的解码器：在 worker 上把其输出切到捕获 Surface。 */
    private fun attachPending() {
        val holder = engine ?: return
        val waiting = synchronized(pending) { pending.entries.map { it.key to it.value }.also { pending.clear() } }
        for ((codec, decoder) in waiting) {
            val bridge = FrameCaptureBridge.create(FrameCaptureBridge.Config(), codec, decoder.format, decoder.display,
                decoder.crypto, holder, bridgeHost, requestedTargetFps = verificationTarget(),
                attach = { surface -> runCatching { codec.setOutputSurface(surface) }.isSuccess }) ?: continue
            bridges[codec] = bridge
            log("bridge.late", "attached")
        }
    }

    /** 首次需要补偿时按 ijk 已调用过的接口选择平移方式；都没有调用过才退回写静音。 */
    private fun chooseAudioMode() {
        if (audioMode != AudioMode.UNDECIDED) return
        audioMode = when {
            timestampCalls.get() > 0 -> AudioMode.TIMESTAMP
            headCalls.get() > 0 -> AudioMode.HEAD
            latencyCalls.get() > 0 -> AudioMode.LATENCY
            else -> AudioMode.SILENCE
        }
        log("audio.mode", "$audioMode timestamp=${timestampCalls.get()} head=${headCalls.get()} latency=${latencyCalls.get()}")
    }

    private enum class AudioMode { UNDECIDED, TIMESTAMP, HEAD, LATENCY, SILENCE }
    @Volatile private var audioMode = AudioMode.UNDECIDED
    private val audioRates = Collections.synchronizedMap(WeakHashMap<AudioTrack, Int>())
    private val timestampCalls = java.util.concurrent.atomic.AtomicInteger()
    private val headCalls = java.util.concurrent.atomic.AtomicInteger()
    private val latencyCalls = java.util.concurrent.atomic.AtomicInteger()

    /** 首次见到主显示 Surface 时决定本进程是否启用，并在后台预热引擎。 */
    private fun activate(apk: File): Boolean {
        active?.let { return it }
        synchronized(this) {
            active?.let { return it }
            val application = ReflectAccess.currentApplication()
            val external = application?.getExternalFilesDir(null)
            val verifying = verificationAllowed && external != null && File(external, "$VERIFY_DIR/enable").isFile
            val decided = application != null && external != null && (enabled || verifying)
            if (decided) {
                val host = application!!
                val debugModel = File(external, "$VERIFY_DIR/${FrameModelSpec.NAME}")
                // 模型来源：debug 验证目录（仅验证构建）优先，否则用模块下载后写入的远程文件，复制并校验到宿主私有目录。
                val modelSource: () -> File? = {
                    if (verifying && File(debugModel, FrameModelSpec.WEIGHTS.fileName).isFile) debugModel
                    else when (val result = FrameModelCache.materialize(host.noBackupFilesDir) { name ->
                        remoteFile?.invoke(name)?.let(android.os.ParcelFileDescriptor::AutoCloseInputStream) }) {
                        is FrameModelCache.Result.Ready -> result.directory
                        else -> { log("model.unavailable", result.toString()); null }
                    }
                }
                engine = FrameInterpolationEngineHolder(apk, host.codeCacheDir, modelSource,
                    log = { event, detail -> log(event, detail) })
                    .also { it.onReady = ::attachPending; it.warmUp() }
            }
            log("activate", "enabled=$enabled verifying=$verifying decided=$decided")
            active = decided
            return decided
        }
    }

    private val bridgeHost = object : FrameCaptureBridge.Host {
        override fun setAudioDelayUs(delayUs: Long) {
            if (delayUs > 0L) chooseAudioMode()
            audioDelayUs = delayUs.coerceIn(0L, 250_000L)
        }
        override fun playbackSpeed(): Double = speed
        override fun audio(): FrameMediaPolicy.Audio =
            if (audioTracks.isEmpty()) FrameMediaPolicy.Audio()
            else FrameMediaPolicy.Audio(output = FrameMediaPolicy.AudioOutput.PCM,
                offloaded = FrameMediaPolicy.Evidence.NO, tunneled = FrameMediaPolicy.Evidence.NO)
        override fun log(event: String, detail: String) = this@FrameInterpolationFeatureInstaller.log(event, detail)
        override fun context(): android.content.Context? = ReflectAccess.currentApplication()
    }

    private fun log(event: String, detail: String) {
        if (::environment.isInitialized) environment.logInfo("$ID.$event", "[BIL] frame $event $detail")
        // 仅 debug 验证构建：模块日志按键去重，真机验证需要逐条事件与周期统计。
        if (verificationAllowed) android.util.Log.i(VERIFY_TAG, "$event $detail")
    }

    private fun skipped(environment: HookEnvironment, reason: String): FeatureInstallResult {
        environment.reportStatus(STATUS, reason)
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "player_frame_interpolation"
        private const val STATUS = "player_frame_interpolation"
        private const val ADAPTER = "tv.danmaku.videoplayer.coreV2.adapter.ijk.IjkMediaPlayAdapter"
        private const val PLAYER = "tv.danmaku.ijk.media.player.IjkMediaPlayer"
        private const val VERIFY_DIR = "innocent-frame-verify"
        private const val PLAYER_PROCESS = "tv.danmaku.bili:ijkservice"
        private const val CLIENT = "tv.danmaku.ijk.media.player.services.IjkMediaPlayerClient"
        private const val VERIFY_TAG = "InnocentFrame"
        private const val REQUIRED_HOOKS = 8
        private const val DROPPED = "innocent.frame.audio.dropped"
    }
}
