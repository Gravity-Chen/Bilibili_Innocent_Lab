package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardSurface
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostCardAcrylicScope
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.lumen.coacervation.engine.host.LumenAcrylicFallbackReason
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostVideoCardAcrylicInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun releaseRestoresOnlyOwnedBackgroundAndKeepsHostPadding() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val list = FrameLayout(context)
            val first = View(context)
            val second = View(context)
            val original = ColorDrawable(Color.GREEN)
            first.background = original
            first.setPadding(3, 5, 7, 11)
            list.addView(first)
            list.addView(second)
            val scope = HostVideoCardSurface.Scope(list, false)
            try {
                val background = scope.apply(first, 12f)
                assertSame(background, scope.apply(first, 12f))
                assertNotSame(background, scope.apply(second, 12f))
                assertEquals(listOf(3, 5, 7, 11), listOf(first.paddingLeft, first.paddingTop,
                    first.paddingRight, first.paddingBottom))
                scope.release(first)
                assertSame(original, first.background)
                assertNull(scope.diagnostics(first))
                assertNotSame(background, scope.apply(first, 18f))
                val replacement = ColorDrawable(Color.BLUE)
                second.background = replacement
                scope.close()
                assertSame(original, first.background)
                assertSame(replacement, second.background)
            } finally { scope.close() }
        }
    }

    @Test fun realAcrylicSamplesWallpaperAndUpdatesWithoutSamplingCardChildren() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var list: FrameLayout
            lateinit var card: FrameLayout
            lateinit var window: Window
            lateinit var scope: HostVideoCardSurface.Scope
            lateinit var observer: ViewTreeObserver.OnPreDrawListener
            var night = false
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                list = FrameLayout(activity).apply { setBackgroundColor(Color.BLUE) }
                card = FrameLayout(activity)
                // 子内容是全红色时，采样源仍只能得到蓝色壁纸。
                card.addView(View(activity).apply { setBackgroundColor(Color.RED) },
                    FrameLayout.LayoutParams(120, 60))
                list.addView(card, FrameLayout.LayoutParams(400, 240).apply { leftMargin = 60; topMargin = 320 })
                activity.setContentView(list)
                scope = HostVideoCardSurface.Scope(list, false)
                scope.apply(card, 24f)
                observer = ViewTreeObserver.OnPreDrawListener { scope.refresh(night); true }
                list.viewTreeObserver.addOnPreDrawListener(observer)
            }
            try {
                SystemClock.sleep(900)
                instrumentation.runOnMainSync {
                    val status = requireNotNull(scope.diagnostics(card))
                    assertEquals("$status; ${scope.samplingDiagnostics()}", LumenAcrylicFallbackReason.NONE, status.fallbackReason)
                    assertTrue(status.toString(), status.acrylicVisible)
                    assertNotEquals(status.toString(), LumenSurfaceBackend.STATIC, status.backdropBackend)
                    // 顶栏的软采样会在同一窗口中重新绘制卡片，不能替换屏幕缓存中的 GPU 节点。
                    val sampled = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                    try { card.draw(Canvas(sampled)) } finally { sampled.recycle() }
                    list.invalidate()
                }
                SystemClock.sleep(150)
                val blue = capture(window)
                try {
                    save(blue, "blue")
                    val before = pixel(blue, card)
                    assertTrue("Card sampled its own red children: $before", Color.blue(before) > Color.red(before))
                    instrumentation.runOnMainSync {
                        list.setBackgroundColor(Color.RED)
                    }
                    SystemClock.sleep(500)
                    val red = capture(window)
                    try {
                        save(red, "red")
                        val after = pixel(red, card)
                        var diagnostics = ""
                        instrumentation.runOnMainSync { diagnostics = scope.samplingDiagnostics().toString() }
                        assertTrue("Wallpaper did not update: $before -> $after; $diagnostics", Color.red(after) > Color.blue(after))
                        assertTrue(abs(Color.blue(before) - Color.blue(after)) > 5)
                        // 视口尺寸改变不能使卡片缓存继续引用已丢弃的共享节点。
                        instrumentation.runOnMainSync {
                            list.layout(0, 0, list.width, list.height - 100)
                            list.setBackgroundColor(Color.BLUE)
                        }
                        SystemClock.sleep(400)
                        val resized = capture(window)
                        try {
                            val next = pixel(resized, card)
                            assertTrue("Viewport resize lost the shared material: $next", Color.blue(next) > Color.red(next))
                        } finally { resized.recycle() }
                    } finally { red.recycle() }
                } finally { blue.recycle() }
                instrumentation.runOnMainSync {
                    val drawable = card.background
                    night = true
                    scope.refresh(true)
                    assertSame(drawable, scope.apply(card, 24f))
                    scope.release(card)
                    assertNull(card.background)
                    scope.apply(card, 24f)
                }
                SystemClock.sleep(500)
                instrumentation.runOnMainSync {
                    assertTrue(requireNotNull(scope.diagnostics(card)).acrylicVisible)
                    list.visibility = View.GONE
                    scope.onPreDraw()
                    assertTrue(scope.samplingDiagnostics().paused)
                    list.visibility = View.VISIBLE
                    scope.onPreDraw()
                    assertFalse(scope.samplingDiagnostics().paused)
                }
            } finally {
                instrumentation.runOnMainSync {
                    list.viewTreeObserver.removeOnPreDrawListener(observer)
                    scope.close()
                }
            }
        }
    }

    @Test fun nativeAndComposeCardsShareOneMaterialAndFollowTheirWindowPosition() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var list: FrameLayout
            lateinit var first: View
            lateinit var second: View
            lateinit var compose: View
            lateinit var window: Window
            lateinit var scope: HostVideoCardSurface.Scope
            lateinit var paint: HostCardAcrylicScope.CardPaint
            scenario.onActivity { activity ->
                activity.activeConfirmDialog?.dismiss()
                window = activity.window
                list = FrameLayout(activity).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(Color.BLUE, Color.RED))
                }
                first = View(activity)
                second = View(activity)
                compose = object : View(activity) {
                    private val position = IntArray(2)
                    override fun onDraw(canvas: Canvas) {
                        getLocationInWindow(position)
                        paint.position(position[0].toFloat(), position[1].toFloat())
                        paint.setBounds(0, 0, width, height)
                        paint.draw(canvas)
                    }
                }
                list.addView(first, FrameLayout.LayoutParams(300, 180).apply { leftMargin = 40; topMargin = 180 })
                list.addView(second, FrameLayout.LayoutParams(300, 180).apply { leftMargin = 40; topMargin = 1300 })
                list.addView(compose, FrameLayout.LayoutParams(300, 180).apply { leftMargin = 400; topMargin = 180 })
                activity.setContentView(list)
                scope = HostVideoCardSurface.Scope(list, false)
                scope.apply(first, 20f)
                scope.apply(second, 20f)
                paint = scope.composePaint(compose, 20f)
            }
            try {
                SystemClock.sleep(1000)
                val before = capture(window)
                try {
                    val top = pixel(before, first)
                    val bottom = pixel(before, second)
                    val fromCompose = pixel(before, compose)
                    assertTrue("Wrong shared-field crop: $top / $bottom", Color.blue(top) > Color.blue(bottom))
                    assertTrue("Wrong Compose crop: $top / $fromCompose", abs(Color.blue(top) - Color.blue(fromCompose)) < 12)
                    var recordings = 0L
                    instrumentation.runOnMainSync {
                        assertEquals(1, scope.samplingDiagnostics().attachedSurfaces)
                        recordings = scope.materialRecordings
                        compose.translationY = 1120f
                        compose.invalidate()
                        // 同一帧重复绘制多个卡片不会增加采样绑定。
                        first.invalidate(); second.invalidate()
                    }
                    SystemClock.sleep(500)
                    val moved = capture(window)
                    try {
                        val after = pixel(moved, compose)
                        assertTrue("Moved Compose crop stayed stale: $fromCompose -> $after", Color.red(after) > Color.red(fromCompose))
                        assertTrue(abs(Color.blue(bottom) - Color.blue(after)) < 12)
                    } finally { moved.recycle() }
                    instrumentation.runOnMainSync {
                        assertEquals(1, scope.samplingDiagnostics().attachedSurfaces)
                        assertTrue("Static wallpaper was re-recorded every frame", scope.materialRecordings - recordings < 8)
                        paint.alpha = 0; compose.invalidate()
                    }
                    SystemClock.sleep(200)
                    val transparent = capture(window)
                    try {
                        val raw = pixel(transparent, compose)
                        val position = IntArray(2)
                        instrumentation.runOnMainSync { compose.getLocationInWindow(position) }
                        val clear = transparent.getPixel(transparent.width - 30, position[1] + compose.height / 2)
                        assertTrue("Drawable alpha was not applied: $raw / $clear",
                            abs(Color.red(raw) - Color.red(clear)) <= 2 &&
                                abs(Color.blue(raw) - Color.blue(clear)) <= 2)
                    } finally { transparent.recycle() }
                    instrumentation.runOnMainSync {
                        paint.close()
                        scope.close()
                        assertEquals(0, scope.samplingDiagnostics().attachedSurfaces)
                        assertNull(first.background); assertNull(second.background)
                    }
                } finally { before.recycle() }
            } finally { instrumentation.runOnMainSync { paint.close(); scope.close() } }
        }
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

    private fun save(bitmap: Bitmap, name: String) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "lumen-mica").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
