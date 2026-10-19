package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith

/**
 * 真机端到端：真实硬件解码器按 PTS 时刻 releaseOutputBuffer(render = true)（与 ijk 相同），
 * 经捕获桥接管、真实 RIFE 推理与 GL 呈现到替身显示 Surface。覆盖准入、时钟、深流水线、seek 与拆除；
 * 不覆盖宿主进程、LSPosed Hook 与音频（音频只校验补偿目标）。模型未放入时跳过。
 */
@RunWith(AndroidJUnit4::class)
class FrameCaptureBridgeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var width = 1280
    private var height = 720
    private var sfPeriodNs = 0L
    @After fun detachDiagnostics() { logSink = null; workSink = null }

    /** The real host has one process-lifetime inference owner. Match that contract across tests. */
    private fun engine(log: (String, String) -> Unit,
                       observer: ((FrameInterpolationEngineHolder.WorkSample) -> Unit)? = null): FrameInterpolationEngineHolder {
        logSink = log
        workSink = observer
        return sharedEngine ?: FrameInterpolationEngineHolder(File(context.applicationInfo.sourceDir), context.codeCacheDir,
            File(context.filesDir, "rife-v4.6"), log = { e, d -> logSink?.invoke(e, d) },
            protectStaticDetails = InstrumentationRegistry.getArguments().getString("protectDetails") != "false",
            workObserver = { sample -> workSink?.invoke(sample) }).also { sharedEngine = it }
    }

    @Test fun realDecoderIsInterpolatedTo60AndTornDownCleanly() = run(sourceFps = 30, expectedFps = 60)

    @Test fun realDecoder25FpsIsInterpolatedTo50() = run(sourceFps = 25, expectedFps = 50)

    /** 超过正式捕获预算时必须在连接任何生产者前拒绝；4K 研究推理不能顺带放开播放入口。 */
    @Test fun oversizedCaptureIsRejectedBeforeConnectingSurface() {
        val model = File(context.filesDir, "rife-v4.6")
        assumeTrue("model not pushed", File(model, "flownet.bin").isFile)
        val engine = engine(log = { event, detail -> Log.i(TAG, "$event $detail") })
        engine.warmUp()
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!engine.ready() && engine.state != FrameInterpolationEngineHolder.State.FAILED && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("engine not ready", engine.ready())
        val decoder = MediaCodec.createDecoderByType("video/avc")
        val display = ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2)
        try {
            for ((w, h) in listOf(1920 to 1080, 2560 to 1440, 3840 to 2160)) {
                val events = ArrayList<String>()
                val host = object : FrameCaptureBridge.Host {
                    override fun setAudioDelayUs(delayUs: Long) = fail("rejected capture must not change audio")
                    override fun playbackSpeed() = 1.0
                    override fun audio() = FrameMediaPolicy.Audio()
                    override fun log(event: String, detail: String) { events += "$event $detail" }
                }
                val format = MediaFormat.createVideoFormat("video/avc", w, h).apply { setInteger(MediaFormat.KEY_FRAME_RATE, 30) }
                assertNull(FrameCaptureBridge.create(FrameCaptureBridge.Config(), decoder, format,
                    display.surface, null, engine, host, requestedTargetFps = 120))
                assertTrue("must reject size before presenter/target selection: $events", events.single().startsWith("bridge.skip format:${w}x$h"))
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("captureAdmission", JSONObject().put("width", w).put("height", h)
                        .put("admitted", false).put("reason", "capture pixel budget").toString())
                })
            }
        } finally { decoder.release(); display.close() }
    }

    /** 性能资格是报告字段；设备跑不满目标仍须完整记录并证明安全回退、拆除，不能把它伪装成 120 fps。 */
    @Test fun highRate120Metrics() {
        val args = InstrumentationRegistry.getArguments()
        width = args.getString("width", "1280").toInt()
        height = args.getString("height", "720").toInt()
        val source = args.getString("sourceFps", "60").toInt()
        val target = args.getString("targetFps", "120").toInt()
        val seconds = args.getString("seconds", "12").toDouble()
        require(width in 160..1280 && height in 90..720 && source in setOf(15, 24, 25, 30, 37, 60) && seconds in 6.0..120.0)
        require(target in setOf(15, 30, 45, 50, 60, 120, 144, 165, 185))
        run(source, target, diagnostic = true, seconds = seconds, onScreen = args.getString("screen") == "true")
    }

    private fun run(sourceFps: Int, expectedFps: Int, diagnostic: Boolean = false, seconds: Double = 3.0,
                    onScreen: Boolean = false) {
        val model = File(context.filesDir, "rife-v4.6")
        assumeTrue("model not pushed", File(model, "flownet.bin").isFile)
        val logs = Collections.synchronizedList(ArrayList<String>())
        val work = Collections.synchronizedList(ArrayList<FrameInterpolationEngineHolder.WorkSample>())
        val engine = engine(log = { e, d ->
            logs += "$e $d"; Log.i(TAG, "$e $d")
        }, observer = if (diagnostic) ({ sample -> work += sample }) else null)
        engine.warmUp()
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!engine.ready() && engine.state != FrameInterpolationEngineHolder.State.FAILED && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue("engine ${engine.state} $logs", engine.ready())

        val args = InstrumentationRegistry.getArguments()
        val motion = if (args.getString("highMotion") == "true") "-pan" else ""
        val clip = File(context.cacheDir, "frame-e2e-${width}x$height-$sourceFps-${seconds.toInt()}$motion.mp4")
        val frameCount = sourceFps * maxOf(6, seconds.toInt() + 2)
        val reuse = args.getString("reuseFixture") == "true" && clip.isFile && runCatching {
            val probe = MediaExtractor()
            try {
                probe.setDataSource(clip.path)
                val media = probe.getTrackFormat(0)
                media.getInteger(MediaFormat.KEY_WIDTH) == width && media.getInteger(MediaFormat.KEY_HEIGHT) == height &&
                    media.getInteger(MediaFormat.KEY_FRAME_RATE) == sourceFps &&
                    media.getLong(MediaFormat.KEY_DURATION) >= (frameCount - 1) * 1_000_000L / sourceFps
            } finally { probe.release() }
        }.getOrDefault(false)
        if (!reuse) encodeClip(clip, frames = frameCount, fps = sourceFps)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = if (onScreen) instrumentation.startActivitySync(Intent(context, FrameRateValidationActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("targetFps", expectedFps)
            .putExtra("texture", InstrumentationRegistry.getArguments().getString("texture") == "true")
            .putExtra("frtc", InstrumentationRegistry.getArguments().getString("frtc") == "true")
            .putExtra("frtcStrict", InstrumentationRegistry.getArguments().getString("frtcStrict") == "true")
            .putExtra("frameRateCategory", InstrumentationRegistry.getArguments().getString("frameRateCategory") == "true")
            .putExtra("vrrToken", InstrumentationRegistry.getArguments().getString("vrrToken") == "true")
            .putExtra("skipSynthetic", InstrumentationRegistry.getArguments().getString("skipSynthetic") == "true")
            ) as FrameRateValidationActivity else null
        if (activity != null) {
            assertTrue("screen surface not ready", activity.ready.await(3, TimeUnit.SECONDS))
            Thread.sleep(1_000)
        }

        val displayThread = HandlerThread("frame-e2e-display").apply { start() }
        val displayed = Collections.synchronizedList(ArrayList<Long>())
        val display = if (onScreen) null else ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4).apply {
            // 以到达时刻计：宿主 TextureView 不理会呈现时间戳，按到达在下一 UI 帧显示；声明的时间戳总是均匀的。
            setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { displayed += System.nanoTime(); it.close() } },
                Handler(displayThread.looper))
        }
        val submitted = Collections.synchronizedList(ArrayList<Long>())
        val submitLateness = Collections.synchronizedList(ArrayList<Long>())
        val resources = JSONArray()
        val power = context.getSystemService(PowerManager::class.java)
        val resourceThread = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "frame-resource-probe").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
        val sampleResources = Runnable {
            val now = System.nanoTime()
            if (diagnostic) {
                val info = JSONObject().put("atNs", now).put("pssKb", Debug.getPss())
                    .put("windowFocused", activity?.focused ?: true)
                    .put("elapsedRealtimeNs", android.os.SystemClock.elapsedRealtimeNanos())
                    .put("nativeHeapKb", Debug.getNativeHeapAllocatedSize() / 1024)
                val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                info.put("batteryTemperatureC", (battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0)
                    .put("plugged", battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.put("thermalStatus", power.currentThermalStatus)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val headroom = power.getThermalHeadroom(10)
                    info.put("thermalHeadroom", if (headroom.isFinite()) headroom.toDouble() else JSONObject.NULL)
                }
                info.put("probeDurationMs", (System.nanoTime() - now) / 1e6)
                resources.put(info)
            }
        }
        val delays = Collections.synchronizedList(ArrayList<Long>())
        val host = object : FrameCaptureBridge.Host {
            override fun setAudioDelayUs(delayUs: Long) { if (delayUs > 0) delays += delayUs }
            override fun playbackSpeed() = 1.0
            override fun audio() = FrameMediaPolicy.Audio(output = FrameMediaPolicy.AudioOutput.PCM,
                offloaded = FrameMediaPolicy.Evidence.NO, tunneled = FrameMediaPolicy.Evidence.NO)
            override fun log(event: String, detail: String) { logs += "$event $detail"; Log.i(TAG, "$event $detail") }
            override fun context(): android.content.Context = this@FrameCaptureBridgeDeviceTest.context
            override fun onFrameSubmitted(dueNs: Long, submittedNs: Long) {
                submitted += submittedNs
                submitLateness += submittedNs - dueNs
            }
        }

        val extractor = MediaExtractor().apply { setDataSource(clip.path); selectTrack(0) }
        val format = extractor.getTrackFormat(0)
        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        val stageUploads = InstrumentationRegistry.getArguments().getString("stageUploads", "true") == "true"
        val bridge = FrameCaptureBridge.create(FrameCaptureBridge.Config(stageUploads = stageUploads), decoder, format,
            activity?.outputSurface ?: display!!.surface, null, engine, host,
            requestedTargetFps = expectedFps.takeIf { diagnostic })
        assertNotNull("bridge not created: $logs", bridge)
        decoder.configure(format, bridge!!.captureSurface, null, 0)
        decoder.start()

        val firstStart = System.nanoTime()
        val cpuStart = android.os.Process.getElapsedCpuTime()
        // PSS walks page tables and may stall for tens of milliseconds. Never run it on source release.
        if (diagnostic) resourceThread.scheduleWithFixedDelay(sampleResources, 0, 1, java.util.concurrent.TimeUnit.SECONDS)
        val beforeSeek: FrameInterpolationSession.Snapshot
        val afterSeek: FrameInterpolationSession.Snapshot
        val native: Boolean
        val secondStart: Long
        val secondEnd: Long
        try {
            play(decoder, extractor, seconds = 4.0)
            beforeSeek = bridge.diagnostics().first
            // seek：与 ijk 相同先 flush 解码器，捕获桥由 Hook 收到 onFlush。
            bridge.onFlush()
            decoder.flush()
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            secondStart = System.nanoTime()
            play(decoder, extractor, seconds = seconds)
            secondEnd = System.nanoTime()
            val diagnostics = bridge.diagnostics()
            afterSeek = diagnostics.first
            native = diagnostics.second
        } finally {
            resourceThread.shutdown()
            check(resourceThread.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) { "resource probe did not stop" }
        }
        val cpuMs = android.os.Process.getElapsedCpuTime() - cpuStart
        @Suppress("DEPRECATION")
        val refresh = activity?.windowManager?.defaultDisplay?.refreshRate ?: 0f
        val physicalTimes = if (onScreen) readPhysicalPresentTimes()
            .filter { it in (secondStart + 1_000_000_000L)..secondEnd } else emptyList()
        val uniquePhysical = physicalTimes.distinct().sorted()
        val physicalIntervals = uniquePhysical.zipWithNext { a, b -> b - a }.sorted()
        val physicalHitches = physicalIntervals.count { it > 1e9 / expectedFps * 1.5 }
        val physicalFps = if (uniquePhysical.size > 1)
            (uniquePhysical.size - 1) * 1e9 / (uniquePhysical.last() - uniquePhysical.first()) else 0.0
        val textureSamples = activity?.textureUpdates?.let { synchronized(it) { it.toList() } }.orEmpty()
            .filter { it.first in (secondStart + 1_000_000_000L)..secondEnd && it.second > 0 }.distinctBy { it.second }
        val textureFps = if (textureSamples.size > 1)
            (textureSamples.size - 1) * 1e9 / (textureSamples.last().first - textureSamples.first().first) else 0.0
        decoder.stop()
        bridge.close()
        decoder.release()
        extractor.release()
        Thread.sleep(500)
        val (closed, _) = bridge.diagnostics()
        display?.close()
        displayThread.quitSafely()
        activity?.let { instrumentation.runOnMainSync { it.finish() } }

        // 第二段稳态（跳过 seek 后前 1 s）的显示间隔。
        val steady = (if (onScreen) submitted else displayed).filter { it in (secondStart + 1_000_000_000L)..secondEnd }.sorted()
        val intervals = steady.zipWithNext { a, b -> (b - a) / 1e6 }
        val fps = if (steady.size > 1) (steady.size - 1) * 1e9 / (steady.last() - steady.first()) else 0.0
        val period = 1e9 / expectedFps
        val hitches = intervals.count { it * 1e6 > period * 1.5 }
        val summary = "first=${(secondStart - firstStart) / 1_000_000}ms displayed=${displayed.size} hitches=$hitches/${intervals.size} " +
            "before=$beforeSeek after=$afterSeek closed=$closed native=$native steadyFps=%.1f ".format(fps) +
            "maxGapMs=%.1f delayUs=${delays.minOrNull()}..${delays.maxOrNull()}".format(intervals.maxOrNull() ?: 0.0)
        Log.i(TAG, summary)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("e2e", summary) })

        if (diagnostic) {
            val samples = synchronized(work) { work.toList() }
            val inference = samples.filter { !it.bypassed && it.status == FrameInterpolationBackend.Status.OK }
            val wall = inference.map { it.wallNs }.sorted()
            fun percentile(values: List<Long>, fraction: Double): Double = if (values.isEmpty()) 0.0
                else values[(kotlin.math.ceil(values.size * fraction).toInt() - 1).coerceIn(values.indices)] / 1e6
            val presentedDelta = afterSeek.presented - beforeSeek.presented
            val interpolatedDelta = afterSeek.interpolated - beforeSeek.interpolated
            val fraction = if (presentedDelta > 0) interpolatedDelta.toDouble() / presentedDelta else 0.0
            val expectedFraction = (expectedFps - sourceFps).coerceAtLeast(0).toDouble() / expectedFps
            val texture = InstrumentationRegistry.getArguments().getString("texture") == "true"
            val focusLost = (0 until resources.length()).any { !resources.getJSONObject(it).getBoolean("windowFocused") }
            val reasons = JSONArray()
            if (onScreen && refresh < expectedFps*.99) reasons.put("LOGICAL_DISPLAY_RATE")
            if (onScreen && physicalFps < expectedFps*.95) reasons.put("PHYSICAL_PRESENT_RATE")
            if (onScreen && physicalHitches * 50 > physicalIntervals.size) reasons.put("PHYSICAL_LONG_INTERVALS")
            if (onScreen && texture && textureFps < expectedFps*.95) reasons.put("TEXTURE_CONSUMPTION_RATE")
            if (focusLost) reasons.put("WINDOW_FOCUS_LOST")
            if (native) reasons.put("NATIVE_FALLBACK")
            if (fps < expectedFps*.95) reasons.put("PRODUCER_RATE")
            if (hitches*50 > intervals.size) reasons.put("LONG_INTERVALS")
            if (fraction < expectedFraction*.9) reasons.put("INSUFFICIENT_SYNTHETIC_FRAMES")
            if (afterSeek.late != beforeSeek.late) reasons.put("STEADY_LATE")
            val qualifies = reasons.length() == 0
            val result = JSONObject().put("width", width).put("height", height).put("sourceFps", sourceFps)
                .put("targetFps", expectedFps).put("seconds", seconds).put("screen", onScreen).put("displayHz", refresh)
                .put("physicalPresentFps", physicalFps).put("physicalDistinctFrames", uniquePhysical.size)
                .put("physicalRepeatedTimestamps", physicalTimes.size - uniquePhysical.size)
                .put("physicalHitches", physicalHitches).put("physicalIntervalCount", physicalIntervals.size)
                .put("physicalIntervalP50Ms", percentile(physicalIntervals, .5))
                .put("physicalIntervalP90Ms", percentile(physicalIntervals, .9))
                .put("physicalIntervalP99Ms", percentile(physicalIntervals, .99))
                .put("physicalPresentTimesNs", JSONArray(physicalTimes))
                .put("textureConsumedFps", textureFps).put("textureDistinctFrames", textureSamples.size)
                .put("vendorRequestResult", activity?.vendorRequestResult)
                .put("frameRateCategory", InstrumentationRegistry.getArguments().getString("frameRateCategory") == "true")
                .put("windowFocusLost", focusLost)
                .put("sfRefreshHz", if (sfPeriodNs > 0) 1e9/sfPeriodNs else 0.0)
                .put("measurementStartNs", secondStart + 1_000_000_000L).put("measurementEndNs", secondEnd)
                .put("failureReasons", reasons)
                .put("motionFixture", if (InstrumentationRegistry.getArguments().getString("highMotion") == "true") "checkerboard pan" else "gradient and moving rectangle")
                .put("surfaceKind", if (InstrumentationRegistry.getArguments().getString("texture") == "true") "TextureView" else "SurfaceView/ImageReader")
                .put("measuredFps", fps).put("fpsEvidence", if (onScreen) "EGL swap completion; physical scanout requires SurfaceFlinger" else "ImageReader arrivals")
                .put("qualifiesTarget", qualifies).put("nativeFallback", native).put("hitches", hitches).put("intervalCount", intervals.size)
                .put("intervalP50Ms", percentile(steady.zipWithNext { a, b -> b - a }.sorted(), .5))
                .put("intervalP90Ms", percentile(steady.zipWithNext { a, b -> b - a }.sorted(), .9))
                .put("intervalP99Ms", percentile(steady.zipWithNext { a, b -> b - a }.sorted(), .99))
                .put("workCount", samples.size).put("bypassed", samples.count { it.bypassed })
                .put("inferenceP50Ms", percentile(wall, .5)).put("inferenceP90Ms", percentile(wall, .9))
                .put("inferenceP99Ms", percentile(wall, .99)).put("inferenceMaxMs", (wall.lastOrNull() ?: 0L) / 1e6)
                .put("workerCpuMeanMs", if (inference.isEmpty()) 0.0 else inference.map { it.cpuNs }.average() / 1e6)
                .put("processCpuMs", cpuMs).put("late", afterSeek.late).put("missedCommit", afterSeek.missedCommit)
                .put("steadyLate", afterSeek.late - beforeSeek.late)
                .put("steadyMissedCommit", afterSeek.missedCommit - beforeSeek.missedCommit)
                .put("totalPresented", afterSeek.presented).put("totalInterpolated", afterSeek.interpolated)
                .put("inferenceFailures", afterSeek.failedInference - beforeSeek.failedInference)
                .put("presented", presentedDelta).put("interpolated", interpolatedDelta).put("interpolatedFraction", fraction)
                .put("closedLiveBuffers", closed.ledger.live).put("resources", resources)
                .put("stageUploads", stageUploads)
                .put("reusedFixture", reuse)
            context.filesDir.resolve("frame-high-rate-results.jsonl").appendText(result.toString() + "\n")
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("highRate", result.toString()) })
            assertTrue("explicit target not used: $logs", logs.any { it.startsWith("bridge.target ${expectedFps}fps") })
            assertEquals(0, closed.ledger.live)
            assertEquals(FrameInterpolationSession.Phase.CLOSED, closed.phase)
            return
        }

        assertTrue(logs.toString(), logs.any { it.startsWith("bridge.admit READY") })
        assertTrue(logs.toString(), logs.any { it.startsWith("bridge.target ${expectedFps}fps") })
        assertFalse("fell back to native: ${logs.filter { it.startsWith("bridge.") }}", native)
        assertTrue(afterSeek.presented > 0 && afterSeek.interpolated > 0)
        assertTrue("steady fps $fps", fps in expectedFps * 0.9..expectedFps * 1.1)
        assertTrue("arrival hitches $hitches of ${intervals.size}", hitches * 50 <= intervals.size)
        assertTrue("delay $delays", delays.isNotEmpty() && delays.all { it in 100_000L..(101_000L + 1_000_000L / sourceFps) })
        assertEquals(FrameInterpolationSession.Phase.CLOSED, closed.phase)
        assertEquals("ledger after close ${closed.ledger}", 0, closed.ledger.live)
    }

    /** SurfaceFlinger 的实际 present 列会重复；按去重后的时间统计，不能把提交 120 次误报成显示 120 次。 */
    private fun readPhysicalPresentTimes(): List<Long> {
        fun shell(command: String): String {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return ""
            // executeShellCommand(String) 使用 Runtime.exec，不能按 shell 引号分组带空格的图层名。
            // 通过 sh 的 stdin 提供脚本，保留整个经单引号转义的参数，并正确关闭两个管道。
            val pipes = automation.executeShellCommandRw("sh")
            return try {
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
                    it.write((command + "\nexit\n").toByteArray(Charsets.UTF_8))
                }
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
            } finally { pipes.forEach { runCatching { it.close() } } }
        }
        return runCatching {
            val layer = shell("dumpsys SurfaceFlinger --list").lineSequence()
                .firstOrNull { it.contains("FrameRateValidationActivity") &&
                    if (InstrumentationRegistry.getArguments().getString("texture") == "true") it.contains("{VRI-")
                    else it.contains("SurfaceView[") && it.contains("(BLAST)") }
                ?.substringAfter('{')?.substringBefore(" parentId=") ?: return emptyList()
            val quoted = "'" + layer.replace("'", "'\\''") + "'"
            val raw = shell("dumpsys SurfaceFlinger --latency $quoted")
            sfPeriodNs = raw.lineSequence().firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            context.filesDir.resolve("frame-screen-latency.txt").writeText(raw)
            raw.lineSequence().drop(1).mapNotNull { line ->
                line.trim().split(Regex("\\s+")).takeIf { it.size == 3 }?.get(1)?.toLongOrNull()
                    ?.takeIf { it > 0L && it != Long.MAX_VALUE }
            }.toList()
        }.getOrDefault(emptyList())
    }

    /** 按 PTS 墙钟时刻渲染，模拟 ijk 的音频主时钟同步。 */
    private fun play(decoder: MediaCodec, extractor: MediaExtractor, seconds: Double) {
        val info = MediaCodec.BufferInfo()
        var basePts = -1L
        var baseNs = 0L
        var inputDone = false
        val end = System.nanoTime() + (seconds * 1e9).toLong()
        while (System.nanoTime() < end) {
            if (!inputDone) {
                val index = decoder.dequeueInputBuffer(2_000)
                if (index >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                    if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val output = decoder.dequeueOutputBuffer(info, 2_000)
            if (output < 0) continue
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { decoder.releaseOutputBuffer(output, false); return }
            if (basePts < 0) { basePts = info.presentationTimeUs; baseNs = System.nanoTime() + 50_000_000L }
            val due = baseNs + (info.presentationTimeUs - basePts) * 1_000L
            while (System.nanoTime() < due) Thread.sleep(0, 500_000)
            decoder.releaseOutputBuffer(output, true)
        }
    }

    /** 1280×720@30 H.264，BT.709 limited SDR：斜向渐变背景与每帧右移 16 px 的亮块。 */
    private fun encodeClip(file: File, frames: Int, fps: Int) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var input = 0
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val pts = input * 1_000_000L / fps
                        if (input == frames) {
                            encoder.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            fill(encoder.getInputImage(index)!!, input)
                            encoder.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
                            input++
                        }
                    }
                }
                val output = encoder.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(encoder.outputFormat); muxer.start() }
                else if (output >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) muxer.writeSampleData(track, encoder.getOutputBuffer(output)!!, info)
                    encoder.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { muxer.stop() }; muxer.release(); encoder.stop(); encoder.release()
        }
    }

    private fun fill(image: Image, index: Int) {
        val (y, u, v) = image.planes
        val boxWidth = width / 8
        val boxX = (index * maxOf(1, width / 80)) % (width - boxWidth)
        val boxY = height * 7 / 18
        val boxBottom = boxY + height * 2 / 9
        val row = ByteArray(width)
        val highMotion = InstrumentationRegistry.getArguments().getString("highMotion") == "true"
        for (line in 0 until height) {
            for (x in 0 until width) row[x] = if (highMotion)
                (if (((x + index * maxOf(1,width/24))/maxOf(1,width/8) + line/maxOf(1,height/6))%2==0) 40 else 220).toByte()
                else (16 + (x + line) * 200 / (width + height)).toByte()
            if (line in boxY until boxBottom) for (x in boxX until boxX + boxWidth) row[x] = 235.toByte()
            y.buffer.position(line * y.rowStride)
            y.buffer.put(row, 0, width)
        }
        for (plane in listOf(u, v)) for (line in 0 until height / 2) for (x in 0 until width / 2)
            plane.buffer.put(line * plane.rowStride + x * plane.pixelStride, 128.toByte())
    }

    private companion object {
        const val TAG = "FrameE2E"
        var sharedEngine: FrameInterpolationEngineHolder? = null
        @Volatile var logSink: ((String, String) -> Unit)? = null
        @Volatile var workSink: ((FrameInterpolationEngineHolder.WorkSample) -> Unit)? = null
    }
}
