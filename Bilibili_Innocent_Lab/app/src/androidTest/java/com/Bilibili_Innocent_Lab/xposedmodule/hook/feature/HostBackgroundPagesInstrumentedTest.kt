package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.shape.MaterialShapeDrawable
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.*
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.Bilibili_Innocent_Lab.xposedmodule.test.R as TestR
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostBackgroundPagesInstrumentedTest {
    @Test fun emptyDynamicPageKeepsCardLayerAndRestoresWallpaperAfterReattach() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val testContext = InstrumentationRegistry.getInstrumentation().context
                val root = FrameLayout(activity)
                val container = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
                val panel = View(activity).apply { background = GradientDrawable().apply { setColor(Color.DKGRAY); cornerRadius = 20f } }
                val panelSurface = panel.background
                val list = RecyclerView(testContext).apply { id = TestR.id.dy_list; layoutManager = LinearLayoutManager(testContext); setBackgroundColor(Color.WHITE) }
                val otherList = RecyclerView(testContext).apply { id = TestR.id.tv_right; layoutManager = LinearLayoutManager(testContext); visibility = View.GONE; setBackgroundColor(Color.WHITE) }
                val section = View(activity).apply { background = GradientDrawable().apply { setColor(Color.DKGRAY) }; setPadding(3, 5, 7, 9) }
                val image = ImageView(activity).apply { setBackgroundColor(Color.BLACK) }
                val imageSurface = image.background
                val action = View(activity).apply { background = StateListDrawable().apply { addState(intArrayOf(), GradientDrawable().apply { setColor(Color.RED) }) } }
                val actionSurface = action.background
                root.addView(container, FrameLayout.LayoutParams(-1, -1))
                container.addView(panel, FrameLayout.LayoutParams(-1, 120))
                container.addView(list, FrameLayout.LayoutParams(-1, -1))
                container.addView(otherList, FrameLayout.LayoutParams(-1, -1))
                root.addView(section, FrameLayout.LayoutParams(-1, 40))
                root.addView(image, FrameLayout.LayoutParams(80, 80))
                root.addView(action, FrameLayout.LayoutParams(80, 40))
                val decor = activity.window.decorView as ViewGroup
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.STARRY), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                pages.attach(container, HostBackgroundPagePolicy.Kind.PAGE)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                root.viewTreeObserver.dispatchOnPreDraw()
                assertTrue(container.background is HostBackgroundDrawable)
                pages.attach(root, HostBackgroundPagePolicy.Kind.DYNAMIC)
                pages.attach(container, HostBackgroundPagePolicy.Kind.PAGE)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                root.viewTreeObserver.dispatchOnPreDraw()
                val wallpaper = root.background as HostBackgroundDrawable
                assertSame("Nested pages must share the outer wallpaper", root, HostBackgroundPages.owner(list))
                assertNull(container.background)
                assertNull(list.background)
                assertSame(panelSurface, panel.background)
                assertSame(imageSurface, image.background)
                assertSame(actionSurface, action.background)
                assertEquals(Color.TRANSPARENT, (section.background as GradientDrawable).color?.defaultColor)
                assertEquals(3, section.paddingLeft)
                assertEquals(5, section.paddingTop)
                assertEquals(7, section.paddingRight)
                assertEquals(9, section.paddingBottom)
                controller.apply(list, true)
                assertSame(wallpaper, root.background)
                assertTrue(wallpaper.night)
                assertNull(list.background)
                assertTrue(pages.scroll(list, 120))
                assertEquals(120L, wallpaper.scrollOffset)
                list.visibility = View.GONE
                otherList.visibility = View.VISIBLE
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                root.viewTreeObserver.dispatchOnPreDraw()
                assertEquals("Each dynamic tab must retain its own wallpaper position", 0L, wallpaper.scrollOffset)
                assertNull("Full-width vertical lists must not depend on a particular resource name", otherList.background)
                list.visibility = View.VISIBLE
                otherList.visibility = View.GONE
                root.viewTreeObserver.dispatchOnGlobalLayout()
                root.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(120L, wallpaper.scrollOffset)
                decor.removeView(root)
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                container.setBackgroundColor(Color.WHITE)
                root.viewTreeObserver.dispatchOnGlobalLayout()
                root.viewTreeObserver.dispatchOnPreDraw()
                assertSame(wallpaper, root.background)
                assertNull(container.background)
                decor.removeView(root)
                verifyNativeAndCommentCoverage(activity, decor)
            }
        }
    }

    private fun verifyNativeAndCommentCoverage(activity: MainActivity, decor: ViewGroup) {
        val base = InstrumentationRegistry.getInstrumentation().context
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or Configuration.UI_MODE_NIGHT_YES
        })
        val window = FrameLayout(context)
        val dynamic = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        val comment = FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setBackgroundColor(Color.WHITE)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 3
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val row = FrameLayout(context).apply {
                        setBackgroundColor(Color.WHITE)
                        layoutParams = RecyclerView.LayoutParams(-1, 150)
                        addView(FrameLayout(context).apply { setBackgroundColor(Color.WHITE) }, FrameLayout.LayoutParams(-1, -1))
                    }
                    return object : RecyclerView.ViewHolder(row) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
        }
        comment.addView(list, FrameLayout.LayoutParams(-1, -1))
        dynamic.addView(comment, FrameLayout.LayoutParams(-1, -1))
        window.addView(dynamic, FrameLayout.LayoutParams(-1, -1))
        val player = FrameLayout(context).apply {
            id = TestR.id.video_area
            setBackgroundColor(Color.BLACK)
            addView(FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }, FrameLayout.LayoutParams(-1, -1))
        }
        val playerSurface = player.background
        val playerChildSurface = player.getChildAt(0).background
        window.addView(player, FrameLayout.LayoutParams(-1, 80))
        val chrome = View(context).apply { background = MaterialShapeDrawable().apply {
            fillColor = ColorStateList.valueOf(Color.DKGRAY)
            setStroke(2f, Color.BLUE)
        } }
        window.addView(chrome, FrameLayout.LayoutParams(-1, 40))
        val gradient = View(context).apply {
            id = TestR.id.main_top_layout
            isClickable = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.DKGRAY, Color.TRANSPARENT))
        }
        window.addView(gradient, FrameLayout.LayoutParams(-1, 40))
        val input = View(context).apply {
            id = TestR.id.fake_input_layout
            background = LayerDrawable(arrayOf(ColorDrawable(Color.DKGRAY), ColorDrawable(Color.RED), ColorDrawable(Color.GRAY), ColorDrawable(Color.WHITE))).apply {
                setLayerWidth(1, 20)
                setLayerHeight(2, 1)
                setLayerWidth(3, 20)
            }
        }
        window.addView(input, FrameLayout.LayoutParams(-1, 40))
        val inputWithLine = View(context).apply {
            id = TestR.id.fake_input_layout
            background = LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(Color.GRAY) },
                GradientDrawable().apply { setColor(Color.DKGRAY) }
            )).apply { setLayerInset(1, 0, 1, 0, 0) }
        }
        window.addView(inputWithLine, FrameLayout.LayoutParams(-1, 40))
        val label = TextView(context).apply { text = "19"; setTextColor(Color.BLACK) }
        window.addView(label, FrameLayout.LayoutParams(80, 40))
        val bright = FrameLayout(context).apply { background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8f } }
        val brightLabel = TextView(context).apply { text = "button"; setTextColor(Color.BLACK) }
        bright.addView(brightLabel, FrameLayout.LayoutParams(-1, -1))
        window.addView(bright, FrameLayout.LayoutParams(80, 40))
        decor.addView(window, ViewGroup.LayoutParams(320, 640))
        window.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
        window.layout(0, 0, 320, 640)
        val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.STARRY), { throw it })
        val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
        pages.attach(dynamic, HostBackgroundPagePolicy.Kind.DYNAMIC)
        pages.attach(comment, HostBackgroundPagePolicy.Kind.COMMENT)
        // Activity 回调晚于子页面时不能吞掉专用渲染，评论层也不能被动态页面忽略。
        pages.attach(window, HostBackgroundPagePolicy.Kind.NATIVE)
        window.viewTreeObserver.dispatchOnGlobalLayout()
        window.viewTreeObserver.dispatchOnPreDraw()
        assertSame(window, HostBackgroundPages.owner(player))
        assertSame(dynamic, HostBackgroundPages.owner(dynamic))
        assertSame(comment, HostBackgroundPages.owner(list))
        assertTrue(window.background is HostBackgroundDrawable)
        assertTrue(dynamic.background is HostBackgroundDrawable)
        assertTrue(comment.background is HostBackgroundDrawable)
        assertNull(list.background)
        assertTrue(list.childCount > 0)
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as ViewGroup
            assertNull(row.background)
            assertNull(row.getChildAt(0).background)
        }
        assertSame(playerSurface, player.background)
        assertSame(playerChildSurface, player.getChildAt(0).background)
        assertEquals(Color.TRANSPARENT, (chrome.background as MaterialShapeDrawable).fillColor?.defaultColor)
        assertEquals(2f, (chrome.background as MaterialShapeDrawable).strokeWidth, .01f)
        assertEquals(Color.TRANSPARENT, (gradient.background as GradientDrawable).color?.defaultColor)
        val inputLayers = input.background as LayerDrawable
        assertEquals(Color.TRANSPARENT, (inputLayers.getDrawable(0) as ColorDrawable).color)
        assertEquals("Input business colors must be retained", Color.RED, (inputLayers.getDrawable(1) as ColorDrawable).color)
        assertEquals("Input separators must be retained", Color.GRAY, (inputLayers.getDrawable(2) as ColorDrawable).color)
        assertEquals("Small neutral controls must be retained", Color.WHITE, (inputLayers.getDrawable(3) as ColorDrawable).color)
        val borderedInput = inputWithLine.background as LayerDrawable
        assertEquals(1, borderedInput.getLayerHeight(0))
        assertEquals(Color.GRAY, (borderedInput.getDrawable(0) as GradientDrawable).color?.defaultColor)
        assertEquals(Color.TRANSPARENT, (borderedInput.getDrawable(1) as GradientDrawable).color?.defaultColor)
        assertEquals(0xffe3e5e7.toInt(), label.currentTextColor)
        assertEquals(Color.BLACK, brightLabel.currentTextColor)
        assertTrue(HostBackgroundPages.protectedAncestor(player.getChildAt(0)))
        controller.apply(list, true)
        assertNull(list.background)
        assertTrue((comment.background as HostBackgroundDrawable).night)
        decor.removeView(window)
    }
}
