package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 呈现线程：RGB8 帧经 glTexSubImage2D 上传后画到宿主显示 Surface，在槽位前精确定时交付并附呈现时刻；
 * 上传返回即完成对客户端内存的拷贝，随后立即 done 归还缓冲。本线程不做捕获读回，避免读回阻塞推迟交付。
 *
 * [restoreNative] 先销毁显示 EGL 窗口（断开生产者连接），再回调外层把解码器输出切回显示 Surface；
 * 顺序颠倒会让 MediaCodec 连接失败。
 */
internal class FrameGlPresenter(
    private val target: Surface,
    private val targetFps: Int = 60,
    private val stageUploads: Boolean = true,
    private val onNativeRestored: (FramePresentationRecovery.Report) -> Unit
) : FrameInterpolationSession.Presenter {
    private val lock = Object()
    private val commands = ArrayDeque<() -> Unit>()
    private val pending = java.util.PriorityQueue<Scheduled>(compareBy { it.dueNs })
    /**
     * 呈现时间戳提前半个目标周期：槽位网格锚在 vsync 时刻上，若时间戳正落在刷新边沿，几毫秒抖动就决定赶上本次还是
     * 下一次刷新，两帧挤进同一刷新、下一刷新没有新帧（宿主真机 SurfaceFlinger timestats：每秒约 3 次 33 ms 间隔）。
     * 放在刷新间隔中间后，SurfaceFlinger 总在目标刷新显示，与提交抖动无关（Android Frame Pacing 的做法）。
     */
    private val timestampLeadNs = 1_000_000_000L / targetFps.coerceAtLeast(1) / 2
    @Volatile private var running = true
    private val thread = Thread({ loop() }, "innocent-frame-present").apply {
        priority = Thread.MAX_PRIORITY
        isDaemon = true
        start()
    }
    private var egl: FrameGl.Egl? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var rgbProgram = 0
    private var rgbTexture = 0
    private var rgbWidth = 0
    private var rgbHeight = 0
    private var staged: Scheduled? = null
    @Volatile private var connected = false
    var onSubmitted: ((Long, Long) -> Unit)? = null

    /** 同步连接显示 Surface；失败时不连接，外层应保持原生呈现。 */
    fun start(timeoutMs: Long = 1_000L): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        post {
            ok = runCatching { setUp() }.getOrDefault(false)
            if (!ok) tearDown()
            latch.countDown()
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS) && ok
    }

    /**
     * 精确定时交付：宿主用 TextureView 呈现（主进程 SurfaceTexture），它不理会呈现时间戳，按到达在下一 UI 帧显示；
     * 交付时刻的抖动直接变成画面顿挫。Handler 定时只有毫秒精度且受消息排队影响，无窗口进程的 Choreographer
     * 又会被系统降频（真机两帧挤进一个 vsync 而丢帧），因此用独立线程 parkNanos 睡到槽位前 [PRESENT_LEAD_NS] 再画。
     */
    override fun present(frameId: Long, buffer: ByteBuffer, format: FrameBufferLedger.Format, dueNs: Long,
                         done: (FrameInterpolationSession.Outcome) -> Unit): Boolean {
        synchronized(lock) {
            if (!running) return false
            pending += Scheduled(buffer, format, dueNs, done)
            lock.notifyAll()
        }
        return true
    }

    override fun restoreNative(report: FramePresentationRecovery.Report) {
        post {
            settlePending()
            tearDown()
            runCatching { onNativeRestored(report) }
        }
    }

    /** 拆除：撤回未呈现帧并断开 EGL 后结束线程。 */
    fun close() {
        post { settlePending(); tearDown() }
        synchronized(lock) { running = false; lock.notifyAll() }
    }

    private fun post(command: () -> Unit) = synchronized(lock) { commands += command; lock.notifyAll() }

    private fun loop() {
        while (true) {
            val command: (() -> Unit)?
            val head: Scheduled?
            synchronized(lock) {
                while (commands.isEmpty()) {
                    val next = pending.peek()
                    if (next == null) {
                        if (!running) return
                        lock.wait()
                        continue
                    }
                    if (stageUploads && staged !== next) break
                    // Object.wait 只有毫秒级唤醒；最后 SPIN_THRESHOLD_NS 改为出锁 parkNanos 精确等待。
                    val wait = waitNs(next) - SPIN_THRESHOLD_NS
                    if (wait <= 0L) break
                    lock.wait(wait / 1_000_000L, (wait % 1_000_000L).toInt())
                }
                command = commands.removeFirstOrNull()
                head = if (command == null) pending.peek() else null
            }
            if (command != null) { runCatching(command); continue }
            val target = head ?: continue
            if (stageUploads && staged !== target) {
                // Upload while the frame still has its planning lead, rather than in the final 4 ms.
                // A newly arrived earlier proposal replaces the texture; the later head is restaged.
                if (!runCatching { upload(target.buffer, target.format) }.getOrDefault(false)) {
                    val item = synchronized(lock) { if (pending.peek() === target) pending.poll() else null }
                    item?.done?.invoke(FrameInterpolationSession.Outcome.SurfaceLost(System.nanoTime()))
                } else staged = target
                continue
            }
            val wait = waitNs(target)
            if (wait > 0L) java.util.concurrent.locks.LockSupport.parkNanos(wait)
            val item = synchronized(lock) { if (pending.peek() === target) pending.poll() else null } ?: continue
            deliver(item)
        }
    }

    private fun waitNs(item: Scheduled) = item.dueNs - timestampLeadNs - PRESENT_LEAD_NS - System.nanoTime()

    /** 已错过下一个槽位的帧不再画（画了只会挤掉后一帧）；其余按时画并回报。 */
    private fun deliver(item: Scheduled) {
        val started = System.nanoTime()
        val next = synchronized(lock) { pending.peek() }
        if (next != null && next.dueNs - timestampLeadNs - PRESENT_LEAD_NS <= started) {
            runCatching { item.done(FrameInterpolationSession.Outcome.Dropped(started)) }
            return
        }
        val outcome = runCatching {
            if ((!stageUploads || staged !== item) && !upload(item.buffer, item.format)) false else draw(item.dueNs - timestampLeadNs)
        }.getOrDefault(false)
        staged = null
        val submitted = System.nanoTime()
        if (outcome) runCatching { onSubmitted?.invoke(item.dueNs, submitted) }
        item.done(if (outcome) FrameInterpolationSession.Outcome.Presented(submitted)
            else FrameInterpolationSession.Outcome.SurfaceLost(System.nanoTime()))
    }

    /**
     * 撤回所有尚未呈现的帧并逐个以 Dropped 回调，保证每个 PRESENTER 持有都被归还（真机曾因丢弃的延后消息
     * 在拆除后残留 1–2 帧）。
     */
    private fun settlePending() {
        val drained = synchronized(lock) { pending.toList().also { pending.clear() } }
        staged = null
        val now = System.nanoTime()
        for (item in drained) runCatching { item.done(FrameInterpolationSession.Outcome.Dropped(now)) }
    }

    private class Scheduled(val buffer: ByteBuffer, val format: FrameBufferLedger.Format, val dueNs: Long,
                            val done: (FrameInterpolationSession.Outcome) -> Unit)

    private fun setUp(): Boolean {
        val created = FrameGl.create(EGL14.EGL_WINDOW_BIT, highPriority = true) ?: return false
        egl = created
        window = EGL14.eglCreateWindowSurface(created.display, created.config, target, intArrayOf(EGL14.EGL_NONE), 0)
        if (window == EGL14.EGL_NO_SURFACE) return false
        connected = true
        if (!EGL14.eglMakeCurrent(created.display, window, window, created.context)) return false
        // Request on the actual producer after connection, not only on its enclosing activity.
        // The system still owns thermal/user limits; this is a cadence hint, never a global override.
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching {
            target.setFrameRate(targetFps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ALWAYS)
        }
        rgbProgram = FrameGl.program(VERTEX, RGB_FRAGMENT) ?: return false
        rgbTexture = FrameGl.texture(GLES20.GL_TEXTURE_2D)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    private fun upload(buffer: ByteBuffer, format: FrameBufferLedger.Format): Boolean {
        if (!connected || format.stride != format.width * 3) return false
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rgbTexture)
        val pixels = buffer.duplicate().also { it.position(0) }
        if (format.width != rgbWidth || format.height != rgbHeight) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, format.width, format.height, 0,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, pixels)
            rgbWidth = format.width
            rgbHeight = format.height
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, format.width, format.height,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, pixels)
        }
        return GLES20.glGetError() == GLES20.GL_NO_ERROR
    }

    private fun draw(dueNs: Long): Boolean {
        if (!connected) return false
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rgbTexture)
        val size = IntArray(2)
        EGL14.eglQuerySurface(egl!!.display, window, EGL14.EGL_WIDTH, size, 0)
        EGL14.eglQuerySurface(egl!!.display, window, EGL14.EGL_HEIGHT, size, 1)
        GLES20.glViewport(0, 0, size[0], size[1])
        GLES20.glUseProgram(rgbProgram)
        FrameGl.bindQuad(rgbProgram, FrameGl.FLIPPED_QUAD)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return false
        EGLExt.eglPresentationTimeANDROID(egl!!.display, window, dueNs)
        return EGL14.eglSwapBuffers(egl!!.display, window)
    }


    /** 断开显示窗口并释放上下文；之后解码器才能重新连接显示 Surface。 */
    private fun tearDown() {
        val current = egl ?: return
        if (android.os.Build.VERSION.SDK_INT >= 31) runCatching {
            target.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        EGL14.eglMakeCurrent(current.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(current.display, window)
        window = EGL14.EGL_NO_SURFACE
        connected = false
        FrameGl.release(current)
        egl = null
    }

    private companion object {
        const val PRESENT_LEAD_NS = 4_000_000L
        const val SPIN_THRESHOLD_NS = 2_000_000L
        const val VERTEX = "attribute vec2 aPosition; attribute vec2 aCoordinate; varying vec2 vCoordinate;" +
            "void main() { vCoordinate = aCoordinate; gl_Position = vec4(aPosition, 0.0, 1.0); }"
        const val RGB_FRAGMENT = "precision mediump float; varying vec2 vCoordinate; uniform sampler2D uTexture;" +
            "void main() { gl_FragColor = texture2D(uTexture, vCoordinate); }"
    }
}
