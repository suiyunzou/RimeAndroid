/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.schema

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.ui.settingskit.Palette
import com.osfans.trime.ui.settingskit.SettingsKitDrawables
import com.osfans.trime.ui.settingskit.SettingsPalette
import splitties.views.dsl.core.Ui

/**
 * 「方案」列表的一行：白色圆角卡片，上行为方案名，下行为方案 id。
 *
 * 卡片左右各留 16dp 外边距、22dp 圆角，与参考图的 iOS 分组卡片保持一致；
 * 多选模式的勾选框与排序手柄都画在这张卡片里，不依赖 res/layout。
 */
class SchemaListEntryUi(
    override val ctx: Context,
) : Ui {
    /** 由 adapter 在绑定条目时刷新，跟随应用内设置的日夜模式。 */
    var palette: Palette = Palette.of(ctx)
        set(value) {
            field = value
            applyPalette(value)
        }

    private val card =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
            minimumHeight = dp(60)
            clipToOutline = true
        }

    val checkBox =
        SchemaCheckView(ctx).apply {
            isVisible = false
        }

    val nameText =
        TextView(ctx).apply {
            textSize = 18f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    private val idText =
        TextView(ctx).apply {
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        }

    private val texts =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                nameText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                idText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    /** 长按整行可拖动排序，这个手柄只作为视觉提示。 */
    private val dragHandle =
        TextView(ctx).apply {
            text = "≡"
            textSize = 20f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    override val root: View =
        FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(dp(16), 0, dp(16), dp(10))
            addView(
                card,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    init {
        card.addView(
            checkBox,
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(12) },
        )
        card.addView(
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(
            dragHandle,
            LinearLayout.LayoutParams(dp(28), dp(40)),
        )
        applyPalette(palette)
    }

    /** 同时刷新文字、勾选框和卡片底色，避免深色模式下留旧颜色。 */
    fun applyPalette(palette: Palette) {
        nameText.setTextColor(palette.ink)
        idText.setTextColor(palette.muted)
        dragHandle.setTextColor(palette.chevron)
        checkBox.applyPalette(palette)
        card.background = SettingsKitDrawables.rounded(ctx, palette.group, SettingsPalette.RADIUS_GROUP)
    }

    fun bindSummary(summary: String) {
        idText.text = summary
    }

    private fun dp(value: Int): Int = SettingsPalette.dp(ctx, value)
}
