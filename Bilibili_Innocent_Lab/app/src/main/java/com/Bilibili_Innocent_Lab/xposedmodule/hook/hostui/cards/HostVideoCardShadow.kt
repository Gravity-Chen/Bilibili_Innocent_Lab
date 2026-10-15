package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.view.View
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion

/** 关闭亚克力时保留原有轻量平台阴影。 */
internal object HostVideoCardShadow {
    fun apply(view: View) {
        view.elevation = 2f * view.resources.displayMetrics.density
        if (AndroidVersion.isAtLeast(AndroidVersion.P)) {
            view.outlineAmbientShadowColor = 0x16707070
            view.outlineSpotShadowColor = 0x16707070
        }
    }
}
