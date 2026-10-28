package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.view.ContextThemeWrapper
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.NativePaintFixtureActivity
import com.google.android.material.appbar.AppBarLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * The khome 9.16 frame rebuilt from the module's own Material classes: status-bar margin on the
 * AppBar, a scroll|enterAlways|snap search row, the capsule row, and a ViewPager2 feed container
 * driven by AppBarLayout.ScrollingViewBehavior. Exercises the real behavior layout math.
 */
@RunWith(AndroidJUnit4::class)
class HostKhomeTopFloatInstrumentedTest {
    /** Same simple name and offset semantics as the host and androidx refresh layouts. */
    class SwipeRefreshLayout(context: Context, private val density: Float) : FrameLayout(context) {
        var start = (-40 * density).roundToInt(); var end = (64 * density).roundToInt(); var custom = false
        var refreshingNow = false
        fun getProgressViewStartOffset() = start
        fun getProgressViewEndOffset() = end
        fun setProgressViewOffset(scale: Boolean, start: Int, end: Int) { this.start = start; this.end = end; custom = true }
        fun isRefreshing() = refreshingNow
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private class Fixture(val root: CoordinatorLayout, val appBar: AppBarLayout, val owner: View, val content: FrameLayout,
                          val pager: ViewPager2, val list: RecyclerView, val refresh: SwipeRefreshLayout, val strip: ViewGroup)

