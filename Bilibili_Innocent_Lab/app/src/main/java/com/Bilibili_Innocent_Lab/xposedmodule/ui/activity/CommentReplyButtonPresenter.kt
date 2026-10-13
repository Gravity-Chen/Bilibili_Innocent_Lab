package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.util.Log
import android.widget.LinearLayout as NativeLinearLayout
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.TextView
import com.highcapable.hikage.widget.com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch

@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.commentReplyButtonSettingsRows(
    performer: Hikage.Performer<NativeLinearLayout.LayoutParams>,
    checked: Boolean,
    onChanged: (Boolean) -> Unit
) {
    with(performer) {
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, directToggle = true)
            bindSettingDestination(this, FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON)
            text = stringResource(R.string.remove_comment_reply_button)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked ->
                onChanged(isChecked)
                runCatching {
                    prefs().edit { putBoolean(FeaturePreferences.REMOVE_COMMENT_REPLY_BUTTON, isChecked) }
                }.onFailure { throwable ->
                    Log.e("BilibiliInnocentLab", "write comment reply button prefs failed", throwable)
                }
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.remove_comment_reply_button_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}
