package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup

/** Theseus 浮层槽位是独立覆盖区域，不能按正文递归清底色、接入主列表或删除 Compose 填色。 */
internal object HostDetailFloatLayers {
    fun tabSlot(view: View): Boolean = HostViewResourceNames.name(view) in tabSlots

    fun slot(view: View): Boolean = tabSlot(view) || HostViewResourceNames.name(view) == "fl_root_container"

    fun contains(view: View): Boolean {
        var node: View? = view
        while (node != null) {
            if (slot(node)) return true
            node = node.parent as? View
        }
        return false
    }

    fun surface(view: View): Boolean {
        val parent = view.parent as? ViewGroup ?: return false
        if (view !is ViewGroup || !tabSlot(parent) || HostBackgroundPages.protected(view)) return false
        if (view.background is HostDetailOverlay) return true
        // 只替换浮层根的整块不透明中性底板。透明包装、半透明遮罩和带圆角的 sheet 保留宿主材质。
        val color = (view.background as? ColorDrawable)?.color ?: return false
        return color ushr 24 == 255 && HostBackgroundPagePolicy.neutral(color) &&
            parent.width > 0 && parent.height > 0 && view.width >= parent.width * .9f &&
            view.height >= parent.height * .9f
    }

    private val tabSlots = setOf("fl_tab_pager_container", "fl_tab_content_container")
}
