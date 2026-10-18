package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowFeedTransitionInstrumentedTest {
    class Item(val identity: Long, var j: Item? = null) {
        val n = ArrayList<Module>()
        fun a() = identity
        fun b(): List<Module> = j?.b() ?: n
        fun h() = j != null
    }
    class Module(val a: Item) {
        init { a.n += this }
        fun M() = a.j ?: a
        fun O() = true
    }
    class Holder(view: View) : RecyclerView.ViewHolder(view) { var module: Module? = null }

    @Test fun personalCardsAreReadyOnTheFirstVisibleFrameBeforePagerSelection() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val host = activity.createPackageContext("tv.danmaku.bili", 0)
                val context = object : ContextWrapper(activity) {
                    override fun getResources() = host.resources
                    override fun getPackageName() = host.packageName
                }
                val decor = activity.window.decorView as ViewGroup
                for (material in HostCardMaterial.entries) {
                    val page = RelativeLayout(context)
                    val list = RecyclerView(context).apply {
                        id = host.resources.getIdentifier("recycler", "id", host.packageName)
                        layoutManager = LinearLayoutManager(context)
                    }
                    val controller = FollowFeedController(
                        requireNotNull(FollowFeedHostAccess.current(Module::class.java, Item::class.java)),
                        compactHeaderSupported = false, report = {}, error = { throw it }, material = material)
                    var identityOffset = 0L
                    list.adapter = object : RecyclerView.Adapter<Holder>() {
                        override fun getItemCount() = 3
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                            Holder(FrameLayout(context).apply { layoutParams = RecyclerView.LayoutParams(-1, 160) })
                        override fun onBindViewHolder(holder: Holder, position: Int) {
                            holder.module = Module(Item(identityOffset + position + 1L))
                            controller.bind(holder.itemView, holder, position)
                        }
                    }
                    page.addView(list, RelativeLayout.LayoutParams(-1, -1))
                    decor.addView(page, ViewGroup.LayoutParams(700, 900))
                    try {
                        controller.attach(page, personalFeed = true)
                        page.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
                        page.layout(0, 0, 700, 900)
                        val row = requireNotNull(list.getChildAt(0))
                        // 模拟已缓存但尚未选中的相邻页，先完全移出窗口并释放材质。
                        page.translationX = decor.width.toFloat() + 700f
                        controller.visibility(page, false)
                        controller.pause(page)
                        page.viewTreeObserver.dispatchOnPreDraw()
                        assertFalse(controller.replacesModuleBackground(list, row))
                        repeat(3) {
                            // 手指拖入半屏；此时仍没有 visibility(true)/onResume。
                            page.translationX = 120f
                            page.viewTreeObserver.dispatchOnPreDraw()
                            assertTrue("$material needs a card before the pager selects the incoming page",
                                controller.replacesModuleBackground(list, row))
                            assertTrue(controller.replacesPersonalSeparators(list))
                            val layer = page.getChildAt(page.indexOfChild(list) - 1) as ViewGroup
                            assertTrue(layer.childCount > 0)
                            for (index in 0 until layer.childCount) {
                                val card = layer.getChildAt(index)
                                assertNotNull(card.background)
                                assertTrue(card.width > 0 && card.height > 0)
                            }
                            // 滑动中重新绑定的数据也必须在选中回调前更新。
                            identityOffset += 10L
                            for (index in 0 until list.childCount) {
                                val child = list.getChildAt(index)
                                val holder = list.getChildViewHolder(child) as Holder
                                holder.module = Module(Item(identityOffset + index + 1L))
                                controller.bind(child, holder, index)
                            }
                            page.viewTreeObserver.dispatchOnPreDraw()
                            assertTrue(controller.replacesModuleBackground(list, row))
                            // 取消本次拖动或继续切走后，缓存页仍应释放表面。
                            page.translationX = decor.width.toFloat() + 700f
                            page.viewTreeObserver.dispatchOnPreDraw()
                            assertFalse(controller.replacesModuleBackground(list, row))
                        }
                    } finally {
                        controller.close(page)
                        decor.removeView(page)
                    }
                }
            }
        }
    }

    @Test fun visibleCardsKeepTheirMaterialAndBackgroundReplacementDuringVideoEntry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val host = activity.createPackageContext("tv.danmaku.bili", 0)
                val context = object : ContextWrapper(activity) {
                    override fun getResources() = host.resources
                    override fun getPackageName() = host.packageName
                }
                val decor = activity.window.decorView as ViewGroup
                for (material in HostCardMaterial.entries) {
                    val page = FrameLayout(context)
                    val parent = FrameLayout(context)
                    val list = RecyclerView(context).apply {
                        id = host.resources.getIdentifier("dy_list", "id", host.packageName)
                        layoutManager = LinearLayoutManager(context)
                    }
                    val controller = FollowFeedController(
                        requireNotNull(FollowFeedHostAccess.current(Module::class.java, Item::class.java)),
                        compactHeaderSupported = false, report = {}, error = { throw it }, material = material)
                    list.adapter = object : RecyclerView.Adapter<Holder>() {
                        override fun getItemCount() = 3
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                            Holder(FrameLayout(context).apply { layoutParams = RecyclerView.LayoutParams(-1, 160) })
                        override fun onBindViewHolder(holder: Holder, position: Int) {
                            holder.module = Module(Item(position + 1L))
                            controller.bind(holder.itemView, holder, position)
                        }
                    }
                    parent.addView(list, FrameLayout.LayoutParams(-1, -1))
                    page.addView(parent, FrameLayout.LayoutParams(-1, -1))
                    decor.addView(page, ViewGroup.LayoutParams(700, 900))
                    try {
                        controller.attach(page)
                        page.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
                        page.layout(0, 0, 700, 900)
                        page.viewTreeObserver.dispatchOnPreDraw()
                        val row = list.getChildAt(0)
                        assertNotNull(row)
                        assertTrue(controller.replacesModuleBackground(list, row))
                        val layer = parent.getChildAt(parent.indexOfChild(list) - 1) as ViewGroup
                        val card = layer.getChildAt(0)
                        val paint = card.background
                        assertNotNull(paint)
                        repeat(3) {
                            controller.pause(page)
                            page.viewTreeObserver.dispatchOnPreDraw()
                            assertTrue("$material must retain its native-background suppression during entry",
                                controller.replacesModuleBackground(list, row))
                            assertSame("$material must not discard its visible card during entry", paint, card.background)
                            assertSame(layer, card.parent)
                            controller.resume(page)
                            page.viewTreeObserver.dispatchOnPreDraw()
                        }
                        page.visibility = View.GONE
                        controller.pause(page)
                        assertFalse("Hidden cards must release their surfaces", controller.replacesModuleBackground(list, row))
                    } finally {
                        controller.close(page)
                        decor.removeView(page)
                    }
                }
            }
        }
    }
}
