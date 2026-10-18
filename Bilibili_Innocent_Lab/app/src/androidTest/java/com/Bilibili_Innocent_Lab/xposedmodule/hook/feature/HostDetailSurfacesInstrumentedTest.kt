package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
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
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPagePolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPages
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostDetailSurfaces
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.test.R as TestR
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.WeakHashMap

@RunWith(AndroidJUnit4::class)
class HostDetailSurfacesInstrumentedTest {
    private class Panel(activity: MainActivity, wrapped: Boolean = true) {
        private val testResources = InstrumentationRegistry.getInstrumentation().context.resources
        // 测试 id 来自测试 APK，生命周期和 Application 仍使用真实 Activity。
        private val context = object : ContextWrapper(activity) {
            override fun getResources(): Resources = testResources
        }
        val round = FrameLayout(context).apply { if (wrapped) id = TestR.id.round_container }
        val root = FrameLayout(context).apply { id = TestR.id.content_container }
        val header = View(context).apply { id = TestR.id.tabs_back_board; setBackgroundColor(Color.WHITE) }
        val divider = View(context).apply { id = TestR.id.shadow_dig; setBackgroundColor(Color.GRAY) }
        val body = FrameLayout(context).apply { id = TestR.id.pager; setBackgroundColor(Color.WHITE) }
        val introduction = FrameLayout(context)
        val input = View(context).apply { id = TestR.id.fake_input_layout; setBackgroundColor(Color.WHITE) }
        val title = TextView(activity).apply { text = "简介 / 评论"; setTextColor(Color.RED) }
        val playlist = FrameLayout(context).apply { id = TestR.id.playlist_container }
        val playlistBody = FrameLayout(context).apply { id = TestR.id.layout_playlist }
        init {
            round.addView(root, FrameLayout.LayoutParams(-1, -1))
            root.addView(header, FrameLayout.LayoutParams(-1, 80))
            root.addView(body, FrameLayout.LayoutParams(-1, 560).apply { topMargin = 80 })
            root.addView(divider, FrameLayout.LayoutParams(-1, 1).apply { topMargin = 79 })
            root.addView(title, FrameLayout.LayoutParams(-1, 80))
            body.addView(introduction, FrameLayout.LayoutParams(-1, -1))
            introduction.addView(input, FrameLayout.LayoutParams(-1, 40).apply { topMargin = 520 })
            playlist.addView(playlistBody, FrameLayout.LayoutParams(-1, -1))
            root.addView(playlist, FrameLayout.LayoutParams(-1, 400).apply { topMargin = 240 })
            header.setPadding(3, 5, 7, 11)
        }
        fun layout() {
            round.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            round.layout(0, 0, 320, 640)
        }
    }

    @Test fun backgroundPassesKeepSurfaceBoundariesAndLeaveThePlaybackWindowAlone() {
        checkPlaybackPanel(wrapped = true)
    }

    @Test fun unwrappedPlaylistPanelSharesWallpaperAndKeepsAnIndependentReadingSurface() {
        checkPlaybackPanel(wrapped = false)
    }

