package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardGridAccess
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostVideoCardLayoutInstrumentedTest {
    @Test fun onlyVerticalRowsReceiveSingleColumnSpacing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val access = requireNotNull(HostVideoCardGridAccess.resolve(javaClass.classLoader!!))
            val params = RecyclerView.LayoutParams(-1, -2)
            assertEquals(2, access.spacingSpan(LinearLayoutManager(context), params))
            assertEquals(-1, access.spacingSpan(LinearLayoutManager(context, RecyclerView.HORIZONTAL, false), params))
            // Grid 的 span 尚未分配时不能按普通单列处理。
            assertEquals(-1, access.spacingSpan(GridLayoutManager(context, 3), params))
            val manager = LinearLayoutManager(context)
            val list = RecyclerView(context).apply { layoutManager = manager }
            list.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: android.graphics.Rect, view: android.view.View,
                    parent: RecyclerView, state: RecyclerView.State) { outRect.set(33, 0, 0, 0) }
            })
            val row = FrameLayout(context)
            list.addView(row, RecyclerView.LayoutParams(-1, -2))
            val insets = android.graphics.Rect()
            access.decorations(manager, row, insets)
            assertEquals(33, insets.left)
            assertEquals(0, insets.right)
        }
    }

    @Test fun nestedPreviewsSharePageBackgroundWithoutReplacingTheirOwnSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val outer = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
            val pager = ViewPager2(context)
            val pagerList = pager.getChildAt(0) as RecyclerView
            val pagerBackground = pagerList.background
            pagerList.addView(outer, RecyclerView.LayoutParams(-1, -1))
            val folder = FrameLayout(context)
            val preview = RecyclerView(context)
            val original = ColorDrawable(Color.TRANSPARENT)
            preview.background = original
            folder.addView(preview)
            outer.addView(folder)
            val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.AURORA), { throw it })
            controller.apply(preview, false)
            val page = outer.background
            assertTrue(page is HostBackgroundDrawable)
            assertSame(pagerBackground, pagerList.background)
            assertSame(original, preview.background)
            controller.apply(preview, true)
            assertSame(page, outer.background)
            assertTrue((page as HostBackgroundDrawable).night)
            controller.scroll(preview, 120)
            assertEquals(0L, page.scrollOffset)
            controller.scroll(outer, 120)
            assertEquals(120L, page.scrollOffset)
        }
    }
}
