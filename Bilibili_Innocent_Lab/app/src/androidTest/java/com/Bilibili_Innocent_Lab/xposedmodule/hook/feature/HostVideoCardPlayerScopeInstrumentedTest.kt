package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyle

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostVideoCardPlayerScopeInstrumentedTest {
    @Test fun separatorSuppressionTracksAppliedCardsAndExcludesOtherLists() {
        val host = InstrumentationRegistry.getInstrumentation().targetContext.createPackageContext("tv.danmaku.bili", 0)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val context = object : ContextWrapper(activity) {
                    override fun getResources() = host.resources
                }
                val style = HostVideoCardStyle(null, HostVideoCardHostAccess(javaClass.classLoader!!), -1,
                    onApplied = {}, onError = { throw AssertionError(it) })
                val card = Card(context, endPage = false)
                card.attach(player = false)
                val list = card.root.parent as ViewGroup
                assertFalse(style.replacesSeparators(list))
                style.bind(card.root)
                assertTrue(style.replacesSeparators(list))
                val surface = card.root.background
                card.root.background = card.background
                assertFalse(style.replacesSeparators(list))
                card.root.background = surface
                val parent = list.parent as View
                parent.id = host.resources.getIdentifier("video_container", "id", "tv.danmaku.bili")
                assertFalse(style.replacesSeparators(list))
                parent.id = View.NO_ID
                assertTrue(style.replacesSeparators(list))
                val disabled = HostVideoCardStyle(null, HostVideoCardHostAccess(javaClass.classLoader!!), -1,
                    onApplied = {}, onError = { throw AssertionError(it) }, decorateCards = false)
                assertFalse(disabled.replacesSeparators(list))
                list.removeView(card.root)
                assertFalse(style.replacesSeparators(list))
                assertFalse(style.replacesSeparators(RecyclerView(activity)))
            }
        }
    }

    private class Card(private val context: Context, endPage: Boolean) {
        private fun id(name: String): Int = context.resources.getIdentifier(name, "id", "tv.danmaku.bili")
            .also { assertTrue("Missing host resource: $name", it != 0) }

        val root = FrameLayout(context)
        val cover = ImageView(context).apply { id = id("cover") }
        val info = FrameLayout(context).apply { id = id("cover_bottom_info_container") }
        val background = ColorDrawable(Color.TRANSPARENT)

        init {
            root.background = background
            val coverRoot = FrameLayout(context)
            coverRoot.addView(cover, FrameLayout.LayoutParams(200, 100))
            if (endPage) coverRoot.addView(View(context).apply { id = id("endpage_progess_root") })
            root.addView(coverRoot, FrameLayout.LayoutParams(200, 100))
            root.addView(TextView(context).apply { id = id("title"); text = "Video" })
            root.addView(info, FrameLayout.LayoutParams(200, 20))
        }

        fun attach(player: Boolean, listName: String? = null) {
            val parent = FrameLayout(root.context)
            if (player) parent.id = id("video_container")
            val list = RecyclerView(root.context)
            if (listName != null) list.id = id(listName)
            list.layoutManager = LinearLayoutManager(root.context)
            parent.addView(list)
            list.layout(0, 0, 1080, 1800)
            list.addView(root, RecyclerView.LayoutParams(400, 200))
            root.layout(0, 0, 400, 200)
            cover.layout(0, 0, 200, 100)
        }

        fun assertUnmodified() {
            assertSame(background, root.background)
            assertSame(ViewOutlineProvider.BACKGROUND, cover.outlineProvider)
            assertFalse(cover.clipToOutline)
            assertFalse(root.clipToOutline)
            val params = info.layoutParams as ViewGroup.MarginLayoutParams
            assertEquals(0, params.leftMargin)
            assertEquals(0, params.rightMargin)
            assertEquals(0, params.bottomMargin)
        }
    }

    private fun check(block: (Context, HostVideoCardStyle) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = runCatching {
            instrumentation.targetContext.createPackageContext("tv.danmaku.bili", 0)
        }.getOrNull()
        assumeTrue("Requires the installed host's card resources", context != null)
        instrumentation.runOnMainSync {
            val style = HostVideoCardStyle(null, HostVideoCardHostAccess(javaClass.classLoader!!), -1,
                onApplied = {}, onError = { throw AssertionError(it) })
            block(requireNotNull(context), style)
        }
    }

    @Test fun endPageCardIsExcludedBeforeAttachmentAndAfterLayout() = check { context, style ->
        val card = Card(context, endPage = true)
        style.bind(card.root)
        card.assertUnmodified()
        card.attach(player = false)
        style.bind(card.root)
        card.assertUnmodified()
    }

    @Test fun backgroundWorksWithoutChangingCardsWhenCardStylingIsDisabled() = check { context, _ ->
        val card = Card(context, endPage = false)
        card.attach(player = false)
        val style = HostVideoCardStyle(null, HostVideoCardHostAccess(javaClass.classLoader!!), -1,
            onApplied = {}, onError = { throw AssertionError(it) },
            decorateCards = false,
            background = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController(
                com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig(
                    com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset.AURORA),
                onError = { throw AssertionError(it) }))
        style.bind(card.root)
        card.assertUnmodified()
        assertTrue((card.root.parent as View).background is com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable)
    }

    @Test fun playerAncestorExcludesCardsWithoutEndPageMarker() = check { context, style ->
        val card = Card(context, endPage = false)
        card.attach(player = true)
        style.bind(card.root)
        card.assertUnmodified()
    }

    @Test fun storyPagerKeepsFullWidthRowsAndNativeSurfaces() = check { context, style ->
        val card = Card(context, endPage = false)
        card.attach(player = false)
        val list = card.root.parent as ViewGroup
        val page = list.parent as View
        page.id = context.resources.getIdentifier("story_activity_root", "id", "tv.danmaku.bili")
        assertTrue(page.id != 0)
        val params = card.root.layoutParams as ViewGroup.MarginLayoutParams
        val original = listOf(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin)
        style.bind(card.root)
        style.prepareMeasurement(LinearLayoutManager(context), card.root)
        card.assertUnmodified()
        assertEquals(original, listOf(params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin))
        assertFalse(style.replacesSeparators(list))
    }

    @Test fun sideCenterPodcastPreviewsKeepTheirDarkMediaSurface() = check { context, style ->
        val card = Card(context, endPage = false)
        card.attach(player = false, listName = "recycler_top_car")
        style.bind(card.root)
        card.assertUnmodified()
        assertFalse(style.replacesSeparators(card.root.parent as ViewGroup))
    }

    @Test fun latePlayerAttachmentDoesNotDecorateCover() = check { context, style ->
        val card = Card(context, endPage = false)
        style.bind(card.root)
        card.attach(player = true)
        assertSame(card.background, card.root.background)
        assertSame(ViewOutlineProvider.BACKGROUND, card.cover.outlineProvider)
        assertFalse(card.cover.clipToOutline)
        assertFalse(card.root.clipToOutline)
    }

    @Test fun ordinaryVideoListStillStylesCoversAndInfo() = check { context, style ->
        val card = Card(context, endPage = false)
        card.attach(player = false)
        style.bind(card.root)
        assertTrue(card.cover.clipToOutline)
        val params = card.info.layoutParams as ViewGroup.MarginLayoutParams
        assertTrue(params.leftMargin > 0)
        assertTrue(params.rightMargin > 0)
        assertTrue(params.bottomMargin > 0)
    }
}
