package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.model.LumenPalette
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGarbColors

/**
 * 卡片的静态配色与页面级共享亚克力入口，View / Compose / 动态使用相同材质场。
 */
internal object HostVideoCardSurface {
    private val light = LumenPalette.neutral(false)
    private val dark = LumenPalette.neutral(true)

    fun color(night: Boolean, context: Context? = null): Int = palette(night, context).surface

    internal class Scope(list: ViewGroup, night: Boolean, context: Context = list.context,
        material: HostCardMaterial = HostCardMaterial.ACRYLIC) : HostCardMaterialScope(list, night, context, material)

    fun create(view: View, radiusPx: Float, night: Boolean): Drawable? {
        val activity = activity(view.context) ?: return null
        val palette = palette(night, view.context)
        // 未 prepare 的表面 API 返回引擎提供的等价静态材质，无会话需要托管生命周期。
        return LumenActivityDelegate(activity, paletteProvider = { palette }).cardBackground(
            color = palette.surface,
            radiusDp = radiusPx / view.resources.displayMetrics.density)
    }

    internal fun palette(night: Boolean, context: Context?): LumenPalette {
        val base = if (night) dark else light
        val tint = context?.let(HostGarbTheme::accent) ?: return base
        return base.copy(surface = HostGarbColors.blend(base.surface, tint, .07f))
    }

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(10) {
            if (current is Activity) return current
            val wrapper = current as? ContextWrapper ?: return null
            val base = wrapper.baseContext
            if (base === current) return null
            current = base
        }
        return null
    }
}
