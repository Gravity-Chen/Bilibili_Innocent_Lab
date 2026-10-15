package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.PixelCopy
import android.view.ViewTreeObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedStatusContentSource
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSampling
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.model.LumenPalette
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowFeedStatusContentInstrumentedTest {
    @Test fun idleProxyDoesNotKeepRecordingAndContentInvalidationsStillReachTheGlass() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var content: FrameLayout
            lateinit var peer: View
            lateinit var band: View
            lateinit var source: FollowFeedStatusContentSource
            lateinit var session: LumenSurfaceSession
            lateinit var observer: ViewTreeObserver.OnPreDrawListener
            lateinit var window: Window
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                root = FrameLayout(activity)
                content = FrameLayout(activity).apply { setBackgroundColor(Color.BLUE) }
                content.addView(View(activity).apply { setBackgroundColor(Color.RED) },
                    FrameLayout.LayoutParams(-1, 200).apply { topMargin = 600 })
                peer = View(activity)
                band = View(activity)
                root.addView(content, FrameLayout.LayoutParams(500, 900))
                root.addView(peer, FrameLayout.LayoutParams(20, 20).apply { leftMargin = 600 })
                root.addView(band, FrameLayout.LayoutParams(400, 80).apply { topMargin = 200 })
                activity.setContentView(root)
                source = FollowFeedStatusContentSource(content, band, 80f)
                observer = ViewTreeObserver.OnPreDrawListener { source.sync(); true }
                root.viewTreeObserver.addOnPreDrawListener(observer)
                session = LumenSurfaceSession(activity, LumenPalette.neutral(false))
                session.bindSource(band, LumenSurfaceOptions(material = LumenSurfaceMaterial.FROSTED,
                    radiusDp = 0f, tintEnabled = false, edgeEnabled = false,
                    sampling = LumenSurfaceSampling(refractionEnabled = false)), source)
            }
            fun pixel(): Int {
                val image = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
                val ready = CountDownLatch(1)
                var result = -1
                PixelCopy.request(window, image, { result = it; ready.countDown() }, Handler(Looper.getMainLooper()))
                assertTrue(ready.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
                val location = IntArray(2)
                instrumentation.runOnMainSync { band.getLocationInWindow(location) }
                return try { image.getPixel(location[0] + band.width / 2, location[1] + band.height / 2) }
                finally { image.recycle() }
            }
            try {
                SystemClock.sleep(600)
                val before = pixel()
                assertTrue(Color.blue(before) > Color.red(before))
                var recordings = 0L
                instrumentation.runOnMainSync { recordings = session.diagnostics().contentRecordings }
                repeat(20) {
                    instrumentation.runOnMainSync { peer.invalidate() }
                    SystemClock.sleep(32)
                }
                instrumentation.runOnMainSync {
                    assertTrue("A hidden coordinate proxy kept recording a static list",
                        session.diagnostics().contentRecordings - recordings <= 2)
                    // 原 ColorDrawable 就地变色，不依赖新布局或替换源身份。
                    content.setBackgroundColor(Color.GREEN)
                }
                SystemClock.sleep(250)
                val after = pixel()
                assertTrue("Dirty list content did not reach the cropped source: $before -> $after",
                    Color.green(after) > Color.blue(after))
                instrumentation.runOnMainSync { band.translationY = 450f }
                SystemClock.sleep(250)
                val moved = pixel()
                assertTrue("A property-only move kept the old crop: $after -> $moved", Color.red(moved) > Color.green(moved))
            } finally {
                instrumentation.runOnMainSync {
                    root.viewTreeObserver.removeOnPreDrawListener(observer)
                    session.close(); source.close()
                }
            }
        }
    }

    @Test fun boundedRecordingKeepsScrolledTransformedPixelsAndSkipsDistantRows() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val root = FrameLayout(activity)
                val decor = activity.window.decorView as ViewGroup
                decor.addView(root, ViewGroup.LayoutParams(700, 900))
                val source = FrameLayout(activity).apply {
                    setBackgroundColor(Color.DKGRAY)
                    pivotX = 0f; pivotY = 0f
                    scaleX = .9f; scaleY = .8f; translationX = 15f; translationY = 20f
                }
                var distantDraws = 0
                val distant = object : View(activity) {
                    override fun onDraw(canvas: Canvas) { distantDraws++; canvas.drawColor(Color.RED) }
                }
                source.addView(View(activity).apply { setBackgroundColor(Color.BLUE) },
                    FrameLayout.LayoutParams(-1, 100).apply { topMargin = 180 })
                source.addView(distant, FrameLayout.LayoutParams(-1, 100).apply { topMargin = 700 })
                root.addView(source, FrameLayout.LayoutParams(550, 850))
                val band = View(activity)
                root.addView(band, FrameLayout.LayoutParams(500, 50).apply { leftMargin = 20; topMargin = 180 })
                val expected = Bitmap.createBitmap(550, 850, Bitmap.Config.ARGB_8888)
                val actual = Bitmap.createBitmap(550, 850, Bitmap.Config.ARGB_8888)
                try {
                    root.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 700, 900)
                    source.scrollTo(0, 31)
                    val full = Canvas(expected)
                    full.translate(0f, -31f)
                    source.draw(full)
                    assertTrue("The full recording includes the distant row", distantDraws > 0)
                    distantDraws = 0
                    FollowFeedStatusContentSource(source, band, 20f).use { cropped ->
                        assertTrue("The engine must allocate only the blur neighborhood", cropped.coordinateView.height < source.height / 2)
                        assertEquals(0f, cropped.coordinateView.alpha, 0f)
                        assertFalse(cropped.coordinateView.isClickable)
                        val matrix = Matrix()
                        assertTrue(ViewSamplingMatrix().sourceToTarget(cropped.coordinateView, source, matrix))
                        Canvas(actual).apply { concat(matrix); cropped.drawContent(this) }
                    }
                    assertEquals("The status recording must skip rows outside its blur neighborhood", 0, distantDraws)
                    // band 的 y=180..230，经 source 的 .8 缩放及 20px 平移映射为 y=200..262.5。
                    for (y in 200..260 step 5) for (x in 50..500 step 10)
                        assertEquals("Source coordinates and own scroll must remain unchanged at $x,$y",
                            expected.getPixel(x, y), actual.getPixel(x, y))
                    assertEquals(Color.TRANSPARENT, actual.getPixel(300, 600))
                } finally {
                    expected.recycle(); actual.recycle(); decor.removeView(root)
                }
            }
        }
    }
}
