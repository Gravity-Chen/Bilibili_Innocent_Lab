package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardGridAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostHomeCardSpacingInstrumentedTest {
    @Test fun spacingWorksWithoutStylingAndRestoresMarginsOutsideHome() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val density = context.resources.displayMetrics.density
            val loader = javaClass.classLoader!!
            val access = requireNotNull(HostVideoCardGridAccess.resolve(loader))
            val style = HostVideoCardStyle(access, HostVideoCardHostAccess(loader), -1, {}, { throw it },
                decorateCards = false, homeSpacing = HostHomeCardSpacingSpec(0, 40))
            val home = FrameLayout(context)
            val manager = LinearLayoutManager(context)
            val list = RecyclerView(context).apply {
                layoutManager = manager
                setPadding(20, 0, 30, 0)
                addItemDecoration(object : RecyclerView.ItemDecoration() {
                    override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView,
                        state: RecyclerView.State) { outRect.set(6, 8, 10, 12) }
                })
            }
            home.addView(list)
            val card = FrameLayout(context)
            val params = RecyclerView.LayoutParams(-1, -2).apply { setMargins(3, 4, 5, 6) }
            list.addView(card, params)
            style.bind(card, feedback = true)
            style.prepareMeasurement(manager, card)
            assertEquals(3, params.leftMargin)
            assertEquals(4, params.topMargin)
            style.markHome(home)
            repeat(20) {
                style.bind(card, feedback = true)
                style.prepareMeasurement(manager, card)
                assertEquals(-26, params.leftMargin)
                assertEquals(-40, params.rightMargin)
                val gap = (40 * density).toInt()
                assertEquals(gap / 2 - 8, params.topMargin)
                assertEquals(gap - gap / 2 - 12, params.bottomMargin)
                assertNull(card.background)
                assertFalse(card.clipToOutline)
            }
            home.removeView(list)
            style.prepareMeasurement(manager, card)
            // 列表离开首页时必须恢复原值，即使卡片美化关闭。
            assertEquals(3, params.leftMargin)
            assertEquals(4, params.topMargin)
            assertEquals(5, params.rightMargin)
            assertEquals(6, params.bottomMargin)
        }
    }
}
