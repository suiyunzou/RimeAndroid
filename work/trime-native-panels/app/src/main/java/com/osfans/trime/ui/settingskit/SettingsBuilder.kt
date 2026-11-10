/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import com.osfans.trime.R

/**
 * Builds the grouped "iOS settings" list used by the reference pages out of raw [LinearLayout]s.
 *
 * ```kotlin
 * val builder = SettingsBuilder(context, dark = true)
 * scrollView.addView(builder.body)
 * builder.section("实验性功能")
 * val card = builder.group()
 * builder.toggle(card, "滑行输入", glide.enabled) { glide.enabled = it }
 * builder.row(card, "拨动输入", "上拨") { open("keys") }
 * builder.segments(listOf("关闭", "上拨", "下拨"), 0) { choose(it) }
 * builder.note("预览随显示设置即时更新。")
 * ```
 *
 * [parent] arguments may be `null`; the builder then uses the most recently created [group],
 * which keeps page code short when a card is filled sequentially.
 */
class SettingsBuilder(
    context: Context,
    dark: Boolean,
) {
    val palette: Palette = Palette.of(dark)

    private val context: Context = context

    /** Vertical container the page is appended to; host it in a `ScrollView`. */
    val body: LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            setBackgroundColor(palette.background)
        }

    /** Last card created by the builder, used when a call omits its parent. */
    var lastGroup: LinearLayout? = null
        private set

    private fun dp(value: Int): Int = SettingsPalette.dp(context, value)

    private fun rounded(
        color: Int,
        radiusDp: Int = SettingsPalette.RADIUS_GROUP,
    ): GradientDrawable = SettingsKitDrawables.rounded(context, color, radiusDp)

    private fun pressedBackground(
        color: Int? = null,
        radiusDp: Int = SettingsPalette.RADIUS_GROUP,
    ): RippleDrawable {
        val ripple = 0x1F000000 or (palette.ink and 0x00FFFFFF)
        val content = color?.let { rounded(it, radiusDp) }
        return RippleDrawable(ColorStateList.valueOf(ripple), content, null)
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
    ): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
        }

    /** Blank vertical gap between cards; defaults to 16 dp. */
    fun space(heightDp: Int = 16): SettingsBuilder {
        body.addView(
            View(context),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)),
        )
        return this
    }

    /** Light grey group caption, e.g. `section("实验性功能")`. */
    fun section(title: String): TextView {
        val caption =
            label(title, 15f, palette.muted).apply {
                setPadding(dp(16), dp(8), 0, dp(8))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        body.addView(caption)
        return caption
    }

    /** White (or #262628 in dark) rounded card holding rows, toggles and segments. */
    fun group(): LinearLayout {
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(palette.group)
                clipToOutline = true
            }
        body.addView(
            card,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        lastGroup = card
        return card
    }

    private fun cardOf(parent: LinearLayout?): LinearLayout = parent ?: lastGroup ?: group()

    /**
     * Plain row: `title` on the left, optional muted `value` on the right and a ` ›` chevron
     * when [onClick] is provided. With a [summary] the title is followed by a muted second line
     * and the row grows past its 54 dp baseline, which is how the launcher home keeps the short
     * explanations of the old preference list without dropping back to that look.
     */
    fun row(
        parent: LinearLayout?,
        title: String,
        value: String = "",
        summary: String? = null,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val card = cardOf(parent)
        val visual = SettingsKitState.RowVisual.of(value, onClick != null)
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), if (summary == null) 0 else dp(9), dp(14), if (summary == null) 0 else dp(9))
                contentDescription = SettingsKitState.rowDescription(title, value)
                if (visual.tappable) {
                    isClickable = true
                    isFocusable = true
                    background = pressedBackground()
                    setOnClickListener { onClick?.invoke() }
                }
            }
        if (summary == null) {
            row.addView(
                label(title, 18f, palette.ink),
                LinearLayout.LayoutParams(0, dp(SettingsPalette.ROW_HEIGHT_DP), 1f),
            )
        } else {
            val texts =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
            texts.addView(label(title, 18f, palette.ink))
            texts.addView(
                label(summary, 13f, palette.muted).apply { setPadding(0, dp(3), 0, 0) },
            )
            row.addView(
                texts,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        if (visual.hasValue) {
            row.addView(
                label(value, 17f, palette.muted),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    if (summary == null) dp(SettingsPalette.ROW_HEIGHT_DP) else LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (visual.hasChevron) {
            row.addView(
                label(" ›", 24f, palette.chevron).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END },
                LinearLayout.LayoutParams(
                    dp(19),
                    if (summary == null) dp(SettingsPalette.ROW_HEIGHT_DP) else LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        card.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (summary == null) dp(SettingsPalette.ROW_HEIGHT_DP) else LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return row
    }

    /** Row with a trailing [IosSwitchView]; tapping anywhere on the row toggles the switch. */
    fun toggle(
        parent: LinearLayout?,
        title: String,
        value: Boolean,
        summary: String? = null,
        badge: Boolean = false,
        action: (Boolean) -> Unit,
    ): IosSwitchView {
        val row = row(parent, title, summary = summary)
        val switch =
            IosSwitchView(context).apply {
                applyPalette(palette)
                setLabel(title)
                isChecked = value
                setOnCheckedChangeListener { checked -> action(checked) }
            }
        if (badge) {
            row.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_settings_leaf)
                    imageTintList = ColorStateList.valueOf(palette.muted)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) },
            )
        }
        row.addView(
            switch,
            LinearLayout.LayoutParams(
                dp(SettingsPalette.SWITCH_WIDTH_DP),
                dp(SettingsPalette.SWITCH_HEIGHT_DP),
            ).apply { leftMargin = dp(8) },
        )
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { switch.toggle() }
        return switch
    }

    /** Full-width rounded action button, used by list pages such as 本地规则. */
    fun button(
        text: String,
        onClick: () -> Unit,
    ): TextView {
        val button =
            label(text, 17f, 0xFFFFFFFF.toInt()).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = text
                background = rounded(SettingsPalette.SWITCH_ON_COLOR, SettingsPalette.RADIUS_PILL)
                setOnClickListener { onClick() }
            }
        body.addView(
            button,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            },
        )
        return button
    }

    /** Pill segmented control; [selected] is clamped and selecting a segment calls [onSelect]. */
    fun segments(
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): LinearLayout {
        val control = segmentsView(labels, selected, onSelect)
        body.addView(
            control,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36)),
        )
        return control
    }

    /**
     * Builds the same segmented control without attaching it, so a page can place it inside a
     * card or beside other content. [segments] is the `body`-attached shortcut.
     *
     * This exists because [segments] used to attach the control immediately; pages that then
     * called `card.addView(segments(...))` crashed on device with
     * "The specified child already has a parent".
     */
    fun segmentsView(
        labels: List<String>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ): LinearLayout {
        val index = SettingsKitState.selectedSegment(labels, selected)
        val control =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(palette.track, SettingsPalette.RADIUS_PILL)
                setPadding(dp(3), dp(3), dp(3), dp(3))
                contentDescription = SettingsKitState.segmentsDescription(labels, index)
            }
        labels.forEachIndexed { i, segmentLabel ->
            val chosen = i == index
            val item =
                label(segmentLabel, 14f, if (chosen) palette.ink else palette.muted).apply {
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    contentDescription =
                        if (chosen) "$segmentLabel，已选中" else segmentLabel
                    background = if (chosen) rounded(palette.selected, SettingsPalette.RADIUS_SEGMENT) else null
                    if (chosen) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setOnClickListener { onSelect(i) }
                }
            control.addView(item, LinearLayout.LayoutParams(0, dp(30), 1f))
        }
        return control
    }

    /** Small muted footnote under a card. */
    fun note(text: String): TextView {
        val footnote =
            label(text, 12f, palette.note).apply {
                setPadding(dp(14), 0, dp(10), 0)
                gravity = Gravity.START
            }
        body.addView(footnote)
        return footnote
    }

    /** Row whose trailing widget is supplied by the caller (e.g. an inline segmented control). */
    fun rowWithControl(
        parent: LinearLayout?,
        title: String,
        control: View,
        onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val card = cardOf(parent)
        val row =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(14), 0)
                contentDescription = title
                if (onClick != null) {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onClick() }
                }
            }
        row.addView(
            label(title, 18f, palette.ink),
            LinearLayout.LayoutParams(0, dp(SettingsPalette.ROW_HEIGHT_DP), 1f),
        )
        row.addView(control)
        card.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(SettingsPalette.ROW_HEIGHT_DP),
            ),
        )
        return row
    }

    companion object {
        /** Section caption colour of [dark] pages; exposed so callers can tint custom captions. */
        @ColorInt
        fun sectionColor(dark: Boolean): Int = Palette.of(dark).muted
    }
}
