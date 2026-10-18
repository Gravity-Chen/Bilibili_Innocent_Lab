@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.DatePickerDialog
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PublishTimeMode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PublishTimePoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PublishTimeUnit
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.VideoPublishTimeRule
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import com.highcapable.betterandroid.ui.extension.view.textColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import android.widget.EditText as NativeEditText
import android.widget.HorizontalScrollView as NativeHorizontalScrollView
import android.widget.LinearLayout as NativeLinearLayout
import android.widget.TextView as NativeTextView

/** 发布时间面板里一个时间节点的草稿；确认前不落盘。 */
private class PublishTimePointDraft(point: PublishTimePoint?) {
    var relative: Boolean = point is PublishTimePoint.Relative
    var date: LocalDate? = (point as? PublishTimePoint.Absolute)?.date
    var amountText: String = (point as? PublishTimePoint.Relative)?.amount?.toString().orEmpty()
    var unit: PublishTimeUnit = (point as? PublishTimePoint.Relative)?.unit ?: PublishTimeUnit.DAY

    /** null 表示草稿还不完整；不抛异常，由调用方决定提示哪一条。 */
    fun toPoint(): PublishTimePoint? = if (relative) {
        amountText.trim().toIntOrNull()
            ?.takeIf { it in 1..PublishTimePoint.MAX_RELATIVE_AMOUNT }
            ?.let { PublishTimePoint.Relative(it, unit) }
    } else {
        // 越界年份由 Absolute 自己拒绝；这里吞成"不完整"，交给调用方提示。
        date?.let { runCatching { PublishTimePoint.Absolute(it) }.getOrNull() }
    }
}

