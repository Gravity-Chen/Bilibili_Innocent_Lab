package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
