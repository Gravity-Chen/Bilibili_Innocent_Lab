package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.SharedPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig

/** commit 失败也可能已改写偏好内存；取消导入资产前必须恢复本次保存触及的配置。 */
internal object HostBackgroundSettingsStore {
    private val keys = listOf(
        FeaturePreferences.HOST_BACKGROUND_PRESET, FeaturePreferences.HOST_BACKGROUND_BLUR,
        FeaturePreferences.HOST_BACKGROUND_SATURATION, FeaturePreferences.HOST_BACKGROUND_VEIL,
        FeaturePreferences.HOST_BACKGROUND_ASSET
    )

    fun save(preferences: SharedPreferences, draft: HostBackgroundConfig): Boolean {
        val before = preferences.all.filterKeys { it in keys }
        val config = draft.normalized()
        val committed = runCatching {
            preferences.edit().putString(FeaturePreferences.HOST_BACKGROUND_PRESET, config.preset.value)
                .putInt(FeaturePreferences.HOST_BACKGROUND_BLUR, config.blur)
                .putInt(FeaturePreferences.HOST_BACKGROUND_SATURATION, config.saturation)
                .putInt(FeaturePreferences.HOST_BACKGROUND_VEIL, config.veil)
                .putString(FeaturePreferences.HOST_BACKGROUND_ASSET, config.asset).commit()
        }.getOrDefault(false)
        if (!committed) runCatching {
            val rollback = preferences.edit()
            for (key in keys) when (val value = before[key]) {
                is String -> rollback.putString(key, value)
                is Int -> rollback.putInt(key, value)
                else -> rollback.remove(key)
            }
            rollback.commit()
        }
        return committed
    }
}
