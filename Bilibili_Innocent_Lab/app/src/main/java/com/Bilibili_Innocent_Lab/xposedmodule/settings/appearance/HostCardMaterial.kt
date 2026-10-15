package com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences

internal enum class HostCardMaterial(val value: String) {
    ORIGINAL("original"), ACRYLIC("acrylic"), MICA("mica");

    val usesSharedField get() = this != ORIGINAL

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.value == value } ?: ORIGINAL

        /** 新键存在时始终优先；只有缺少新键才继承旧开关，非法值安全退回原版。 */
        fun read(value: Any?, legacy: Any?) = if (value == null) {
            if (legacy == true) ACRYLIC else ORIGINAL
        } else parse(value as? String)

        fun resolve(values: Map<String, *>) = read(values[FeaturePreferences.HOST_CARD_MATERIAL],
            values[FeaturePreferences.HOST_CARD_ACRYLIC])
    }
}
