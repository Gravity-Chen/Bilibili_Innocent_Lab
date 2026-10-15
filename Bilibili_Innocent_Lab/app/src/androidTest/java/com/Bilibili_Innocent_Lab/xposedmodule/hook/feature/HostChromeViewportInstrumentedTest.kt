package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeContentSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeViewportSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenSourceAvailability
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostChromeViewportInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun croppedChromePreservesPixelsAndTracksMovementWithLessGpuContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var cropped: HostSurfaceScope
            lateinit var reference: LumenSurfaceSession
            lateinit var first: View
            lateinit var second: View
            lateinit var owner: FrameLayout
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                val root = FrameLayout(activity)
                fun fixture(left: Int): Pair<FrameLayout, View> {
                    val owner = FrameLayout(activity).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(Color.BLUE, Color.RED))
                    }
                    // A native foreground as well as the owner's background must survive cropping.
                    owner.addView(View(activity).apply { setBackgroundColor(Color.GREEN) },
                        FrameLayout.LayoutParams(480, 110).apply { topMargin = 340 })
                    val glass = View(activity)
                    root.addView(owner, FrameLayout.LayoutParams(480, 900).apply { leftMargin = left })
                    root.addView(glass, FrameLayout.LayoutParams(350, 140).apply {
                        leftMargin = left + 50; topMargin = 300
                    })
                    return owner to glass
                }
                val a = fixture(0); val b = fixture(520)
                owner = a.first
                first = a.second; second = b.second
                activity.setContentView(root)
                val colors = HostChromeColors(false, Color.MAGENTA)
                val base = HostSurfaceStyle.floating(false, 12f)
                val options = base.copy(sampling = base.sampling.copy(blurRadiusDp = 6f,
                    softwareBlurRadiusDp = 6f, refractionEnabled = false))
                cropped = HostSurfaceScope(first, colors)
                cropped.attach(first, a.first)
                cropped.surface(first, options)
                reference = LumenSurfaceSession(activity, colors.palette())
                reference.bindSource(second, options, HostChromeContentSource(b.first, colors.palette().background))
            }
            try {
                SystemClock.sleep(900)
                instrumentation.runOnMainSync {
                    val small = requireNotNull(cropped.samplingPerformance()).logicalGpuContentPixels
                    val full = reference.performanceDiagnostics().logicalGpuContentPixels
                    assertTrue("Chrome content: $small / $full; ${cropped.diagnostics()}; ${cropped.samplingPerformance()}", small > 0 && small * 2 < full)
                    assertTrue(requireNotNull(cropped.diagnostics()).gpuDraws > 0)
                }
                compare(window, first, second)
                var recordings = 0L
                instrumentation.runOnMainSync { recordings = requireNotNull(cropped.diagnostics()).contentRecordings }
                repeat(12) {
                    instrumentation.runOnMainSync { first.invalidate() }
                    SystemClock.sleep(32)
                }
                instrumentation.runOnMainSync {
                    assertTrue("An idle chrome proxy kept recording",
                        requireNotNull(cropped.diagnostics()).contentRecordings - recordings <= 2)
                }
                instrumentation.runOnMainSync {
                    first.translationY = 260f; second.translationY = 260f
                    cropped.onVisualMovement(); reference.notifyPositionChanged()
                }
                SystemClock.sleep(400)
                compare(window, first, second)
                instrumentation.runOnMainSync {
                    cropped.detach()
                    assertNull(first.background)
                    cropped.attach(first, owner)
                }
                SystemClock.sleep(400)
                compare(window, first, second)
            } finally { instrumentation.runOnMainSync { cropped.close(); reference.close() } }
        }
    }

    @Test fun coordinateProxyCannotHideSelfSamplingFromTheEngineGuard() {
        instrumentation.runOnMainSync {
            val owner = FrameLayout(instrumentation.targetContext)
            val glass = View(owner.context)
            owner.addView(glass)
            owner.layout(0, 0, 480, 900); glass.layout(0, 0, 300, 100)
            val source = HostChromeViewportSource(HostChromeContentSource(owner, Color.BLACK)) {
                it(glass, 0f, 0f, 20f)
            }
            try {
                source.sync()
                assertEquals(LumenSourceAvailability.PROHIBITED, source.availability)
            } finally { source.close() }
        }
    }

    private fun compare(window: Window, first: View, second: View) {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        try {
            assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
            val a = IntArray(2); val b = IntArray(2)
            instrumentation.runOnMainSync { first.getLocationInWindow(a); second.getLocationInWindow(b) }
            var total = 0L; var count = 0; var maximum = 0
            for (y in 18 until 122 step 5) for (x in 18 until 332 step 6) {
                val left = bitmap.getPixel(a[0] + x, a[1] + y)
                val right = bitmap.getPixel(b[0] + x, b[1] + y)
                for (shift in intArrayOf(0, 8, 16)) {
                    val difference = abs((left ushr shift and 255) - (right ushr shift and 255))
                    total += difference; count++; maximum = maxOf(maximum, difference)
                }
            }
            assertTrue("Cropped glass differs: mean=${total.toDouble() / count}, max=$maximum",
                total.toDouble() / count <= 1.0 && maximum <= 4)
        } finally { bitmap.recycle() }
    }
}
