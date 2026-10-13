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

/** 评论回复入口设置的无状态分卷；状态与偏好写入仍属于当前 Activity。 */
@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.commentReplyButtonSetting(builder: Hikage.Performer<NativeLinearLayout.LayoutParams>) {
    with(builder) {
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HIDE_COMMENT_REPLY_BUTTON, directToggle = true)
            bindSettingDestination(this, FeaturePreferences.HIDE_COMMENT_REPLY_BUTTON)
            text = stringResource(R.string.hide_comment_reply_button)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hideCommentReplyButton
            setOnCheckedChangeListener { _, isChecked ->
                hideCommentReplyButton = isChecked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HIDE_COMMENT_REPLY_BUTTON,
                            isChecked
                        )
                    }
                }.onFailure { t ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write comment reply button prefs failed",
                        t
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.hide_comment_reply_button_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}
