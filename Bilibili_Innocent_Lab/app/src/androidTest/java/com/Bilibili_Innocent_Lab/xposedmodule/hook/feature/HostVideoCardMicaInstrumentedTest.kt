package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardMaterialScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardSurface
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenMicaFallbackReason
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostVideoCardMicaInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun absentWallpaperUsesOpaqueFallbackAndCloseRespectsBackgroundOwnership() {
        instrumentation.runOnMainSync {
            val list = FrameLayout(instrumentation.targetContext)
            val first = View(list.context)
            val second = View(list.context)
            val original = ColorDrawable(Color.GREEN)
            first.background = original
            first.setPadding(3, 5, 7, 11)
            list.addView(first); list.addView(second)
            val scope = HostVideoCardSurface.Scope(list, false, material = HostCardMaterial.MICA)
            try {
                val surface = scope.apply(first, 20f)
                scope.apply(second, 20f)
                assertSame(surface, scope.apply(first, 20f))
                assertEquals(LumenMicaFallbackReason.NO_BACKGROUND, scope.micaDiagnostics()?.fallbackReason)
                assertEquals(0L, scope.micaPreparations())
                assertEquals(listOf(3, 5, 7, 11), listOf(first.paddingLeft, first.paddingTop, first.paddingRight, first.paddingBottom))
                val replacement = ColorDrawable(Color.BLUE)
                second.background = replacement
                scope.close()
                assertSame(original, first.background)
                assertSame(replacement, second.background)
                assertTrue(scope.micaDiagnostics()?.closed == true)
            } finally { scope.close() }
        }
    }

    @Test fun micaSharesStaticWallpaperAcrossNativeAndComposeAndDoesNotReprepareDuringScrolling() {
        val bitmap = Bitmap.createBitmap(96, 192, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawColor(Color.BLUE)
            val paint = android.graphics.Paint().apply { color = Color.RED }
            canvas.drawRect(0f, 96f, 96f, 192f, paint)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var list: FrameLayout
                lateinit var wallpaper: HostBackgroundDrawable
                lateinit var top: View
                lateinit var bottom: View
                lateinit var compose: View
                lateinit var window: Window
                lateinit var scope: HostVideoCardSurface.Scope
                lateinit var paint: HostCardMaterialScope.CardPaint
                scenario.onActivity { activity ->
                    activity.activeConfirmDialog?.dismiss()
                    window = activity.window
                    wallpaper = HostBackgroundDrawable(HostBackgroundConfig(HostBackgroundPreset.CUSTOM, veil = 0), image = bitmap)
                    list = FrameLayout(activity).apply { background = wallpaper }
                    top = View(activity); bottom = View(activity)
                    compose = object : View(activity) {
                        private val location = IntArray(2)
                        override fun onDraw(canvas: Canvas) {
                            getLocationInWindow(location)
                            paint.position(location[0].toFloat(), location[1].toFloat())
                            paint.setBounds(0, 0, width, height); paint.draw(canvas)
                        }
                    }
                    list.addView(top, FrameLayout.LayoutParams(300, 180).apply { leftMargin = 40; topMargin = 200 })
                    list.addView(bottom, FrameLayout.LayoutParams(300, 180).apply { leftMargin = 40; topMargin = 1400 })
                    list.addView(compose, FrameLayout.LayoutParams(300, 180).apply { leftMargin = 420; topMargin = 200 })
                    // 未知的子内容即使是高饱和色也不能进入 Mica 的静态输入。
                    list.addView(View(activity).apply { setBackgroundColor(Color.GREEN) },
                        FrameLayout.LayoutParams(200, 200).apply { leftMargin = 750; topMargin = 600 })
                    activity.setContentView(list)
                    scope = HostVideoCardSurface.Scope(list, false, material = HostCardMaterial.MICA)
                    scope.apply(top, 20f); scope.apply(bottom, 20f)
                    paint = scope.composePaint(compose, 20f)
                }
                try {
                    SystemClock.sleep(1000)
                    instrumentation.runOnMainSync {
                        val status = requireNotNull(scope.micaDiagnostics())
                        assertEquals(status.toString(), LumenMicaFallbackReason.NONE, status.fallbackReason)
                        assertTrue(status.toString(), status.micaVisible)
                        assertTrue(status.snapshotBytes in 1..73_728)
                        assertEquals(1L, scope.micaPreparations())
                    }
                    val before = capture(window)
                    val topPixel: Int
                    val bottomPixel: Int
                    try {
                        topPixel = pixel(before, top); bottomPixel = pixel(before, bottom)
                        assertTrue("Mica lost static spatial color: $topPixel / $bottomPixel", Color.blue(topPixel) > Color.blue(bottomPixel))
                        assertTrue(abs(Color.blue(topPixel) - Color.blue(pixel(before, compose))) < 5)
                    } finally { before.recycle() }
                    instrumentation.runOnMainSync {
                        repeat(20) { wallpaper.scrollOffset += 50; scope.onPreDraw() }
                        assertEquals(1L, scope.micaPreparations())
                        compose.translationY = 1200f; compose.invalidate()
                        // 软件采样不得破坏屏幕显示列表引用的共享节点。
                        val sampled = Bitmap.createBitmap(top.width, top.height, Bitmap.Config.ARGB_8888)
                        try { top.draw(Canvas(sampled)); assertEquals(255, Color.alpha(sampled.getPixel(150, 90))) }
                        finally { sampled.recycle() }
                    }
                    SystemClock.sleep(200)
                    val moved = capture(window)
                    try {
                        assertTrue(abs(Color.blue(topPixel) - Color.blue(pixel(moved, top))) < 3)
                        assertTrue(abs(Color.blue(bottomPixel) - Color.blue(pixel(moved, compose))) < 5)
                    } finally { moved.recycle() }
                    instrumentation.runOnMainSync {
                        list.translationX = list.width * 2f
                        list.background = null
                        list.invalidate()
                    }
                    SystemClock.sleep(150)
                    instrumentation.runOnMainSync {
                        assertEquals("Offscreen pages must defer wallpaper processing", LumenMicaFallbackReason.NONE,
                            scope.micaDiagnostics()?.fallbackReason)
                        list.background = wallpaper
                        list.translationX = 0f
                        list.invalidate()
                    }
                    SystemClock.sleep(200)
                    instrumentation.runOnMainSync {
                        assertTrue(scope.micaDiagnostics()?.micaVisible == true)
                        assertEquals(1L, scope.micaPreparations())
                    }
                    instrumentation.runOnMainSync {
                        list.background = null
                        list.invalidate()
                    }
                    SystemClock.sleep(300)
                    instrumentation.runOnMainSync {
                        assertEquals(LumenMicaFallbackReason.NO_BACKGROUND, scope.micaDiagnostics()?.fallbackReason)
                        assertEquals(1L, scope.micaPreparations())
                        list.background = wallpaper
                        list.invalidate()
                    }
                    SystemClock.sleep(500)
                    instrumentation.runOnMainSync {
                        assertTrue(scope.micaDiagnostics()?.micaVisible == true)
                        assertEquals(2L, scope.micaPreparations())
                        scope.refresh(true)
                        scope.close()
                        assertNull(top.background); assertNull(bottom.background)
                        assertEquals(LumenMicaFallbackReason.CLOSED, scope.micaDiagnostics()?.fallbackReason)
                    }
                } finally { instrumentation.runOnMainSync { paint.close(); scope.close() } }
            }
        } finally { bitmap.recycle() }
    }

    private fun pixel(bitmap: Bitmap, view: View): Int {
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationInWindow(location) }
        return bitmap.getPixel(location[0] + view.width / 2, location[1] + view.height / 2)
    }

    private fun capture(window: Window): Bitmap {
        val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(window, bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }
}
