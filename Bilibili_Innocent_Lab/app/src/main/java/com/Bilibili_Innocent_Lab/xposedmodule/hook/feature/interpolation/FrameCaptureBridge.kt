package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Choreographer
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个被接管的宿主视频解码器：解码输出改到 [FrameGlCapture] 的 SurfaceTexture，经
 * [FrameInterpolationSession] 插帧后由 [FrameGlPresenter] 在独立呈现线程画回宿主显示 Surface。任何失败都走 [returnToNative]：
 * 先断开 EGL，再把解码器输出切回显示 Surface，本解码器实例此后保持原生播放。
 *
 * 真机（高通）：解码器无视 CPU 读用途、给 ImageReader 分配 UBWC 压缩布局而无法锁定平面，
 * 因此捕获只走 GPU：驱动负责解压与 YUV→RGB，模块只做 RGBA 读回。
 *
 * 线程：捕获回调与 Choreographer 驱动在捕获线程串行执行，呈现在呈现线程；宿主 MediaCodec 线程只调用
 * [onFlush]/[close]，二者都投递到捕获线程。
 */
internal class FrameCaptureBridge private constructor(
    private val config: Config,
    private val codec: MediaCodec,
    private val display: Surface,
    /** 处理尺寸（[FrameProcessingSize]）：捕获缩放后的尺寸，推理、账本与呈现都用它。 */
    private val width: Int,
    private val height: Int,
    /** 解码输出尺寸（可达 4K）：只用于媒体准入与 epoch。 */
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val source: FrameHostMediaSignals.Values,
    private val secureDecoder: Boolean,
    private val tunneled: Boolean,
    private val engine: FrameInterpolationEngineHolder,
    private val host: Host
) {
    /** 宿主侧回调：音频补偿目标、倍速与音频输出证据、诊断日志。 */
    interface Host {
        fun setAudioDelayUs(delayUs: Long)
        fun playbackSpeed(): Double
        fun audio(): FrameMediaPolicy.Audio
        fun log(event: String, detail: String)
        /** 用于温控余量与 ADPF；null 时两者都不启用。 */
        fun context(): android.content.Context? = null
        /** EGL swap 完成时刻；是提交证据，不能当作屏幕实际扫描显示时间。 */
        fun onFrameSubmitted(dueNs: Long, submittedNs: Long) = Unit
    }

    /**
     * 真机 720p：与 GL 捕获共用 GPU 时推理平均约 27 ms。规划需至少提前 minLead，而被插值的后一帧在规划时
     * 必须已到达，故呈现延迟 ≥ 提前量 + 一个源帧间隔（30 fps 为 33 ms）；音频按同一延迟补偿。
     */
    data class Config(
        val targetFps: Int = 60,
        // 宿主内真机：帧到达抖动大于测试应用，80 ms 时约 5 槽/秒等不到后一帧；放宽到 100 ms / 提前 60 ms。
        val presentationDelayNs: Long = 100_000_000L,
        val maxLeadNs: Long = 60_000_000L,
        val minLeadNs: Long = 32_000_000L,
        /** 接管的解码输出上限（4K）；处理尺寸另由能力与屏幕决定，见 [FrameProcessingSize]。 */
        val maxSourcePixels: Long = 4096L * 2160L,
        /** 时间线窗口 4 + 推理输出 1 + Presenter 临近交付前持有的约 3 帧 + 余量；RGB8 每帧 720p 2.7 MB、1440p 11 MB。 */
        val maxBuffers: Int = 12,
        val frameWindow: Int = 4,
        val stageUploads: Boolean = true,
        /** 正式播放：源帧率已达目标时回原生（验证构建的显式档位可做纯复制测试）。 */
        val interpolationRequired: Boolean = true
    )

    private val presenter = FrameGlPresenter(display, config.targetFps, config.stageUploads) { onNativeRestored(it) }.apply {
        onSubmitted = host::onFrameSubmitted
    }
    private val capture = FrameGlCapture(width, height, sourceWidth, sourceHeight)
    private val clock = FrameHostClock()
    private val ledger = FrameBufferLedger(config.maxBuffers)
    private val gate = FrameMediaGate()
    // 真机：起步约 1/3 s 内 GL 线程仍在做首次纹理与读回分配，偶发连续 3 帧迟到即触发回退（10 次中 2 次）；
    // 延长预热并放宽连续失败门限，持续性问题仍由 25% 失败率判定。
    private val recovery = FramePresentationRecovery(maxConsecutiveFailures = 6, warmupSamples = WARMUP_SAMPLES)
    private val timeline = FrameTimeline(config.targetFps, presentationDelayNs = config.presentationDelayNs,
        maxLeadNs = config.maxLeadNs, minLeadNs = config.minLeadNs, originalToleranceUs = 1_000_000L / config.targetFps / 4, phaseLock = true,
        maxClockUncertaintyUs = 8_000L, frameWindow = config.frameWindow)
    private val session = FrameInterpolationSession(config.targetFps, ledger, engine.synthesizer(), engine.worker(),
        presenter, System::nanoTime, recovery, timeline = timeline)
    private var admitted = false
    private var discontinuity = 0L
    private var epochCount = 0L
    private var lastArrivalNs = Long.MIN_VALUE
    private var lastReportNs = 0L
    private var audioDelayUs = 0L
    private var power: FramePowerAdvisor? = null
    private val cadence = FrameSourceCadence(config.targetFps, interpolationRequired = config.interpolationRequired)
    private val native = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    /** 交给宿主 configure 的替换 Surface（捕获 SurfaceTexture）。 */
    var captureSurface: Surface? = null
        private set

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (closed.get() || native.get()) return
            runCatching { drive(frameTimeNanos) }.onFailure { returnToNative("drive:${it.javaClass.simpleName}") }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * attach 非空为「迟接管」：解码器已在原生播放（冷启动时引擎尚未就绪），先建好捕获 Surface，
     * 由 attach 把运行中的解码器切过来（setOutputSurface 会断开其对显示 Surface 的连接），之后呈现器才能连接显示 Surface；
     * 任何一步失败都把解码器切回显示 Surface。
     */
    private fun start(attach: ((Surface) -> Boolean)? = null): Boolean {
        engine.prewarm(width, height)
        power = FramePowerAdvisor(host.context(), 1_000_000_000L / config.targetFps)
        val listener = FrameGlCapture.FrameListener { timestamp, arrival, read -> onFrame(timestamp, arrival, read) }
        val surface = capture.start(listener) ?: return false
        captureSurface = surface
        if (attach != null && !attach(surface)) return false
        if (!presenter.start()) {
            if (attach != null) runCatching { codec.setOutputSurface(display) }
            return false
        }
        capture.handler.post { Choreographer.getInstance().postFrameCallback(frameCallback) }
        host.log("bridge.start", "${width}x$height")
        return true
    }

    /** 诊断：会话快照与是否已回原生；供日志与真机验证使用，不参与控制。 */
    fun diagnostics(): Pair<FrameInterpolationSession.Snapshot, Boolean> = session.snapshot() to native.get()

    /** 宿主 MediaCodec.flush（seek）：旧帧与时钟作废，下一帧开新 epoch。 */
    fun onFlush() {
        capture.handler.post {
            clock.reset()
            cadence.reset()
            discontinuity++
            lastArrivalNs = Long.MIN_VALUE
            if (admitted && !native.get()) beginEpoch()
        }
    }

    /** 宿主 stop/release：拆除会话、断开 EGL；音频补偿归零。 */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        capture.handler.post {
            host.setAudioDelayUs(0L)
            session.close()
            host.log("bridge.close", session.snapshot().recovery.cause.name)
        }
        presenter.close()
        capture.close()
        power?.close()
    }

    private fun onFrame(timestampNs: Long, arrivalNs: Long, read: (ByteBuffer, FrameBufferLedger.Format) -> Boolean) {
        if (native.get() || closed.get()) return
        if (!admitted && !admit()) return
        val pts = timestampNs / 1_000L
        if (clock.feed(pts, arrivalNs, host.playbackSpeed()) == FrameHostClock.Update.DISCONTINUITY) {
            discontinuity++
            beginEpoch()
        }
        lastArrivalNs = arrivalNs
        // 捕获包含阻塞式 GPU 读回；ADPF 的 CPU 提示尚未按实际 CPU 工作分离，保持关闭以免错误提频。
        cadence.add(pts)
        if (cadence.verdict() == FrameSourceCadence.Verdict.UNSUITABLE) {
            returnToNative("cadence:${cadence.describe()}")
            return
        }
        session.capture(pts, read)
    }

    /** 首帧时用解码器输出格式补齐颜色证据再做准入；拒绝即回原生，不呈现任何一帧。 */
    private fun admit(): Boolean {
        // 真机：宿主 configure 格式不带 profile，解码器输出格式（video/raw）也不带；解码器解析序列头后把
        // profile 填进其输入格式（AV1 profile 1 = Main8）。缺失项依次由输入格式、输出格式补齐。
        val input = runCatching { codec.inputFormat }.getOrNull()?.let(::values)
        val source = input?.let(this.source::fillFrom) ?: this.source
        val decoded = runCatching { codec.outputFormat }.getOrNull()?.let(::values) ?: FrameHostMediaSignals.Values(source.mime)
        // GPU 采样 OES 时由驱动按流色彩空间完成 YUV→RGB；只对已知的 SDR BT.601/709 声称捕获已验证。
        val decodedSignal = FrameHostMediaSignals.signal(decoded.fillFrom(source), 8)
        val capture = if (FrameHostMediaSignals.converterStandard(decodedSignal.standard) != null)
            FrameMediaPolicy.Capture.VERIFIED_SDR_RGB8 else FrameMediaPolicy.Capture.OTHER
        fun evidence(value: Boolean) = if (value) FrameMediaPolicy.Evidence.YES else FrameMediaPolicy.Evidence.NO
        val lease = gate.begin(epoch()).lease
        val resolution = gate.resolve(lease, FrameMediaPolicy.Snapshot(sourceWidth, sourceHeight,
            FrameHostMediaSignals.signal(source.fillFrom(decoded)), decodedSignal, capture,
            FrameMediaPolicy.Evidence.NO, evidence(secureDecoder), evidence(tunneled), host.audio()))
        host.log("bridge.admit", resolution.decision.reason.name)
        if (resolution.permit == null) {
            returnToNative("media:${resolution.decision.reason}")
            return false
        }
        admitted = true
        beginEpoch()
        return true
    }

    private fun epoch() = FrameTimeline.Epoch(System.identityHashCode(codec).toLong(), epochCount,
        System.identityHashCode(display).toLong(), (sourceWidth.toLong() shl 32) or sourceHeight.toLong(), discontinuity)

    private fun beginEpoch() {
        epochCount++
        if (!session.begin(epoch(), width, height)) host.log("bridge.begin", session.snapshot().recovery.state.name)
    }

    private fun drive(vsyncNs: Long) {
        val now = System.nanoTime()
        // 暂停时没有新帧：停止推进时钟，时间线随后因时钟过期而停止规划，屏幕保留最后一帧。
        if (!admitted || lastArrivalNs == Long.MIN_VALUE || now - lastArrivalNs > STALL_NS) return
        // 时钟快照取在 vsync 时刻：时间线以首个快照为锚，槽位网格因此与显示 vsync 对齐，
        // 每个槽位恰好落在一个 vsync 上，不会因抖动两帧挤进同一 vsync、下一 vsync 却没有新帧。
        clock.snapshot(minOf(vsyncNs, now), discontinuity)?.let { snapshot ->
            val state = session.updateClock(snapshot)
            if (state == FrameTimeline.ClockState.RESTART_REQUIRED || state == FrameTimeline.ClockState.NO_CLOCK) {
                discontinuity++
                clock.reset()
                beginEpoch()
                return
            }
        }
        // 温控：预测余量逼近严重降频阈值前回原生，避免持续高负载把整机推入降频（之后宿主本身也会卡顿）。
        power?.thermalHeadroom(now)?.let { headroom ->
            if (headroom >= THERMAL_LIMIT) { returnToNative("thermal:%.2f".format(headroom)); return }
        }
        session.tick(now)
        // 音频只跟随大幅变化：相位跟踪每次对齐都会让视频延迟摆动数毫秒，若音频逐次跟随，就要在 ijk 的
        // 音频线程上频繁阻塞写静音或丢字节，宿主内真机出现音频卡顿与视频顿挫。小幅偏差（< 20 ms）不影响口型。
        val target = session.effectiveDelayUs()
        if (target > 0L && (audioDelayUs == 0L || kotlin.math.abs(target - audioDelayUs) > AUDIO_HYSTERESIS_US)) {
            audioDelayUs = target
            host.setAudioDelayUs(target)
        }
        if (now - lastReportNs >= REPORT_INTERVAL_NS) {
            lastReportNs = now
            val snapshot = session.snapshot()
            host.log("bridge.stats", "phase=${snapshot.phase} state=${snapshot.recovery.state} presented=${snapshot.presented} " +
                "interpolated=${snapshot.interpolated} late=${snapshot.late} missed=${snapshot.missedCommit} " +
                "inferUs=${snapshot.averageInferenceUs} leadUs=${snapshot.averageLeadUs} delayUs=${session.effectiveDelayUs()} " +
                "planned=${snapshot.planned} source=${cadence.describe()} audioDelayUs=$audioDelayUs " +
                "staticSkips=${engine.staticSkips.get()} cutSkips=${engine.sceneCutSkips.get()} " +
                "guardUs=${engine.lastGuardUs} nativeUs=${engine.lastNativeUs} thermal=${power?.lastHeadroom} batteryC=${power?.lastBatteryC}")
        }
    }

    /** 管线主动放弃（准入失败、驱动异常）：经 Presenter 断开 EGL 后切回原生。 */
    private fun returnToNative(reason: String) {
        if (native.get()) return
        host.log("bridge.native", reason)
        presenter.restoreNative(recovery.report())
    }

    /** 由呈现线程在显示 EGL 断开后回调；只切换一次。 */
    private fun onNativeRestored(report: FramePresentationRecovery.Report) {
        if (!native.compareAndSet(false, true)) return
        audioDelayUs = 0L
        host.setAudioDelayUs(0L)
        val switched = runCatching { codec.setOutputSurface(display) }.isSuccess
        host.log("bridge.restored", "${report.state}:${report.cause}:switched=$switched")
        // 温控/准入回退后本实例不会再恢复插帧；归还 RGB 账本、GL 线程与读回内存，避免闲置资源常驻。
        if (switched) close()
    }

    companion object {
        private const val STALL_NS = 200_000_000L
        private const val WARMUP_SAMPLES = 20
        private const val REPORT_INTERVAL_NS = 5_000_000_000L
        private const val AUDIO_HYSTERESIS_US = 20_000L
        /** getThermalHeadroom 的 1.0 为严重降频；留出余量提前退出。 */
        private const val THERMAL_LIMIT = 0.85f
        private const val DEEP_DELAY_NS = 150_000_000L
        private const val DEEP_LEAD_NS = 100_000_000L
        /** 账本 16 个缓冲：窗口 + 推理输出 + 呈现器持有约 3 帧。 */
        private const val MAX_FRAME_WINDOW = 11
        private const val TUNNELED = "tunneled-playback"
        /** MediaFormat.KEY_HDR10_PLUS_INFO（API 29），以字面量引用以兼容更低的 minSdk。 */
        private const val KEY_HDR10_PLUS_INFO = "hdr10-plus-info"

        /**
         * 宿主 configure 前调用；返回 null 表示不接管（保持宿主原样）。只接管已在主播放器登记的显示 Surface、
         * 无加密、尺寸在范围内且推理引擎已就绪的视频解码器。
         */
        fun create(config: Config, codec: MediaCodec, format: MediaFormat, display: Surface, crypto: Any?,
                   engine: FrameInterpolationEngineHolder, host: Host, requestedTargetFps: Int? = null,
                   attach: ((Surface) -> Boolean)? = null): FrameCaptureBridge? {
            if (!engine.ready()) { host.log("bridge.skip", "engine:${engine.state}"); return null }
            val width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull() ?: return null
            val height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull() ?: return null
            if (width <= 0 || height <= 0 || width.toLong() * height > config.maxSourcePixels || crypto != null) {
                host.log("bridge.skip", "format:${width}x$height:crypto=${crypto != null}")
                return null
            }
            // 处理尺寸：不超过屏幕与本机实测能力；4K 源在捕获时一次缩到此尺寸。
            val (displayLong, displayShort) = displaySize(host.context())
            val processing = FrameProcessingSize.choose(width, height, engine.capability, displayLong, displayShort)
                ?: run { host.log("bridge.skip", "capability:${width}x$height"); return null }
            host.log("bridge.size", "${width}x$height->${processing.width}x${processing.height} display=${displayLong}x$displayShort")
            val name = runCatching { codec.name }.getOrDefault("")
            val tunneled = runCatching { format.getFeatureEnabled(TUNNELED) }.getOrDefault(false) ||
                runCatching { format.containsKey(MediaFormat.KEY_AUDIO_SESSION_ID) }.getOrDefault(false)
            // 25 fps 源按 2 倍插到 50 fps（30/29.97 → 60）；目标随后由实测节奏复核，不符即回原生。
            val hint = sourceFpsHint(format)
            val target = targetFpsFor(hint, requestedTargetFps)
            host.log("bridge.target", "${target}fps hint=$hint")
            // 源已达目标帧率（如 60 fps 视频）：没有可补的帧，不接管，避免 4K 捕获与呈现的空耗。
            if (requestedTargetFps == null && hint != null && hint >= target * 0.98f) {
                host.log("bridge.skip", "rate:$hint")
                return null
            }
            val bridge = runCatching {
                // 60 fps 源的四帧窗口只覆盖 50 ms，无法保留 100 ms 延迟管线起步所需的原帧。
                // 120 验证档扩为八源帧与十六个 RGB 缓冲，仍由账本硬上限约束。
                // 720p 以上：推理占槽位比例更高，抖动更易错过槽位。加深流水线（延迟 150 ms、提前 100 ms、六源帧窗口），
                // 让 worker 跑得更靠前以吸收抖动；音频按同一延迟补偿。720p 保持已在宿主验证的 100 ms。
                val deep = processing.pixels > 1280L * 720L
                val delayNs = if (deep) DEEP_DELAY_NS else config.presentationDelayNs
                // 源帧窗口须覆盖整个呈现延迟再加两帧余量：150 ms 在 60 fps 源上需要 11 帧（宿主真机：6 帧窗口时
                // 时间线等不到后一帧、画面停住）。
                val sourceFps = (hint ?: 30f).coerceIn(10f, 120f)
                val window = maxOf(if (target >= 120) 8 else config.frameWindow,
                    kotlin.math.ceil(delayNs / 1e9 * sourceFps).toInt() + 2).coerceAtMost(MAX_FRAME_WINDOW)
                val selected = config.copy(targetFps = target,
                    presentationDelayNs = delayNs,
                    maxLeadNs = if (deep) DEEP_LEAD_NS else config.maxLeadNs,
                    frameWindow = window,
                    maxBuffers = if (target >= 120 || deep || window > config.frameWindow) 16 else config.maxBuffers,
                    interpolationRequired = config.interpolationRequired && requestedTargetFps == null)
                FrameCaptureBridge(selected, codec, display, processing.width, processing.height, width, height,
                    values(format), name.endsWith(".secure"), tunneled, engine, host)
            }.getOrNull() ?: return null
            if (!bridge.start(attach)) { bridge.close(); host.log("bridge.skip", "presenter"); return null }
            return bridge
        }

        /** 面板物理分辨率（长边, 短边）；取不到时为 (0, 0)，处理尺寸只受能力约束。 */
        private fun displaySize(context: android.content.Context?): Pair<Int, Int> = runCatching {
            val manager = context!!.getSystemService(android.hardware.display.DisplayManager::class.java)
            val modes = manager.getDisplay(android.view.Display.DEFAULT_DISPLAY).supportedModes
            val width = modes.maxOf { maxOf(it.physicalWidth, it.physicalHeight) }
            val height = modes.maxOf { minOf(it.physicalWidth, it.physicalHeight) }
            width to height
        }.getOrDefault(0 to 0)

        /** ijk 在 configure 格式里给出 operating-rate（真机：29.97 fps 源为 29、30 fps 源为 30），也可能给 frame-rate。 */
        private fun sourceFpsHint(format: MediaFormat): Float? {
            for (key in listOf(MediaFormat.KEY_FRAME_RATE, MediaFormat.KEY_OPERATING_RATE)) {
                val value = runCatching {
                    if (!format.containsKey(key)) null
                    else runCatching { format.getFloat(key) }.getOrNull() ?: format.getInteger(key).toFloat()
                }.getOrNull()
                if (value != null && value.isFinite() && value > 0f) return value
            }
            return null
        }

        internal fun targetFpsFor(sourceFps: Float?, requested: Int? = null): Int {
            require(requested == null || requested in setOf(15, 30, 45, 50, 60, 120, 144, 165, 185))
            return requested ?: if (sourceFps != null && sourceFps in 24.5f..25.5f) 50 else 60
        }

        private fun values(format: MediaFormat): FrameHostMediaSignals.Values {
            fun integer(key: String) = runCatching { if (format.containsKey(key)) format.getInteger(key) else null }.getOrNull()
            fun bytes(key: String): ByteArray? = runCatching {
                if (format.containsKey(key)) format.getByteBuffer(key)?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
                } else null
            }.getOrNull()
            return FrameHostMediaSignals.Values(
                mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull(),
                colorStandard = integer(MediaFormat.KEY_COLOR_STANDARD),
                colorTransfer = integer(MediaFormat.KEY_COLOR_TRANSFER),
                colorRange = integer(MediaFormat.KEY_COLOR_RANGE),
                hdrStaticInfo = FrameHostMediaSignals.hdrMetadataPresent(bytes(MediaFormat.KEY_HDR_STATIC_INFO)),
                hdr10PlusInfo = FrameHostMediaSignals.hdrMetadataPresent(bytes(KEY_HDR10_PLUS_INFO)),
                profile = integer(MediaFormat.KEY_PROFILE))
        }
    }
}
