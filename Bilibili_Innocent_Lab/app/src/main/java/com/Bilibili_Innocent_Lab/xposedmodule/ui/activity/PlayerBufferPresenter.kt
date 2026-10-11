package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.widget.LinearLayout as NativeLinearLayout
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerBufferMode
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.updatePadding
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.android.widget.TextView

internal fun MainActivity.playerBufferModeLabel(value: Int): String = getString(when (PlayerBufferMode.fromValue(value)) {
    PlayerBufferMode.FOLLOW_HOST -> R.string.player_buffer_follow_host
    PlayerBufferMode.LOW -> R.string.player_buffer_low
    PlayerBufferMode.CUSTOM -> R.string.player_buffer_custom
    PlayerBufferMode.WHOLE -> R.string.player_buffer_whole
})

internal fun MainActivity.updatePlayerBufferSummary() {
    playerBufferSummaryView?.text = if (PlayerBufferMode.fromValue(playerBufferMode) == PlayerBufferMode.CUSTOM) {
        getString(R.string.player_buffer_custom_current, playerBufferSeconds, playerBufferMemoryMb)
    } else getString(R.string.player_buffer_current, playerBufferModeLabel(playerBufferMode))
}

/** 共用一行导航入口，草稿和生命周期仍由当前 MainActivity 的模态底座持有。 */
@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.playerBufferSettingsRows(performer: Hikage.Performer<NativeLinearLayout.LayoutParams>) {
    with(performer) {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) { topMargin = 12.dp },
            init = {
                orientation = NativeLinearLayout.VERTICAL
                background = selfRippleBackground(10f)
                updatePadding(horizontal = 0.dp, vertical = 9.dp)
                isClickable = true
                isFocusable = true
                setOnClickListener { showPlayerBufferDialog(anchor = it) }
                listOf(
                    FeaturePreferences.PLAYER_BUFFER_MODE, FeaturePreferences.PLAYER_BUFFER_SECONDS,
                    FeaturePreferences.PLAYER_BUFFER_MEMORY_MB, FeaturePreferences.PLAYER_BUFFER_DISK_MB,
                    FeaturePreferences.PLAYER_BUFFER_WIFI_ONLY
                ).forEach { key -> bindSettingDestination(this, key) }
            }
        ) {
            TextView(lparams = LayoutParams(widthMatchParent = true)) {
                text = stringResource(R.string.player_buffer_strategy)
                textColor = colorResource(R.color.colorTextGray)
                textSize = 15f
            }
            TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 5.dp }) {
                alpha = 0.6f
                textColor = colorResource(R.color.colorTextDark)
                textSize = 12f
                playerBufferSummaryView = this
                updatePlayerBufferSummary()
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.player_buffer_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}
