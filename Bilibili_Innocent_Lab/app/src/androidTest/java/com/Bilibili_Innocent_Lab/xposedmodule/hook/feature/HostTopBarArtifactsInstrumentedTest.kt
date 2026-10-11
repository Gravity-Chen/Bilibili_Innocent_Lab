package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarArtifactBinding
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostTopBarArtifactsInstrumentedTest {
    @Test fun reappliedBackgroundsDisappearWhileNativePressClickAndUnderlineSurvive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var clicks = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val context = activity
                val dock = FrameLayout(context)
                val surface = ColorDrawable(Color.DKGRAY)
                dock.background = surface
                val tabs = LinearLayout(context)
                dock.addView(tabs, FrameLayout.LayoutParams(600, 100))
                val tab = FrameLayout(context).apply { setOnClickListener { clicks++ } }
                tabs.addView(tab, LinearLayout.LayoutParams(200, 100))
                val underline = View(context).apply { background = ColorDrawable(Color.MAGENTA) }
                tab.addView(underline, FrameLayout.LayoutParams(60, 6))
                val line = underline.background
                activity.setContentView(dock)
                dock.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
                dock.layout(0, 0, 600, 100)
                val binding = HostTopBarArtifactBinding(dock)
                binding.clear()

                // 替换为同样数量的标签也应发现新 View，不能只比较 childCount。
                tabs.removeView(tab)
                val replacement = FrameLayout(context).apply { background = ColorDrawable(Color.WHITE) }
                tabs.addView(replacement, LinearLayout.LayoutParams(200, 100))
                binding.clear()
                assertNull(replacement.background)
                val extra = View(context).apply { background = ColorDrawable(Color.WHITE) }
                replacement.addView(extra, FrameLayout.LayoutParams(80, 80))
                binding.clear()
                assertNull(extra.background)
                tabs.removeView(replacement)
                tabs.addView(tab, LinearLayout.LayoutParams(200, 100))
                binding.clear()

                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100f, 50f, 0)
                try { assertTrue(tab.dispatchTouchEvent(down)) } finally { down.recycle() }
                // 模拟宿主切页/换肤重新设置背景，几何尺寸保持不变。
                repeat(2) {
                    tab.background = ColorDrawable(Color.WHITE)
                    tab.foreground = ColorDrawable(Color.LTGRAY)
                    dock.foreground = ColorDrawable(Color.WHITE)
                    binding.clear()
                    assertNull(tab.background)
                    assertNull(tab.foreground)
                    assertNull(dock.foreground)
                    assertSame(surface, dock.background)
                    assertSame(line, underline.background)
                    assertTrue("Cleaning must retain the native press until UP", tab.isPressed)
                }
                val up = MotionEvent.obtain(now, now + 100L, MotionEvent.ACTION_UP, 100f, 50f, 0)
                try { assertTrue(tab.dispatchTouchEvent(up)) } finally { up.recycle() }

            }
            instrumentation.waitForIdleSync()
            assertEquals("Native UP must still click the tab once", 1, clicks)
        }
    }
}
