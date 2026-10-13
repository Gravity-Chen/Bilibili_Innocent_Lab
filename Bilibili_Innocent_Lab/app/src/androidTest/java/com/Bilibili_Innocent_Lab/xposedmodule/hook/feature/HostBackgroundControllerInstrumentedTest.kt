package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostBackgroundControllerInstrumentedTest {
    @Test fun customImageCompletesAfterItsFirstPageLeavesTheWindow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decoded = CountDownLatch(1)
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.CUSTOM, veil = 0),
            onError = { throw it }, imageLoader = { _, _ ->
                loading.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                decoded.countDown()
                image
            })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var decor: ViewGroup
            lateinit var first: FrameLayout
            lateinit var next: FrameLayout
            var nextReady = false
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                decor = activity.window.decorView as ViewGroup
                first = FrameLayout(activity)
                decor.addView(first, ViewGroup.LayoutParams(64, 128))
                controller.apply(first, false)
            }
            try {
                assertTrue(loading.await(3, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    decor.removeView(first)
                    next = FrameLayout(activity)
                    nextReady = true
                    decor.addView(next, ViewGroup.LayoutParams(64, 128))
                    controller.apply(next, false)
                }
                release.countDown()
                assertTrue(decoded.await(3, TimeUnit.SECONDS))
                val target = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888)
                try {
                    var pixel = Color.TRANSPARENT
                    val deadline = SystemClock.uptimeMillis() + 3000
                    do {
                        instrumentation.runOnMainSync {
                            target.eraseColor(Color.TRANSPARENT)
                            next.background.setBounds(0, 0, 64, 128)
                            next.background.draw(Canvas(target))
                            pixel = target.getPixel(32, 64)
                        }
                        if (pixel == Color.MAGENTA) break
                        SystemClock.sleep(30)
                    } while (SystemClock.uptimeMillis() < deadline)
                    assertEquals("A detached first page must not strand the image callback", Color.MAGENTA, pixel)
                    instrumentation.runOnMainSync {
                        val later = FrameLayout(next.context)
                        controller.apply(later, false)
                        target.eraseColor(Color.TRANSPARENT)
                        later.background.setBounds(0, 0, 64, 128)
                        later.background.draw(Canvas(target))
                        assertEquals("Pages created after completion must use the loaded image", Color.MAGENTA, target.getPixel(32, 64))
                        controller.release(later)
                    }
                } finally { target.recycle() }
            } finally {
                release.countDown()
                instrumentation.runOnMainSync {
                    controller.release(first)
                    if (nextReady) { controller.release(next); decor.removeView(next) }
                }
                image.recycle()
            }
        }
    }
}
