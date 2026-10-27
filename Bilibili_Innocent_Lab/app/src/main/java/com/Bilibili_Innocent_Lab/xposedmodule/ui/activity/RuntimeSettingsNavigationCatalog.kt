package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.settings.backup.SettingsCatalog

/** Navigation only: these controllers retain their own persistence, capability and consent authority. */
internal object RuntimeSettingsNavigationCatalog {
    const val ADVANCED_MATERIAL = "runtime.ui.advanced_material"
    const val MATERIAL_COLOR_SPEC = SettingsCatalog.ID_MATERIAL_COLOR_SPEC
    const val MODAL_BACKDROP_BLUR = "runtime.ui.modal_backdrop_blur"
    const val LAUNCHER_ICON = "runtime.ui.launcher_icon"
    const val COMMUNICATION_COMPATIBILITY = "runtime.ui.communication_compatibility"
    const val BILI_ACCESS_KEY = SettingsCatalog.ID_BILI_ACCESS_KEY_AUTHORIZED
    const val NO_ROOT_SUPPORT = "runtime.ui.no_root_support"

    fun descriptors(): List<SettingDescriptor> = listOf(
        entry(ADVANCED_MATERIAL, R.string.advanced_material_title, "APPEARANCE"),
        entry(MATERIAL_COLOR_SPEC, R.string.material_color_spec_title, "APPEARANCE"),
        entry(MODAL_BACKDROP_BLUR, R.string.panel_window_blur_title, "APPEARANCE"),
        entry(LAUNCHER_ICON, R.string.hide_app_icon_on_launcher, "APPEARANCE"),
        entry(COMMUNICATION_COMPATIBILITY, R.string.communication_compatibility_mode, "COMPATIBILITY"),
        entry(BILI_ACCESS_KEY, R.string.bili_access_key_authorize, "COMPATIBILITY"),
        entry(NO_ROOT_SUPPORT, R.string.no_root_support_enable, "COMPATIBILITY"),
    )

    private fun entry(id: String, title: Int, section: String) = SettingDescriptor(
        id, "navigation:$id", title, 4, section, directToggle = false, favoriteEligible = false,
    )
}
