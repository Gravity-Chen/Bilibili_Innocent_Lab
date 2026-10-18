package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenPrismGlass
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostPrismInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun prismSamplesCroppedContentMorphsAndReleasesWithoutIdleWork() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var root: FrameLayout
            lateinit var content: FrameLayout
            lateinit var detail: View
            lateinit var top: FrameLayout
            lateinit var bottom: FrameLayout
            var phase = 0f
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                root = FrameLayout(activity).apply { clipChildren = true; setBackgroundColor(Color.WHITE) }
                content = FrameLayout(activity)
                detail = object : View(activity) {
                    private val paint = Paint()
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(if (phase < .5f) 0xFF52B8C5.toInt() else 0xFFE879AA.toInt())
                        paint.color = Color.WHITE
                        val offset = phase * 24f
                        for (x in -24 until width step 24)
                            canvas.drawRect(x + offset, 0f, x + offset + 8f, height.toFloat(), paint)
                    }
                }
                content.addView(detail, FrameLayout.LayoutParams(-1, -1))
                root.addView(content, FrameLayout.LayoutParams(-1, -1))
                top = FrameLayout(activity).apply { setPadding(3, 5, 7, 9); clipToOutline = true }
                bottom = FrameLayout(activity).apply { setPadding(3, 5, 7, 9); clipToOutline = true }
                root.addView(top, FrameLayout.LayoutParams(-1, 132).apply {
                    topMargin = 250; leftMargin = 65; rightMargin = 65
                })
                root.addView(bottom, FrameLayout.LayoutParams(-1, 132).apply {
                    gravity = android.view.Gravity.BOTTOM; bottomMargin = 160; leftMargin = 95; rightMargin = 95
                })
                activity.setContentView(root)
            }
            val output = File(instrumentation.targetContext.getExternalFilesDir(null), "host-prism").apply { mkdirs() }
            val results = ArrayList<String>()
            val metricsThread = HandlerThread("PrismFrameMetrics").apply { start() }
            val frames = Collections.synchronizedMap(LinkedHashMap<Long, Long>())
            val gpu = Collections.synchronizedMap(LinkedHashMap<Long, Long>())
            val drawing = Collections.synchronizedMap(LinkedHashMap<Long, Long>())
            val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                    val vsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                    frames[vsync] = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    drawing[vsync] = metrics.getMetric(FrameMetrics.DRAW_DURATION)
                    if (Build.VERSION.SDK_INT >= 31) {
                        gpu[vsync] = metrics.getMetric(FrameMetrics.GPU_DURATION)
                    }
                }
            }
            instrumentation.runOnMainSync { window.addOnFrameMetricsAvailableListener(listener, Handler(metricsThread.looper)) }
            var topScope: HostSurfaceScope? = null
            var bottomScope: HostSurfaceScope? = null
            var animator: ValueAnimator? = null
            fun animate(profile: String) {
                frames.clear(); gpu.clear(); drawing.clear()
                val animated = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 2400
                        addUpdateListener { phase = it.animatedValue as Float; detail.invalidate() }
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) { animated.countDown() }
                        })
                        start()
                    }
                }
                assertTrue(animated.await(8, TimeUnit.SECONDS))
                SystemClock.sleep(150)
                val durations = synchronized(frames) { frames.values.sorted() }
                val gpuDurations = synchronized(gpu) { gpu.values.sorted() }
                val drawDurations = synchronized(drawing) { drawing.values.sorted() }
                assertTrue("No frame metrics", durations.size > 30)
                results += "$profile uniqueVsync=${durations.size}, p50Ms=${durations[durations.size / 2] / 1e6}, p95Ms=${durations[(durations.size * .95).toInt()] / 1e6}, gpuP95Ms=${gpuDurations.takeIf { it.isNotEmpty() }?.let { it[(it.size * .95).toInt()] / 1e6 }}, drawP95Ms=${drawDurations[(drawDurations.size * .95).toInt()] / 1e6}"
            }
            fun capture(name: String): Int {
                val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
                val done = CountDownLatch(1)
                var result = -1
                PixelCopy.request(window, bitmap, { result = it; done.countDown() }, Handler(window.decorView.handler.looper))
                assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
                val point = IntArray(2)
                instrumentation.runOnMainSync { bottom.getLocationInWindow(point) }
                return try {
                    File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.getPixel(point[0] + bottom.width / 2, point[1] + bottom.height / 2)
                } finally { bitmap.recycle() }
            }
            try {
                for (material in listOf(HostGlassMaterial.SOFT, HostGlassMaterial.PRISM)) {
                    instrumentation.runOnMainSync {
                        phase = 0f; detail.invalidate()
                        val colors = HostChromeColors(false, Color.BLUE)
                        val appearance = HostChromeAppearance(material)
                        topScope = HostSurfaceScope(top, colors, appearance = appearance).also {
                            it.attach(top, content); it.floating(top, false, 24f)
                        }
                        bottomScope = HostSurfaceScope(bottom, colors, appearance = appearance).also {
                            it.attach(bottom, content); it.floating(bottom, false, 24f)
                        }
                    }
                    SystemClock.sleep(800)
                    val before = capture("$material-before")
                    if (material == HostGlassMaterial.PRISM) {
                        instrumentation.runOnMainSync {
                            val state = requireNotNull(topScope?.prismDiagnostics(top))
                            results += "prism=$state; sampling=${topScope?.samplingPerformance()}"
                            assertTrue("Prism did not sample: $state", state.glassVisible)
                            if (Build.VERSION.SDK_INT >= 33) {
                                assertEquals(LumenSurfaceBackend.GPU, state.backdropBackend)
                                assertTrue("Prism lens is not active: $state", state.lensActive)
                            }
                            assertTrue(requireNotNull(topScope?.samplingPerformance()).logicalGpuContentPixels < root.width.toLong() * root.height / 2)
                        }
                    }
                    var idle = 0L
                    instrumentation.runOnMainSync { idle = requireNotNull(topScope?.diagnostics()).contentRecordings }
                    SystemClock.sleep(600)
                    instrumentation.runOnMainSync {
                        val more = requireNotNull(topScope?.diagnostics()).contentRecordings - idle
                        results += "$material idle recordings=$more"
                        assertTrue("$material keeps recording while idle: $more", more <= 2)
                    }
                    animate(material.name)
                    val after = capture("$material-after")
                    assertTrue("$material froze the backdrop", Color.red(after) > Color.red(before) + 25)
                    if (material == HostGlassMaterial.PRISM) {
                        instrumentation.runOnMainSync {
                            topScope?.shape(top, top.width / 2f - 66f, 8f)
                            bottom.translationX = 25f; bottom.translationY = -35f
                            topScope?.onVisualMovement(); bottomScope?.onVisualMovement()
                            topScope?.updatePalette(HostChromeColors(true, Color.BLUE))
                        }
                        SystemClock.sleep(400)
                        capture("prism-morphed-dark")
                        instrumentation.runOnMainSync {
                            assertTrue(requireNotNull(topScope?.prismDiagnostics(top)).dark)
                            topScope?.detach()
                            assertEquals(4, root.childCount) // bottom's prism is the remaining sibling
                            assertFalse(root.clipChildren)
                        }
                    }
                    instrumentation.runOnMainSync {
                        topScope?.close(); bottomScope?.close()
                        assertNull(top.background); assertNull(bottom.background)
                        assertEquals(3, root.childCount)
                        assertTrue(root.clipChildren)
                        assertEquals(3, top.paddingLeft); assertEquals(9, bottom.paddingBottom)
                    }
                }
                // Official direct entry as a reference, without the module's crop/geometry adapter.
                lateinit var directSession: LumenSurfaceSession
                lateinit var directTop: LumenPrismGlass
                lateinit var directBottom: LumenPrismGlass
                instrumentation.runOnMainSync {
                    phase = 0f; detail.invalidate()
                    bottom.translationX = 0f; bottom.translationY = 0f
                    val palette = HostChromeColors(false, Color.BLUE).palette()
                    directSession = LumenSurfaceSession(root.context, palette)
                    directTop = LumenPrismGlass(top, palette, LumenPrismOptions(), directSession, content)
                    directBottom = LumenPrismGlass(bottom, palette, LumenPrismOptions(), directSession, content)
                }
                try {
                    SystemClock.sleep(800)
                    animate("ENGINE_PRISM")
                    instrumentation.runOnMainSync { results += "direct engine=${directSession.performanceDiagnostics()}" }
                } finally {
                    instrumentation.runOnMainSync { directTop.close(); directBottom.close(); directSession.close() }
                }
                instrumentation.runOnMainSync {
                    val foreign = ColorDrawable(Color.GREEN)
                    val colors = HostChromeColors(false, Color.BLUE)
                    topScope = HostSurfaceScope(top, colors, appearance = HostChromeAppearance(HostGlassMaterial.PRISM)).also {
                        it.attach(top, content); it.floating(top, false, 24f)
                    }
                    top.background = foreign
                    topScope?.close()
                    assertSame(foreign, top.background)
                }
            } finally {
                instrumentation.runOnMainSync {
                    animator?.cancel(); topScope?.close(); bottomScope?.close()
                    window.removeOnFrameMetricsAvailableListener(listener)
                }
                metricsThread.quitSafely()
                File(output, "results.txt").writeText(results.joinToString("\n"))
            }
        }
    }
}
