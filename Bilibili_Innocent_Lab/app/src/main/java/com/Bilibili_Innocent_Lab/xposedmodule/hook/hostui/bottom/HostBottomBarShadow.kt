package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.lumen.coacervation.engine.host.LumenControlShadow
import com.lumen.coacervation.engine.host.LumenControlShadowOptions

/** 在父级 overlay 绘制外侧阴影，避开胶囊自身的圆角裁剪与采样背景所有权。 */
internal class HostBottomBarShadow(private val host: View, private val radiusDp: Float) {
    private var owner: ViewGroup? = null
    private var dark: Boolean? = null
    private var shadow: Drawable? = null
    private val transform = Matrix()
    private val layer = object : View(host.context) {
        override fun onDraw(canvas: Canvas) {
            val parent = owner ?: return
            val checkpoint = canvas.save()
            canvas.translate((host.left - parent.scrollX).toFloat(), (host.top - parent.scrollY).toFloat())
            canvas.concat(transform)
            shadow?.draw(canvas)
            canvas.restoreToCount(checkpoint)
        }
    }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var left = Int.MIN_VALUE
    private var top = Int.MIN_VALUE
    private var scrollX = 0
    private var scrollY = 0

    fun sync(isDark: Boolean) {
        val parent = host.parent as? ViewGroup ?: return detach()
        var changed = false
        if (owner !== parent) {
            detach()
            owner = parent
            parent.overlay.add(layer)
            changed = true
        }
        if (dark != isDark) {
            dark = isDark
            // 深底已有明亮描边；黑色投影无助于分界，保留干净的深色玻璃。
            shadow = if (isDark) null else LumenControlShadow.shadowOnly(radiusDp,
                host.resources.displayMetrics.density, OPTIONS, dark = false)
            changed = true
        }
        if (layer.width != parent.width || layer.height != parent.height) {
            layer.layout(0, 0, parent.width, parent.height)
            changed = true
        }
        val drawable = shadow
        if (drawable != null && (drawable.bounds.width() != host.width || drawable.bounds.height() != host.height)) {
            drawable.setBounds(0, 0, host.width, host.height)
            changed = true
        }
        if (transform != host.matrix || left != host.left || top != host.top ||
            scrollX != parent.scrollX || scrollY != parent.scrollY) {
            transform.set(host.matrix)
            left = host.left; top = host.top
            scrollX = parent.scrollX; scrollY = parent.scrollY
            changed = true
        }
        layer.visibility = if (!isDark && host.isShown) View.VISIBLE else View.INVISIBLE
        layer.alpha = host.alpha
        if (changed) layer.invalidate()
    }

    fun detach() {
        owner?.overlay?.remove(layer)
        owner = null
        dark = null
        shadow = null
    }

    private companion object {
        val OPTIONS = LumenControlShadowOptions(enabled = true, blurRadiusDp = 6f,
            offsetYDp = 2f, opacity = .12f, contactOpacity = .04f, adaptToTheme = false)
    }
}
