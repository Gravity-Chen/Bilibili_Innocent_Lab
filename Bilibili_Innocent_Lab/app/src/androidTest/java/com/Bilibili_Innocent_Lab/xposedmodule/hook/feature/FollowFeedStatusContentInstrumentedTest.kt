package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedStatusContentSource
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowFeedStatusContentInstrumentedTest {
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
                    FollowFeedStatusContentSource(source, band, 20f).drawContent(Canvas(actual))
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