    @Test fun floatsTheFeedUnderTheAppBarAndGivesEverythingBack() {
        ActivityScenario.launch(NativePaintFixtureActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            val density = instrumentation.targetContext.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).roundToInt()
            scenario.onActivity { activity ->
                val context = ContextThemeWrapper(activity, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar)
                val root = CoordinatorLayout(context)
                val list = RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 60
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                            TextView(context).apply { layoutParams = RecyclerView.LayoutParams(-1, dp(80)) }) {}
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                            (holder.itemView as TextView).text = "card $position"
                        }
                    }
                }
                val refresh = SwipeRefreshLayout(context, density).apply { addView(list, FrameLayout.LayoutParams(-1, -1)) }
                val pager = ViewPager2(context).apply {
                    offscreenPageLimit = 1
                    adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 2
                        override fun getItemViewType(position: Int) = position
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                            val page = FrameLayout(context).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) }
                            // Page 1 has no list (a Compose page in the host): the page itself is padded.
                            page.addView(if (viewType == 0) refresh else View(context), FrameLayout.LayoutParams(-1, -1))
                            return object : RecyclerView.ViewHolder(page) {}
                        }
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                    }
                }
                val content = FrameLayout(context).apply { addView(pager, FrameLayout.LayoutParams(-1, -1)) }
                val appBar = AppBarLayout(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    addView(View(context), AppBarLayout.LayoutParams(-1, dp(44)).apply {
                        scrollFlags = AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                            AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS or AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP
                    })
                }
                val row = FrameLayout(context)
                val owner = View(context)
                row.addView(owner, FrameLayout.LayoutParams(-1, -1))
                appBar.addView(row, AppBarLayout.LayoutParams(-1, dp(40)))
                root.addView(View(context), CoordinatorLayout.LayoutParams(-1, dp(68)))
                root.addView(content, CoordinatorLayout.LayoutParams(-1, -1).apply {
                    behavior = AppBarLayout.ScrollingViewBehavior(); gravity = Gravity.TOP
                })
                root.addView(appBar, CoordinatorLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
                activity.setContentView(root)
                fixture = Fixture(root, appBar, owner, content, pager, list, refresh, pager.getChildAt(0) as ViewGroup)
            }
            idle()
            val float = HostKhomeTopFloat()
            var nativeTop = 0; var nativeBottom = 0; var nativeHeight = 0; var shift = 0
            main {
                nativeTop = fixture.content.top; nativeBottom = fixture.content.bottom; nativeHeight = fixture.content.height
                shift = dp(24) + fixture.appBar.height
                assertEquals("Native feed starts at the AppBar bottom", shift, nativeTop)
            }
            fun settle(enabled: Boolean = true) {
                repeat(6) {
                    var changed = false
                    main { changed = float.sync(HostKhomeTopFloat.locate(fixture.owner, 0), enabled, Color.WHITE) }
                    idle()
                    if (!changed) return
                }
            }
            settle()
            main {
                assertTrue(float.floating)
                assertEquals("Feed runs up to the CoordinatorLayout top", 0, fixture.content.top)
                assertEquals("Feed bottom is unchanged", nativeBottom, fixture.content.bottom)
                assertEquals(nativeHeight + shift, fixture.content.height)
                assertEquals(shift, fixture.list.paddingTop)
                assertFalse(fixture.list.clipToPadding)
                val first = fixture.list.getChildAt(0)
                assertEquals("First card rests below the capsule", shift, first.top)
                assertEquals(HostKhomeFloatPolicy.refreshOffsets(-dp(40), dp(64), shift),
                    fixture.refresh.start to fixture.refresh.end)
                val bare = (0 until fixture.strip.childCount).map { fixture.strip.getChildAt(it) }
                    .single { page -> (page as ViewGroup).getChildAt(0) !== fixture.refresh }
                assertEquals("A page without a list is padded itself", shift, bare.paddingTop)
                fixture.appBar.setExpanded(false, false)
            }
            idle(); settle()
            main {
                assertEquals("Collapse still offsets the feed with the AppBar", fixture.appBar.bottom - shift, fixture.content.top)
                assertTrue(fixture.content.top < 0)
                // The host's no-top-bar bottom tab: behavior detached, AppBar gone.
                (fixture.content.layoutParams as CoordinatorLayout.LayoutParams).behavior = null
                fixture.appBar.visibility = View.GONE
                fixture.content.requestLayout()
            }
            idle(); settle()
            main {
                assertFalse(float.floating)
                assertEquals("Shared container is given back", 0, (fixture.content.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
                assertEquals("Hidden home lists keep their padding", shift, fixture.list.paddingTop)
                (fixture.content.layoutParams as CoordinatorLayout.LayoutParams).behavior = AppBarLayout.ScrollingViewBehavior()
                fixture.appBar.visibility = View.VISIBLE
                fixture.appBar.setExpanded(true, false)
                fixture.content.requestLayout()
            }
            idle(); settle()
            main {
                assertTrue(float.floating)
                assertEquals(0, fixture.content.top)
                // The host rewrites the list padding: its value becomes the new base.
                fixture.list.setPadding(0, dp(8), 0, 0)
                float.markDirty()
            }
            idle(); settle()
            main {
                assertEquals(dp(8) + shift, fixture.list.paddingTop)
                assertTrue(float.restore())
            }
            idle()
            main {
                assertFalse(float.floating)
                assertEquals(0, (fixture.content.layoutParams as ViewGroup.MarginLayoutParams).topMargin)
                assertEquals(nativeTop, fixture.content.top)
                assertEquals(nativeBottom, fixture.content.bottom)
                assertEquals(dp(8), fixture.list.paddingTop)
                assertTrue(fixture.list.clipToPadding)
                val (start, end) = HostKhomeFloatPolicy.restoredRefreshOffsets(-dp(40), dp(64))
                assertEquals(start to end, fixture.refresh.start to fixture.refresh.end)
                (0 until fixture.strip.childCount).forEach { assertEquals(0, fixture.strip.getChildAt(it).paddingTop) }
            }
        }
    }

    @Test fun refreshInProgressIsNeverReset() {
        ActivityScenario.launch(NativePaintFixtureActivity::class.java).use { scenario ->
            lateinit var refresh: SwipeRefreshLayout
            lateinit var owner: View
            val density = instrumentation.targetContext.resources.displayMetrics.density
            scenario.onActivity { activity ->
                val context = ContextThemeWrapper(activity, com.google.android.material.R.style.Theme_MaterialComponents_Light_NoActionBar)
                val root = CoordinatorLayout(context)
                val list = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
                refresh = SwipeRefreshLayout(context, density).apply { refreshingNow = true; addView(list, FrameLayout.LayoutParams(-1, -1)) }
                val pager = ViewPager2(context).apply {
                    adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun getItemCount() = 1
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                            FrameLayout(context).apply { layoutParams = ViewGroup.LayoutParams(-1, -1); addView(refresh) }) {}
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                    }
                }
                val content = FrameLayout(context).apply { addView(pager, FrameLayout.LayoutParams(-1, -1)) }
                val appBar = AppBarLayout(context)
                val row = FrameLayout(context)
                owner = View(context)
                row.addView(owner)
                appBar.addView(row, AppBarLayout.LayoutParams(-1, (40 * density).roundToInt()))
                root.addView(content, CoordinatorLayout.LayoutParams(-1, -1).apply { behavior = AppBarLayout.ScrollingViewBehavior() })
                root.addView(appBar, CoordinatorLayout.LayoutParams(-1, -2))
                activity.setContentView(root)
            }
            idle()
            val float = HostKhomeTopFloat()
            repeat(3) { main { float.sync(HostKhomeTopFloat.locate(owner, 0), true, null) }; idle() }
            main {
                assertTrue(float.floating)
                assertFalse("A running refresh keeps its spinner offsets", refresh.custom)
                refresh.refreshingNow = false
            }
            repeat(3) { main { float.sync(HostKhomeTopFloat.locate(owner, 0), true, null) }; idle() }
            main { assertTrue("Applied once the refresh finished", refresh.custom) }
        }
    }

    private fun idle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(50)
        instrumentation.waitForIdleSync()
    }
}
