package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.view.View
import android.widget.ImageView

/** 只移除导航容器的装饰底图，不能把角色图标、角标或发布动画当作背景。 */
internal object HostChromeArtwork {
    fun isBackdrop(name: String): Boolean = name == "tab_background" || name == "tabs_bg_view" ||
        name == "tabs_bg" || name == "main_top_bg"

    fun clear(view: View) {
        if (view.visibility != View.GONE) view.visibility = View.GONE
        if (view.alpha != 0f) view.alpha = 0f
        if (view.background != null) view.background = null
        if (view.foreground != null) view.foreground = null
        (view as? ImageView)?.let { if (it.drawable != null) it.setImageDrawable(null) }
    }
}