internal fun MainActivity.showRecommendVideoPublishTimeDialog(anchor: View? = null) {
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    val saved = VideoPublishTimeRule.parse(recommendVideoPublishTimeRule)
    var mode: PublishTimeMode? = saved.mode
    val drafts = listOf(PublishTimePointDraft(saved.first), PublishTimePointDraft(saved.second))
    fun dp(value: Int): Int = (value * density).toInt()
    // 模式、时间节点与预览放进限高滚动区，小屏上按钮行始终可见。
    val body = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }

    container.addView(
        NativeTextView(this).apply {
            text = getString(R.string.recommend_video_publish_time_range)
            textColor = getColor(R.color.colorTextDark)
            textSize = 17f
            setLineSpacing(4 * density, 1f)
        },
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )

    fun styleChoice(view: NativeTextView, selected: Boolean) {
        view.textColor = if (selected) monetColors.primary else getColor(R.color.colorTextDark)
        view.typeface = Typeface.create(
            Typeface.DEFAULT,
            if (selected) Typeface.BOLD else Typeface.NORMAL
        )
        view.background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(
                if (selected) {
                    ColorUtils.setAlphaComponent(monetColors.primary, 0x24)
                } else {
                    monetColors.surfaceVariant
                }
            )
            if (selected) setStroke(density.toInt().coerceAtLeast(1), monetColors.primary)
        }
        view.isSelected = selected
    }

    fun choiceView(label: String, compact: Boolean = false): NativeTextView =
        NativeTextView(this).apply {
            text = label
            textSize = if (compact) 13f else 14f
            gravity = if (compact) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(
                dp(if (compact) 12 else 14),
                dp(if (compact) 8 else 10),
                dp(if (compact) 12 else 14),
                dp(if (compact) 8 else 10)
            )
            isClickable = true
            isFocusable = true
        }

    val modeOptions = listOf(
        null to R.string.recommend_video_publish_time_mode_off,
        PublishTimeMode.BEFORE to R.string.recommend_video_publish_time_mode_before,
        PublishTimeMode.AFTER to R.string.recommend_video_publish_time_mode_after,
        PublishTimeMode.BETWEEN to R.string.recommend_video_publish_time_mode_between,
        PublishTimeMode.OUTSIDE to R.string.recommend_video_publish_time_mode_outside
    )
    val modeViews = modeOptions.map { (option, labelRes) ->
        option to choiceView(getString(labelRes)).also { view ->
            body.addView(
                view,
                NativeLinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            )
        }
    }

    val previewView = NativeTextView(this).apply {
        textColor = getColor(R.color.colorTextDark)
        textSize = 13f
        setLineSpacing(4 * density, 1f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(monetColors.surfaceVariant)
        }
    }
    val errorView = NativeTextView(this).apply {
        visibility = View.GONE
        textColor = if (ColorUtils.calculateLuminance(monetColors.surface) < 0.5) {
            0xFFFFB4AB.toInt()
        } else {
            0xFFBA1A1A.toInt()
        }
        textSize = 12f
        setLineSpacing(4 * density, 1f)
    }

    /** 当前草稿能否组成一条规则；不完整或倒置时返回 null。 */
    fun draftRule(): VideoPublishTimeRule? {
        val selected = mode ?: return VideoPublishTimeRule.DISABLED
        val first = drafts[0].toPoint() ?: return null
        val second = if (selected.pointCount == 2) drafts[1].toPoint() ?: return null else null
        return runCatching { VideoPublishTimeRule.of(selected, first, second) }.getOrNull()
    }

    fun refreshPreview() {
        val rule = draftRule()
        previewView.text = getString(R.string.recommend_video_publish_time_preview_label) + "\n" +
            when {
                rule == null -> "—"
                else -> recommendVideoPublishTimeSummary(rule, Instant.now())
            }
    }

    val pointSections = drafts.mapIndexed { index, draft ->
        val section = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
        val label = NativeTextView(this).apply {
            textColor = getColor(R.color.colorTextGray)
            textSize = 13f
        }
        section.addView(label)

        val kindRow = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL }
        val dateKind = choiceView(getString(R.string.recommend_video_publish_time_kind_date), compact = true)
        val relativeKind = choiceView(
            getString(R.string.recommend_video_publish_time_kind_relative),
            compact = true
        )
        kindRow.addView(dateKind, NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        kindRow.addView(
            relativeKind,
            NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
        )
        section.addView(
            kindRow,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        val dateButton = choiceView("").apply {
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(monetColors.surfaceVariant)
                setStroke(
                    density.toInt().coerceAtLeast(1),
                    ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x38)
                )
            }
        }
        section.addView(
            dateButton,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        val relativeGroup = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.VERTICAL }
        val amountEditor = NativeEditText(this).apply {
            setText(draft.amountText)
            hint = getString(R.string.recommend_video_publish_time_amount_hint)
            textColor = getColor(R.color.colorTextDark)
            setHintTextColor(ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x99))
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(4))
            contentDescription = getString(R.string.recommend_video_publish_time_amount_hint)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = 14 * density
                setColor(monetColors.surfaceVariant)
                setStroke(
                    density.toInt().coerceAtLeast(1),
                    ColorUtils.setAlphaComponent(getColor(R.color.colorTextGray), 0x38)
                )
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    draft.amountText = s?.toString().orEmpty()
                    refreshPreview()
                }
            })
        }
        relativeGroup.addView(
            amountEditor,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        val unitRow = NativeLinearLayout(this).apply { orientation = NativeLinearLayout.HORIZONTAL }
        val unitLabels = mapOf(
            PublishTimeUnit.HOUR to R.string.recommend_video_publish_time_unit_hour,
            PublishTimeUnit.DAY to R.string.recommend_video_publish_time_unit_day,
            PublishTimeUnit.WEEK to R.string.recommend_video_publish_time_unit_week,
            PublishTimeUnit.MONTH to R.string.recommend_video_publish_time_unit_month,
            PublishTimeUnit.YEAR to R.string.recommend_video_publish_time_unit_year
        )
        val unitViews = unitLabels.map { (unit, labelRes) ->
            unit to choiceView(getString(labelRes), compact = true).also { view ->
                unitRow.addView(
                    view,
                    NativeLinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { if (unitRow.childCount > 0) marginStart = dp(6) }
                )
            }
        }
        relativeGroup.addView(
            NativeHorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(unitRow)
            },
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        section.addView(relativeGroup)

        fun render() {
            label.text = getString(
                when {
                    mode?.pointCount != 2 -> R.string.recommend_video_publish_time_point_single
                    index == 0 -> R.string.recommend_video_publish_time_point_start
                    else -> R.string.recommend_video_publish_time_point_end
                }
            )
            styleChoice(dateKind, !draft.relative)
            styleChoice(relativeKind, draft.relative)
            dateButton.visibility = if (draft.relative) View.GONE else View.VISIBLE
            relativeGroup.visibility = if (draft.relative) View.VISIBLE else View.GONE
            dateButton.text = draft.date?.toString()
                ?: getString(R.string.recommend_video_publish_time_pick_date)
            dateButton.textColor = if (draft.date == null) {
                getColor(R.color.colorTextGray)
            } else {
                getColor(R.color.colorTextDark)
            }
            unitViews.forEach { (unit, view) -> styleChoice(view, unit == draft.unit) }
        }

        dateKind.setOnClickListener {
            draft.relative = false
            render()
            refreshPreview()
        }
        relativeKind.setOnClickListener {
            draft.relative = true
            render()
            refreshPreview()
        }
        unitViews.forEach { (unit, view) ->
            view.setOnClickListener {
                draft.unit = unit
                render()
                refreshPreview()
            }
        }
        dateButton.setOnClickListener {
            val initial = draft.date ?: LocalDate.now(ZoneId.systemDefault())
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    draft.date = LocalDate.of(year, month + 1, dayOfMonth)
                    render()
                    refreshPreview()
                },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth
            ).apply {
                // 与 PublishTimePoint 的可解析年份一致，否则选得出、存得进、却读不回来。
                val zone = ZoneId.systemDefault()
                datePicker.minDate = LocalDate.of(PublishTimePoint.MIN_YEAR, 1, 1)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
                datePicker.maxDate = LocalDate.of(PublishTimePoint.MAX_YEAR, 12, 31)
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            }.show()
        }
        render()
        body.addView(
            section,
            NativeLinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        )
        section to { render() }
    }

    fun renderMode() {
        modeViews.forEach { (option, view) -> styleChoice(view, option == mode) }
        pointSections[0].first.visibility = if (mode == null) View.GONE else View.VISIBLE
        pointSections[1].first.visibility = if (mode?.pointCount == 2) View.VISIBLE else View.GONE
        pointSections.forEach { (_, render) -> render() }
        errorView.visibility = View.GONE
        refreshPreview()
    }
    modeViews.forEach { (option, view) ->
        view.setOnClickListener {
            mode = option
            renderMode()
        }
    }

    body.addView(
        previewView,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
    )
    body.addView(
        errorView,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    )
    renderMode()
    val contentCap = minOf(dp(520), (resources.displayMetrics.heightPixels * 0.6f).toInt())
    container.addView(
        MaxHeightScrollView(this, contentCap).apply {
            addView(body, FrameLayout.LayoutParams(-1, -2))
        },
        NativeLinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    )

    fun showError(@StringRes messageRes: Int) {
        errorView.text = getString(messageRes)
        errorView.visibility = View.VISIBLE
        errorView.announceForAccessibility(errorView.text)
    }

    /** 逐项校验；返回 null 表示已经展示了错误。 */
    fun validatedRule(): VideoPublishTimeRule? {
        val selected = mode ?: return VideoPublishTimeRule.DISABLED
        val needed = drafts.take(selected.pointCount)
        needed.forEach { draft ->
            if (draft.relative && draft.toPoint() == null) {
                showError(R.string.recommend_video_publish_time_invalid_amount)
                return null
            }
            if (!draft.relative && draft.toPoint() == null) {
                showError(R.string.recommend_video_publish_time_invalid_date)
                return null
            }
        }
        val rule = draftRule()
        // 混合"日期 + 相对"的区间只能按当前时刻判断先后；此刻倒置的规则不允许保存。
        if (rule == null || rule.resolveBounds(Instant.now(), ZoneId.systemDefault()) == null) {
            showError(R.string.recommend_video_publish_time_invalid_range)
            return null
        }
        return rule
    }

    val buttonRow = NativeLinearLayout(this).apply {
        orientation = NativeLinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    val cancelButton = createTermsActionButton(getString(R.string.dialog_cancel), filled = false) {
        dismissWithAnimation(dialog, container) {}
    }
    val confirmButton = createTermsActionButton(getString(R.string.dialog_confirm), filled = true) {
        errorView.visibility = View.GONE
        val rule = validatedRule() ?: return@createTermsActionButton
        val encoded = rule.encode()
        recommendVideoPublishTimeRule = encoded
        runCatching {
            prefs().edit {
                putString(FeaturePreferences.RECOMMEND_VIDEO_PUBLISH_TIME_RULE, encoded)
            }
        }.onFailure { throwable ->
            Log.e(
                "BilibiliInnocentLab",
                "write recommended video publish time prefs failed",
                throwable
            )
        }
        updateRecommendVideoPublishTimeSummary()
        dismissWithAnimation(dialog, container) {}
    }
    buttonRow.addView(
        cancelButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    )
    buttonRow.addView(
        confirmButton,
        NativeLinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(8) }
    )
    container.addView(
        buttonRow,
        NativeLinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) }
    )
    presentModalDialog(dialog, container, anchor)
}

internal fun MainActivity.updateRecommendVideoPublishTimeSummary() {
    recommendVideoPublishTimeSummaryView?.text =
        getString(R.string.recommend_video_publish_time_range) + "\n" +
            recommendVideoPublishTimeSummary()
}
