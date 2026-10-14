package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbHeroBinding
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarArtifactBinding
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostGarbChromeInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        .createPackageContext("tv.danmaku.bili", 0)

    @Test fun lateArtworkIsClearedWhileNativeCharacterDrawablesSurvive() {
        val context = context
        fun id(name: String) = context.resources.getIdentifier(name, "id", "tv.danmaku.bili").also { check(it != 0) }
        val dock = FrameLayout(context)
        val glass = ColorDrawable(Color.DKGRAY)
        dock.background = glass
        val top = ImageView(context).apply { this.id = id("tabs_bg_view") }
        val bottom = ImageView(context).apply { this.id = id("tab_background") }
        val icon = ImageView(context).apply { this.id = id("tab_svg"); setImageDrawable(ColorDrawable(Color.BLUE)) }
        val character = icon.drawable
        dock.addView(top); dock.addView(bottom); dock.addView(icon)
        val binding = HostTopBarArtifactBinding(dock)
        repeat(3) {
            for (view in listOf(top, bottom)) {
                view.visibility = View.VISIBLE; view.alpha = 1f
                view.setImageDrawable(ColorDrawable(Color.WHITE))
            }
            binding.clear()
            HostBottomBarFxController.stripAllHostArtifacts(dock)
            for (view in listOf(top, bottom)) {
                assertEquals(View.GONE, view.visibility)
                assertNull(view.drawable)
            }
            assertSame(glass, dock.background)
            assertSame(character, icon.drawable)
        }
    }

    @Test fun compactCharacterKeepsItsAspectRatioAndRestoresNativeSizeWithoutGarb() {
        val context = context
        val density = context.resources.displayMetrics.density
        val host = FrameLayout(context)
        val container = FrameLayout(context)
        val tab = FrameLayout(context)
        val normal = FrameLayout(context).apply {
            id = context.resources.getIdentifier("normal_ll", "id", "tv.danmaku.bili")
        }
        val icon = ImageView(context).apply {
            id = context.resources.getIdentifier("tab_svg", "id", "tv.danmaku.bili")
        }
        normal.addView(icon, FrameLayout.LayoutParams(180, 135))
        tab.addView(normal, FrameLayout.LayoutParams(200, 160))
        container.addView(tab); host.addView(container)
        val config = HostBottomBarFxConfig(compact = true, iconOnly = true)
        val accent = 0xFF6E89FF.toInt()
        repeat(3) {
            HostBottomBarFxController.alignTabContent(host, container, density, 4, config,
                HostChromeColors(true, accent, accent))
            assertTrue(icon.layoutParams.height <= 32f * density + 1)
            assertEquals(4f / 3f, icon.layoutParams.width.toFloat() / icon.layoutParams.height, .03f)
        }
        HostBottomBarFxController.alignTabContent(host, container, density, 4, config, HostChromeColors(true, accent))
        assertEquals(180, icon.layoutParams.width)
        assertEquals(135, icon.layoutParams.height)
    }

    @Test fun mineHeroRetainsItsArtworkAndRestoresNativeForegroundOnThemeExit() {
        val context = context
        val root = FrameLayout(context)
        val anchor = View(context)
        val artworkContainer = FrameLayout(context).apply { foreground = ColorDrawable(Color.TRANSPARENT) }
        val hero = ImageView(context).apply {
            id = context.resources.getIdentifier("main_top_bg_imageview", "id", "tv.danmaku.bili")
            check(id != 0)
            setImageDrawable(ColorDrawable(Color.YELLOW))
        }
        artworkContainer.addView(hero)
        artworkContainer.addView(View(context)) // 动态装扮在图片之上还有原生视频子层。
        root.addView(artworkContainer); root.addView(anchor)
        val artwork = hero.drawable
        val foreground = artworkContainer.foreground
        val outline = artworkContainer.outlineProvider
        val binding = HostGarbHeroBinding(anchor)
        binding.refresh(true)
        assertSame(artwork, hero.drawable)
        assertNotSame(foreground, artworkContainer.foreground)
        assertTrue(artworkContainer.clipToOutline)
        binding.refresh(false)
        assertSame(artwork, hero.drawable)
        assertSame(foreground, artworkContainer.foreground)
        assertSame(outline, artworkContainer.outlineProvider)
        assertFalse(artworkContainer.clipToOutline)
    }
}