    private fun checkPlaybackPanel(wrapped: Boolean) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val decor = activity.window.decorView as ViewGroup
                val window = FrameLayout(activity)
                val originalWindow = ColorDrawable(Color.BLUE)
                val media = View(InstrumentationRegistry.getInstrumentation().context).apply {
                    id = TestR.id.video_area
                    setBackgroundColor(Color.RED)
                }
                val originalMedia = media.background
                val panel = Panel(activity, wrapped)
                val originalChildCount = panel.root.childCount
                window.background = originalWindow
                window.addView(media, FrameLayout.LayoutParams(-1, -1))
                window.addView(panel.round, FrameLayout.LayoutParams(-1, -1))
                decor.addView(window, ViewGroup.LayoutParams(320, 640))
                window.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                window.layout(0, 0, 320, 640)
                panel.layout()
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.NEBULA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                try {
                    pages.attach(window, HostBackgroundPagePolicy.Kind.NATIVE)
                    repeat(3) { window.viewTreeObserver.dispatchOnGlobalLayout(); window.viewTreeObserver.dispatchOnPreDraw() }
                    assertSame(originalWindow, window.background)
                    assertSame(originalMedia, media.background)
                    assertTrue(panel.root.background is HostBackgroundDrawable)
                    val header = panel.header.background
                    val body = panel.body.background
                    val divider = panel.divider.background
                    val input = panel.input.background
                    val playlist = panel.playlist.background
                    assertNotNull(header)
                    assertNotNull(body)
                    assertNotNull(divider)
                    assertNotNull(input)
                    assertNotNull(playlist)
                    assertNotSame(panel.root.background, playlist)
                    repeat(3) { window.viewTreeObserver.dispatchOnPreDraw() }
                    assertSame(header, panel.header.background)
                    assertSame(body, panel.body.background)
                    assertSame(divider, panel.divider.background)
                    assertSame(input, panel.input.background)
                    assertSame(playlist, panel.playlist.background)
                    assertSame(panel.header, header.callback)
                    assertEquals(Color.RED, panel.title.currentTextColor)
                    assertEquals(originalChildCount, panel.root.childCount)
                    assertEquals(listOf(3, 5, 7, 11), listOf(panel.header.paddingLeft, panel.header.paddingTop,
                        panel.header.paddingRight, panel.header.paddingBottom))
                    pages.attach(panel.introduction, HostBackgroundPagePolicy.Kind.PAGE)
                    assertSame(panel.root, HostBackgroundPages.sharedWallpaperOwner(panel.introduction))
                } finally { decor.removeView(window) }
            }
        }
    }

    @Test fun chapterOverlayInsertedLaterCoversHeaderAndBodyAndRestoresAfterClosing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val decor = activity.window.decorView as ViewGroup
                val panel = Panel(activity)
                decor.addView(panel.round, ViewGroup.LayoutParams(320, 640))
                panel.root.background = HostBackgroundDrawable(HostBackgroundConfig(HostBackgroundPreset.AURORA))
                val slot = FrameLayout(panel.root.context).apply { id = TestR.id.fl_tab_pager_container }
                panel.root.addView(slot, FrameLayout.LayoutParams(-1, -1))
                val chapter = FrameLayout(panel.root.context).apply { setBackgroundColor(Color.WHITE) }
                val original = chapter.background
                val title = TextView(panel.root.context).apply { id = TestR.id.watchpoint_title; text = "章节"; setTextColor(Color.BLACK) }
                val close = View(panel.root.context).apply { id = TestR.id.watchpoint_close }
                val list = FrameLayout(panel.root.context).apply { id = TestR.id.watchpoint_list }
                val row = TextView(activity).apply { text = "Intro" }
                list.addView(row)
                chapter.addView(title, FrameLayout.LayoutParams(-1, 44))
                chapter.addView(close, FrameLayout.LayoutParams(44, 44))
                chapter.addView(list, FrameLayout.LayoutParams(-1, -1).apply { topMargin = 44 })
                val owned = WeakHashMap<View, Drawable>()
                val surfaces = HostDetailSurfaces(panel.root, owned)
                try {
                    surfaces.discover(); surfaces.apply(false)
                    val header = panel.header.background
                    slot.addView(chapter, FrameLayout.LayoutParams(-1, -1))
                    panel.layout()
                    surfaces.discover(); surfaces.apply(false)
                    val backdrop = chapter.background
                    assertNotSame(original, backdrop)
                    assertSame(backdrop, owned[chapter])
                    assertSame(header, panel.header.background)
                    assertEquals(panel.root.height, chapter.height)
                    assertNull(title.background)
                    assertNull(list.background)
                    val snapshot = Bitmap.createBitmap(chapter.width, chapter.height, Bitmap.Config.ARGB_8888)
                    try {
                        requireNotNull(backdrop).setBounds(0, 0, chapter.width, chapter.height)
                        backdrop.draw(Canvas(snapshot))
                        assertEquals("The bottom edge must not reveal the underlying page", 255,
                            Color.alpha(snapshot.getPixel(chapter.width / 2, chapter.height - 1)))
                    } finally { snapshot.recycle() }
                    var selected = false
                    row.setOnClickListener { selected = true }
                    row.performClick()
                    assertTrue(selected)
                    surfaces.apply(true)
                    assertSame(backdrop, chapter.background)
                    assertEquals(0xffe3e5e7.toInt(), title.currentTextColor)
                    close.setOnClickListener { slot.removeView(chapter) }
                    close.performClick()
                    surfaces.discover(); surfaces.apply(true)
                    assertSame(original, chapter.background)
                    assertEquals(Color.BLACK, title.currentTextColor)
                    assertFalse(owned.containsKey(chapter))
                } finally { surfaces.detach(); decor.removeView(panel.round) }
            }
        }
    }

    @Test fun unnamedFloatLayersKeepTheirOwnSurfaceAndNeverBecomeTheMainPageList() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val decor = activity.window.decorView as ViewGroup
                val panel = Panel(activity)
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.AURORA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                decor.addView(panel.round, ViewGroup.LayoutParams(320, 640))
                panel.layout()
                pages.attach(panel.root, HostBackgroundPagePolicy.Kind.NATIVE)
                fun frame() {
                    panel.layout()
                    panel.root.viewTreeObserver.dispatchOnGlobalLayout()
                    repeat(2) { panel.root.viewTreeObserver.dispatchOnPreDraw() }
                }
                try {
                    frame()
                    val wallpaper = panel.root.background as HostBackgroundDrawable
                    for (slotId in listOf(TestR.id.fl_tab_pager_container, TestR.id.fl_tab_content_container)) {
                        val slot = FrameLayout(panel.root.context).apply { id = slotId }
                        panel.root.addView(slot, FrameLayout.LayoutParams(-1, -1))
                        // 没有章节、笔记等业务 id，验证新面板无需继续补资源名单。
                        val overlay = FrameLayout(panel.root.context).apply { setBackgroundColor(Color.WHITE) }
                        val original = overlay.background
                        val list = RecyclerView(panel.root.context).apply {
                            id = TestR.id.recycler
                            layoutManager = LinearLayoutManager(context)
                        }
                        overlay.addView(list, FrameLayout.LayoutParams(-1, -1))
                        slot.addView(overlay, FrameLayout.LayoutParams(-1, -1))
                        frame()
                        val surface = requireNotNull(overlay.background) { "A floating layer must not become transparent" }
                        assertNotSame(original, surface)
                        assertTrue(HostBackgroundPages.protectedAncestor(list))
                        assertFalse("Overlay scrolling must not move the main wallpaper", pages.scroll(list, 80))
                        assertEquals(0L, wallpaper.scrollOffset)
                        pages.attach(overlay, HostBackgroundPagePolicy.Kind.COMMENT)
                        assertSame("An inline float layer must not acquire a second wallpaper owner",
                            panel.root, HostBackgroundPages.owner(overlay))
                        frame()
                        assertSame(surface, overlay.background)
                        val snapshot = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
                        try {
                            surface.setBounds(0, 0, 320, 640)
                            surface.draw(Canvas(snapshot))
                            assertEquals(255, Color.alpha(snapshot.getPixel(160, 639)))
                        } finally { snapshot.recycle() }
                        slot.removeView(overlay)
                        frame()
                        assertSame(original, overlay.background)
                        panel.root.removeView(slot)
                    }
                } finally { decor.removeView(panel.round) }
            }
        }
    }

    @Test fun customSheetsAndRootAreaLayersPreserveTheirOriginalBackgrounds() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val decor = activity.window.decorView as ViewGroup
                val panel = Panel(activity)
                val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.AURORA), { throw it })
                val pages = HostBackgroundPages(controller, HostVideoCardHostAccess(activity.classLoader)) { throw it }
                decor.addView(panel.round, ViewGroup.LayoutParams(320, 640))
                val originals = ArrayList<Pair<View, Drawable?>>()
                for (slotId in listOf(TestR.id.fl_tab_pager_container, TestR.id.fl_tab_content_container, TestR.id.fl_root_container)) {
                    val slot = FrameLayout(panel.root.context).apply { id = slotId }
                    panel.root.addView(slot, FrameLayout.LayoutParams(-1, -1))
                    // 带遮罩的 sheet 必须保留遮罩、内部底板和控件填色；不能给整个槽位铺实心矩形。
                    val scrim = FrameLayout(panel.root.context).apply { setBackgroundColor(0x7F000000) }
                    val sheet = FrameLayout(panel.root.context).apply { setBackgroundColor(Color.WHITE) }
                    val row = View(panel.root.context).apply { setBackgroundColor(Color.LTGRAY) }
                    slot.addView(scrim, FrameLayout.LayoutParams(-1, -1))
                    scrim.addView(sheet, FrameLayout.LayoutParams(-1, 400).apply { topMargin = 240 })
                    sheet.addView(row, FrameLayout.LayoutParams(-1, 40))
                    originals += listOf(scrim, sheet, row).map { it to it.background }
                    val media = FrameLayout(panel.root.context).apply { id = TestR.id.video_area; setBackgroundColor(Color.BLACK) }
                    slot.addView(media, FrameLayout.LayoutParams(-1, -1))
                    originals += media to media.background
                    if (slotId == TestR.id.fl_root_container) {
                        val rootLayer = FrameLayout(panel.root.context).apply { setBackgroundColor(Color.WHITE) }
                        slot.addView(rootLayer, FrameLayout.LayoutParams(-1, -1))
                        originals += rootLayer to rootLayer.background
                    }
                }
                try {
                    panel.layout()
                    pages.attach(panel.root, HostBackgroundPagePolicy.Kind.NATIVE)
                    repeat(3) { panel.root.viewTreeObserver.dispatchOnGlobalLayout(); panel.root.viewTreeObserver.dispatchOnPreDraw() }
                    originals.forEach { (view, background) -> assertSame(background, view.background) }
                } finally { decor.removeView(panel.round) }
            }
        }
    }

    @Test fun detachRestoresOnlyOwnedBackgroundsAndThemeChangesKeepTextAndClicks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val decor = activity.window.decorView as ViewGroup
                val panel = Panel(activity)
                decor.addView(panel.round, ViewGroup.LayoutParams(320, 640))
                panel.layout()
                panel.root.background = HostBackgroundDrawable(HostBackgroundConfig(HostBackgroundPreset.AURORA))
                val originalHeader = panel.header.background
                val originalInput = panel.input.background
                val originalPlaylist = panel.playlist.background
                val owned = WeakHashMap<View, Drawable>()
                val surfaces = HostDetailSurfaces(panel.root, owned)
                try {
                    surfaces.discover()
                    surfaces.apply(false)
                    val header = panel.header.background
                    surfaces.apply(true)
                    assertSame(header, panel.header.background)
                    var clicked = false
                    panel.input.setOnClickListener { clicked = true }
                    panel.input.performClick()
                    assertTrue(clicked)
                    assertEquals(Color.RED, panel.title.currentTextColor)
                    val replacement = ColorDrawable(Color.MAGENTA)
                    panel.body.background = replacement
                    surfaces.detach()
                    assertSame(originalHeader, panel.header.background)
                    assertSame(originalInput, panel.input.background)
                    assertSame(originalPlaylist, panel.playlist.background)
                    assertSame(replacement, panel.body.background)
                    assertTrue(owned.isEmpty())
                    surfaces.apply(true)
                    assertNotSame(originalHeader, panel.header.background)
                    surfaces.detach()
                    assertSame(originalHeader, panel.header.background)
                    assertSame(replacement, panel.body.background)
                } finally { surfaces.detach(); decor.removeView(panel.round) }
            }
        }
    }
}
