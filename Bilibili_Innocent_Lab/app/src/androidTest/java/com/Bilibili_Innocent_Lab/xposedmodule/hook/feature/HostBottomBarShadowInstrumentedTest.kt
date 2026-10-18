package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostBottomBarShadowInstrumentedTest {
    @Test fun outsideShadowSurvivesClippingAndTracksMovementThemeAndDetach() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val parent = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
            // Detached rendering avoids changing the user's Activity or theme.
            val host = object : View(context) {
                override fun isShown() = visibility == VISIBLE
            }.apply {
                setBackgroundColor(Color.WHITE)
                clipToOutline = true
            }
            parent.addView(host, FrameLayout.LayoutParams(dp(180), dp(48)).apply {
                leftMargin = dp(70); topMargin = dp(70)
            })
            parent.measure(View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(240), View.MeasureSpec.EXACTLY))
            parent.layout(0, 0, dp(320), dp(240))
            val shadow = HostBottomBarShadow(host, 24f)
            val bitmap = Bitmap.createBitmap(parent.width, parent.height, Bitmap.Config.ARGB_8888)
            fun pixel(x: Int, y: Int): Int {
                bitmap.eraseColor(Color.WHITE)
                parent.draw(Canvas(bitmap))
                return bitmap.getPixel(dp(x), dp(y))
            }
            try {
                shadow.sync(false)
                assertTrue("Light shadow must escape the clipped capsule", Color.red(pixel(160, 68)) < 252)
                assertEquals("Glass interior must stay clean", Color.WHITE, pixel(160, 90))
                host.translationY = dp(48).toFloat()
                shadow.sync(false)
                assertEquals("Old location must not retain shadow", Color.WHITE, pixel(160, 68))
                assertTrue("Shadow must move with the bar", Color.red(pixel(160, 116)) < 252)
                shadow.sync(true)
                assertEquals("Dark mode uses its existing light rim", Color.WHITE, pixel(160, 116))
                shadow.sync(false)
                host.visibility = View.INVISIBLE
                shadow.sync(false)
                assertEquals("Hidden bar must not leave a shadow", Color.WHITE, pixel(160, 116))
                host.visibility = View.VISIBLE
                shadow.sync(false)
                shadow.detach()
                assertEquals("Detached overlay must be removed", Color.WHITE, pixel(160, 116))
            } finally {
                shadow.detach()
                bitmap.recycle()
            }
        }
    }
}
