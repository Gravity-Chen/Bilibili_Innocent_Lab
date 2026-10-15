package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostPageChrome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostReadingSurfaces
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostWallpaperText
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.Bilibili_Innocent_Lab.xposedmodule.test.R as TestR
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.WeakHashMap

@RunWith(AndroidJUnit4::class)
class HostReadingSurfacesInstrumentedTest {
    private fun fixture(action: (FrameLayout) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
                    override fun getApplicationContext() = activity.applicationContext
                }
                val root = FrameLayout(context)
                val decor = activity.window.decorView as ViewGroup
                decor.addView(root, ViewGroup.LayoutParams(700, 900))
                try { action(root) } finally { decor.removeView(root) }
            }
        }
    }

    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 700, 900)
    }

    @Test fun mineGroupsKeepHostLayoutAndReleaseSurfacesWhenReused() = fixture { root ->
        val context = root.context
        val mine = RecyclerView(context).apply {
            id = TestR.id.mine_recycle
            layoutManager = LinearLayoutManager(context)
        }
        var clicks = 0
        val icon = View(context).apply { setBackgroundColor(Color.MAGENTA) }
        val section = FrameLayout(context).apply {
            setBackgroundColor(Color.DKGRAY)
            setPadding(3, 5, 7, 9)
            addView(FrameLayout(context).apply { id = TestR.id.item_recycle }, FrameLayout.LayoutParams(-1, 100))
            addView(icon, FrameLayout.LayoutParams(40, 40))
            setOnClickListener { clicks++ }
        }
        val original = section.background
        val iconBackground = icon.background
        mine.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                section.layoutParams = RecyclerView.LayoutParams(-1, 160).apply { setMargins(3, 5, 7, 9) }
                return object : RecyclerView.ViewHolder(section) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        }
        root.addView(mine, FrameLayout.LayoutParams(-1, -1))
        layout(root)
        val owned = WeakHashMap<View, Drawable>()
        val surfaces = HostReadingSurfaces(root, owned)
        val params = section.layoutParams as RecyclerView.LayoutParams
        try {
            surfaces.discover(); surfaces.apply(true)
            val left = params.leftMargin
            assertEquals(3, left)
            val top = params.topMargin
            assertTrue("Groups need vertical separation", top > 5)
            assertSame(params, section.layoutParams)
            assertSame(section.background, owned[section])
            assertNotSame(original, section.background)
            assertSame(iconBackground, icon.background)
            section.performClick()
            assertEquals(1, clicks)
            surfaces.discover(); surfaces.apply(true)
            assertEquals("Repeated layouts must not accumulate spacing", left, params.leftMargin)
            assertEquals(top, params.topMargin)
            surfaces.apply(false)
            assertEquals(left, params.leftMargin)
            // 模拟复用成非功能分组，旧材质必须释放，宿主布局保持不变。
            section.removeViewAt(0)
            surfaces.discover()
            assertSame(original, section.background)
            assertEquals(3, params.leftMargin)
            assertEquals(5, params.topMargin)
            assertEquals(7, params.rightMargin)
            assertEquals(9, params.bottomMargin)
            assertEquals(3, section.paddingLeft)
            assertTrue(owned.isEmpty())
        } finally { surfaces.close() }
    }

    @Test fun readingPlanesAndSearchGroupsDoNotStyleUnrelatedRowsOrMedia() = fixture { root ->
        val context = root.context
        val preferences = FrameLayout(context).apply { id = android.R.id.list_container }
        val settings = FrameLayout(context)
        preferences.addView(settings, FrameLayout.LayoutParams(-1, 200))
        root.addView(preferences, FrameLayout.LayoutParams(-1, 200))
        val search = FrameLayout(context).apply { id = TestR.id.search_discover_list }
        val history = FrameLayout(context).apply {
            addView(TextView(context).apply { id = TestR.id.title }, FrameLayout.LayoutParams(100, 40))
        }
        val unrelated = FrameLayout(context).apply { setBackgroundColor(Color.BLUE) }
        val original = unrelated.background
        search.addView(history, FrameLayout.LayoutParams(-1, 200))
        search.addView(unrelated, FrameLayout.LayoutParams(100, 100))
        root.addView(search, FrameLayout.LayoutParams(-1, 200).apply { topMargin = 200 })
        val messages = FrameLayout(context).apply { id = TestR.id.rv_messages }
        val entry = FrameLayout(context).apply {
            addView(View(context).apply { id = TestR.id.im_top_hint }, FrameLayout.LayoutParams(20, 20))
        }
        messages.addView(entry, FrameLayout.LayoutParams(-1, 100))
        root.addView(messages, FrameLayout.LayoutParams(-1, 200).apply { topMargin = 400 })
        val media = FrameLayout(context).apply {
            id = TestR.id.video_area
            addView(FrameLayout(context).apply { id = TestR.id.rv_messages }, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(media, FrameLayout.LayoutParams(-1, 100).apply { topMargin = 600 })
        layout(root)
        val owned = WeakHashMap<View, Drawable>()
        HostReadingSurfaces(root, owned).use { surfaces ->
            surfaces.discover(); surfaces.apply(true)
            for (view in listOf(settings, history, messages, entry)) assertNotNull(owned[view])
            assertSame(original, unrelated.background)
            assertNull(media.getChildAt(0).background)
        }
        assertNull(history.background)
        assertNull(messages.background)
        assertTrue(owned.isEmpty())
    }

    @Test fun searchInputReceivesChromeWithoutReplacingItsControls() = fixture { root ->
        val search = FrameLayout(root.context).apply { id = TestR.id.search_input_layout }
        val input = TextView(root.context).apply { setBackgroundColor(Color.WHITE); isFocusable = true }
        val original = input.background
        search.addView(input, FrameLayout.LayoutParams(300, 60))
        root.addView(search, FrameLayout.LayoutParams(-1, 100))
        layout(root)
        HostPageChrome(root, WeakHashMap()).use { chrome ->
            chrome.discover(); chrome.apply(true)
            assertNotNull(search.background)
            assertSame(original, input.background)
            assertTrue(input.isFocusable)
        }
        assertNull(search.background)
    }

    @Test fun contrastKeepsTextHierarchyAndRestoresHostColors() = fixture { root ->
        val contrast = HostWallpaperText()
        fun label(color: Int) = TextView(root.context).apply {
            setTextColor(color); root.addView(this, FrameLayout.LayoutParams(100, 40))
        }
        val primary = label(Color.BLACK)
        val secondary = label(0xff9499a0.toInt())
        val business = label(Color.MAGENTA)
        val disabled = label(Color.GRAY).apply { isEnabled = false }
        val stateful = label(Color.BLACK).apply {
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(Color.RED, Color.GRAY)))
        }
        for (view in listOf(primary, secondary, business, disabled, stateful)) contrast.apply(view, root)
        assertEquals(0xffe3e5e7.toInt(), primary.currentTextColor)
        assertEquals(0xffb8bcc6.toInt(), secondary.currentTextColor)
        assertEquals(Color.MAGENTA, business.currentTextColor)
        assertEquals(Color.GRAY, disabled.currentTextColor)
        assertTrue(stateful.textColors.isStateful)
        secondary.setBackgroundColor(Color.WHITE)
        contrast.apply(secondary, root)
        assertEquals("Reused text on a light control must recover its original color", 0xff9499a0.toInt(), secondary.currentTextColor)
        primary.setTextColor(Color.GREEN)
        contrast.restore()
        assertEquals("Host updates take precedence over restoration", Color.GREEN, primary.currentTextColor)
    }
}
