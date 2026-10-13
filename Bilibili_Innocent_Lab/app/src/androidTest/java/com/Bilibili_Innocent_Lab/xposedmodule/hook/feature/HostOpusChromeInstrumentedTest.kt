package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.util.AttributeSet
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostOpusChrome
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostOpusChromeInstrumentedTest {
    private fun host(): Context = InstrumentationRegistry.getInstrumentation().targetContext.createPackageContext(
        "tv.danmaku.bili", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)

    @Test fun installedOpusPainterRetainsTheAuditedColorAndQuoteContracts() {
        val loader = host().classLoader
        val painter = loader.loadClass("gv1.c")
        val opus = loader.loadClass("com.bilibili.bplus.followinglist.opus.t")
        val module = loader.loadClass("com.bilibili.bplus.followinglist.model.j0")
        assertTrue(painter.isAssignableFrom(opus))
        assertEquals(Int::class.javaPrimitiveType, painter.getDeclaredMethod("s",
            ColorStateList::class.java, module, View::class.java).returnType)
        assertEquals(Boolean::class.javaPrimitiveType, module.getMethod("f0").returnType)
        assertNotNull(loader.loadClass("com.bilibili.bplus.followinglist.page.opus.OpusDetailFragment")
            .getDeclaredMethod("onViewCreated", View::class.java, android.os.Bundle::class.java))
    }

    @Test fun nativeFollowAnimationCannotPaintOrReceiveTouchesInsideTheTransparentBottomBar() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val host = host()
            val layout = host.resources.getIdentifier("dy_fragment_opus_detail", "layout", host.packageName)
            val inflater = LayoutInflater.from(host).cloneInContext(host)
            // 图标字体由宿主 Application 初始化，模块测试进程没有该 Drawable inflater。
            // 只替换不参与本用例的图标，仍使用原 XML、ConstraintLayout 与关注提示布局。
            inflater.factory2 = object : LayoutInflater.Factory2 {
                override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
                    onCreateView(name, context, attrs)
                override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = when (name) {
                    "com.bilibili.magicasakura.widgets.TintImageView" -> ImageView(context, attrs)
                    "com.airbnb.lottie.LottieAnimationView" -> View(context, attrs)
                    else -> null
                }
            }
            val root = inflater.inflate(layout, null) as ViewGroup
            fun find(name: String) = requireNotNull(root.findViewById<View>(
                host.resources.getIdentifier(name, "id", host.packageName)))
            val guide = find("opus_follow_guide") as ViewGroup
            val bottom = find("opus_bottom_layout") as ViewGroup
            val originalIndex = root.indexOfChild(guide)
            val originalParams = guide.layoutParams
            val originalCount = root.childCount
            for (i in 0 until root.childCount) root.getChildAt(i).visibility = View.GONE
            for (i in 0 until guide.childCount) guide.getChildAt(i).visibility = View.GONE
            for (i in 0 until bottom.childCount) bottom.getChildAt(i).visibility = View.GONE
            guide.visibility = View.VISIBLE
            bottom.visibility = View.VISIBLE
            bottom.layoutParams = bottom.layoutParams.apply { height = 100 }
            root.setBackgroundColor(Color.BLUE)
            var clicks = 0
            guide.setOnClickListener { clicks++ }
            val chrome = requireNotNull(HostOpusChrome.create(root, HashMap()))
            try {
                root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 1080, 1000)
                // 显式让底栏完全透明，证明消除叠层依靠视口，而非加不透明底色遮盖。
                bottom.setBackgroundColor(Color.TRANSPARENT)
                guide.setBackgroundColor(Color.RED)
                for (fraction in listOf(0f, .5f, 1f, .5f, 0f)) {
                    guide.translationY = guide.height * fraction
                    val bitmap = Bitmap.createBitmap(1080, 1000, Bitmap.Config.ARGB_8888)
                    try {
                        root.draw(Canvas(bitmap))
                        assertEquals(Color.BLUE, bitmap.getPixel(540, bottom.top + 50))
                        val visible = bitmap.getPixel(540, bottom.top - 2)
                        assertEquals(if (fraction == 1f) Color.BLUE else Color.RED, visible)
                    } finally { bitmap.recycle() }
                    fun touch(action: Int) {
                        val event = MotionEvent.obtain(0, 0, action, 540f, bottom.top + 50f, 0)
                        try { root.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                    touch(MotionEvent.ACTION_DOWN); touch(MotionEvent.ACTION_UP)
                    assertEquals(0, clicks)
                }
            } finally { chrome.close() }
            assertSame(root, guide.parent)
            assertSame(originalParams, guide.layoutParams)
            assertEquals(originalIndex, root.indexOfChild(guide))
            assertEquals(originalCount, root.childCount)
        }
    }
}
