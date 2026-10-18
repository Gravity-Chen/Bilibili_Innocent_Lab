package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.Color
import androidx.core.graphics.drawable.toDrawable
import android.view.View
import android.view.ViewGroup
import com.lumen.coacervation.engine.host.LumenPrismGlass
import com.lumen.coacervation.engine.host.LumenPrismOptions
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.roundToInt

/** 独立真实 View 持有棱镜；缩岛改变采样几何，外阴影不受宿主自己的 Outline 裁剪。 */
internal class HostPrismSurface(
    private val host: View,
    palette: LumenPalette,
    session: LumenSurfaceSession,
    source: View?
) : AutoCloseable {
    private class ClipLease(val original: Boolean, var users: Int = 0)
    private class PrismView(context: android.content.Context, val clipping: ClipLease) : View(context), HostDockLayer
    private val original = host.background
    private val marker = Color.TRANSPARENT.toDrawable()
    private val parent = host.parent as ViewGroup
    // Share clipping ownership with other Prism siblings without a process-level View cache.
    private val clipping = (0 until parent.childCount).asSequence()
        .mapNotNull { parent.getChildAt(it) as? PrismView }.firstOrNull()?.clipping ?: ClipLease(parent.clipChildren)
    private val layer = PrismView(host.context, clipping)
    private val glass: LumenPrismGlass
    private var closed = false

    init {
        // Let the host parent create its own LayoutParams (including host ConstraintLayout).
        parent.addView(layer, parent.indexOfChild(host))
        clipping.users++
        parent.clipChildren = false
        layer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        host.background = marker
        glass = LumenPrismGlass(layer, palette, LumenPrismOptions(), session, source)
    }

    val owns: Boolean get() = !closed && host.parent === parent && layer.parent === parent && host.background === marker

    fun sync(insetX: Float, insetY: Float) {
        if (closed) return
        val x = insetX.coerceIn(0f, (host.width - 1).coerceAtLeast(0) / 2f).roundToInt()
        val y = insetY.coerceIn(0f, (host.height - 1).coerceAtLeast(0) / 2f).roundToInt()
        val width = (host.width - x * 2).coerceAtLeast(1)
        val height = (host.height - y * 2).coerceAtLeast(1)
        if (layer.measuredWidth != width || layer.measuredHeight != height)
            layer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        layer.layout(host.left + x, host.top + y, host.left + x + width, host.top + y + height)
        layer.pivotX = host.pivotX - x
        layer.pivotY = host.pivotY - y
        layer.translationX = host.translationX
        layer.translationY = host.translationY
        layer.translationZ = host.z
        layer.scaleX = host.scaleX
        layer.scaleY = host.scaleY
        layer.rotation = host.rotation
        layer.rotationX = host.rotationX
        layer.rotationY = host.rotationY
        layer.alpha = host.alpha
        layer.visibility = if (host.isShown && host.width > 0 && host.height > 0) View.VISIBLE else View.INVISIBLE
    }

    fun updatePalette(palette: LumenPalette) = glass.updatePalette(palette)
    fun notifyPositionChanged() = glass.notifyPositionChanged()
    fun diagnostics() = glass.diagnostics()

    override fun close() {
        if (closed) return
        closed = true
        glass.close()
        parent.removeView(layer)
        if (--clipping.users == 0 && !parent.clipChildren) parent.clipChildren = clipping.original
        if (host.background === marker) host.background = original
    }
}
