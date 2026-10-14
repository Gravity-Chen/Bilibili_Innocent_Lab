package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostDockLayer
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostGlowView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeArtwork
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import android.widget.TextView

/** 宿主刷新标签样式时会重新设置点击背景；在同一帧绘制前清理，避免矩形底色闪过。 */
internal class HostTopBarArtifactBinding(private val dock: ViewGroup) {
    private data class Node(val view: View, val parent: Any?, val childCount: Int, val backdrop: Boolean, val title: Boolean)

    private val nodes = ArrayList<Node>()

    private fun collect(view: View) {
        if (view is HostDockLayer || view is HostGlowView ||
            HostTopBarFxController.isUnderlineIndicator(view)) return
        val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
        nodes += Node(view, view.parent, (view as? ViewGroup)?.childCount ?: 0,
            HostChromeArtwork.isBackdrop(name), name == "tab_title")
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
    }

    fun clear(colors: HostChromeColors? = null) {
        // 稳定布局只检查缓存的 View 引用，不逐帧解析资源名。新增、替换或重建标签后重收集。
        if (nodes.isEmpty() || nodes.any {
                it.view.parent !== it.parent || (it.view as? ViewGroup)?.childCount?.let { count ->
                    count != it.childCount
                } == true
            }) {
            nodes.clear()
            collect(dock)
        }
        for (node in nodes) {
            val view = node.view
            if (node.backdrop) { HostChromeArtwork.clear(view); continue }
            if (view !== dock && view.background != null) view.background = null
            if (view.foreground != null) view.foreground = null
            if (node.title && view is TextView && colors?.garbAccent != null) {
                val palette = colors.palette()
                val selected = view.isSelected || (view.parent as? View)?.isSelected == true
                val color = if (selected) palette.primary else palette.textPrimary
                if (view.currentTextColor != color) view.setTextColor(color)
            }
            // 原生 Tab 在 ACTION_UP 根据 isPressed 触发点击，不能像底栏自有手势那样清掉它。
        }
    }
}
