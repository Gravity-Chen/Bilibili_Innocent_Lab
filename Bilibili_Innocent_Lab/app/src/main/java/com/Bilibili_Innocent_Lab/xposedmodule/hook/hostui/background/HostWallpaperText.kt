package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap

/** 壁纸要求深色前景时，修正宿主仍按浅色主题提供的中性文字，保留主次和业务颜色。 */
internal class HostWallpaperText {
    private class Colors(val original: ColorStateList, val applied: ColorStateList)
    private val changed = WeakHashMap<TextView, Colors>()

    fun apply(view: TextView, page: ViewGroup) {
        val saved = changed[view]
        if (saved != null && view.textColors !== saved.applied) changed.remove(view)
        val colors = changed[view]?.original ?: view.textColors
        val color = colors.defaultColor
        if (!view.isEnabled || colors.isStateful || !HostBackgroundPagePolicy.neutral(color) || (color and 255) > 160) {
            restore(view); return
        }
        // 浅色按钮、彩色面板上的文字继续使用宿主配色，不触及封面、播放器等保护区域。
        var node: View? = view
        while (node != null && node !== page) {
            val solid = when (val drawable = node.background) {
                is ColorDrawable -> drawable.color
                is GradientDrawable -> drawable.color?.defaultColor
                else -> null
            }
            if (solid != null && solid ushr 24 != 0 &&
                (!HostBackgroundPagePolicy.neutral(solid) || (solid and 255) > 160)) {
                restore(view); return
            }
            node = node.parent as? View
        }
        val target = if ((color and 255) <= 95) 0xffe3e5e7.toInt() else 0xffb8bcc6.toInt()
        if (view.currentTextColor == target) return
        val applied = ColorStateList.valueOf(target)
        changed[view] = Colors(colors, applied)
        view.setTextColor(applied)
    }

    private fun restore(view: TextView) {
        val colors = changed.remove(view) ?: return
        if (view.textColors === colors.applied) view.setTextColor(colors.original)
    }

    fun restore() {
        for (view in changed.keys.toList()) restore(view)
    }
}
