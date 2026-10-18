package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.animation.ValueAnimator
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostDetailToolbarTransition
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.Bilibili_Innocent_Lab.xposedmodule.test.R as TestR
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.google.android.material.appbar.CollapsingToolbarLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostDetailToolbarTransitionInstrumentedTest {
    @Test fun installedHostExposesTheAuditedScrimContract() {
        val host = InstrumentationRegistry.getInstrumentation().targetContext.createPackageContext(
            "tv.danmaku.bili", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val type = host.classLoader.loadClass("com.google.android.material.appbar.CollapsingToolbarLayout")
        assertEquals(Int::class.javaPrimitiveType, requireNotNull(Lookup.fieldOrNull(type, "currentOffset")).type)
        assertEquals(ValueAnimator::class.java, requireNotNull(Lookup.fieldOrNull(type, "scrimAnimator")).type)
        assertNotNull(Lookup.methodOrNull(type, "setScrimAlpha", Int::class.javaPrimitiveType!!))
        assertNotNull(Lookup.methodOrNull(type, "getScrimVisibleHeightTrigger"))
    }

    @Test fun scrimTracksTheWholeGestureAndReversesWithoutAThresholdOrTrailingAnimator() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                val fixtureNames = mapOf(TestR.id.appbar to "appbar", TestR.id.collapsing_toolbar to "collapsing_toolbar",
                    TestR.id.nav_top_bar to "nav_top_bar")
                // Material 的颜色/样式来自模块主题，仅将测试容器的资源名映射到宿主角色。
                @Suppress("DEPRECATION")
                val resources = object : Resources(activity.assets, activity.resources.displayMetrics, activity.resources.configuration) {
                    override fun getResourceEntryName(resid: Int): String = fixtureNames[resid] ?: super.getResourceEntryName(resid)
                }
                val context = object : ContextWrapper(activity) {
                    override fun getResources() = resources
                }
                val root = FrameLayout(context)
                val appbar = FrameLayout(context).apply { id = TestR.id.appbar }
                val collapsing = CollapsingToolbarLayout(context).apply {
                    id = TestR.id.collapsing_toolbar
                    setContentScrimColor(Color.BLUE)
                }
                val toolbar = View(context).apply { id = TestR.id.nav_top_bar }
                root.addView(appbar, FrameLayout.LayoutParams(320, 600))
                appbar.addView(collapsing, FrameLayout.LayoutParams(320, 600))
                collapsing.addView(toolbar, CollapsingToolbarLayout.LayoutParams(320, 150))
                val decor = activity.window.decorView as ViewGroup
                decor.addView(root, ViewGroup.LayoutParams(320, 640))
                root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 320, 640)
                val type = CollapsingToolbarLayout::class.java
                val offset = requireNotNull(Lookup.fieldOrNull(type, "currentOffset"))
                val animator = requireNotNull(Lookup.fieldOrNull(type, "scrimAnimator"))
                val alpha = requireNotNull(Lookup.methodOrNull(type, "getScrimAlpha"))
                val original = collapsing.contentScrim
                val duration = collapsing.scrimAnimationDuration
                val transition = requireNotNull(HostDetailToolbarTransition.create(root))
                try {
                    fun frame(distance: Int): Int {
                        offset.setInt(collapsing, -distance)
                        transition.apply()
                        return alpha.invoke(collapsing) as Int
                    }
                    assertEquals(0, frame(0))
                    val quarter = frame(112)
                    assertTrue("The scrim must start appearing before the old height trigger", quarter in 35..45)
                    assertEquals(128, frame(225))
                    val threeQuarters = frame(338)
                    assertTrue(threeQuarters in 210..220)
                    assertEquals(255, frame(450))
                    assertEquals(threeQuarters, frame(338))
                    assertEquals(128, frame(225))
                    assertEquals(quarter, frame(112))
                    assertEquals(0, frame(0))
                    // 原生 offset 回调可能在同一帧重启阈值动画；绘制前必须交还给手势进度。
                    collapsing.setScrimsShown(false, false)
                    collapsing.setScrimsShown(true, true)
                    assertTrue((animator.get(collapsing) as ValueAnimator).isStarted)
                    assertEquals(128, frame(225))
                    assertFalse((animator.get(collapsing) as ValueAnimator).isStarted)
                    assertEquals(0, frame(-20))
                    assertEquals(255, frame(500))
                    assertSame("Keep the host's material and ownership", original, collapsing.contentScrim)
                    assertEquals(duration, collapsing.scrimAnimationDuration)
                    frame(0)
                    transition.close()
                    assertEquals(0, alpha.invoke(collapsing))
                    frame(450)
                    transition.close()
                    assertEquals(255, alpha.invoke(collapsing))
                } finally {
                    transition.close()
                    decor.removeView(root)
                }
            }
        }
    }
}
