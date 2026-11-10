/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.userdict

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R
import com.osfans.trime.ui.settingskit.Palette
import com.osfans.trime.ui.settingskit.SettingsKitDrawables
import com.osfans.trime.ui.settingskit.SettingsPalette
import splitties.views.dsl.core.Ui

/**
 * 「用户词典」列表的一行：白色圆角卡片，标题为词典名，副标题提示可用的备份/导入/导出操作，
 * 右侧 ⋮ 是原有菜单入口。样式与「方案」页保持同一套卡片参数。
 */
class UserDictListEntryUi(
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

    val nameText =
        TextView(ctx).apply {
            textSize = 18f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    private val summaryText =
        TextView(ctx).apply {
            text = "备份 · 导入 · 导出"
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
                summaryText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    val moreButton =
        ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_baseline_more_horiz_24)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
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
            texts,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        card.addView(
            moreButton,
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        applyPalette(palette)
    }

    /** 同时刷新文字、⋮ 图标与卡片底色，避免深色模式下留旧颜色。 */
    fun applyPalette(palette: Palette) {
        nameText.setTextColor(palette.ink)
        summaryText.setTextColor(palette.muted)
        moreButton.imageTintList = ColorStateList.valueOf(palette.muted)
        moreButton.background = circleRipple(palette)
        card.background = SettingsKitDrawables.rounded(ctx, palette.group, SettingsPalette.RADIUS_GROUP)
    }

    /** 圆形点击波纹，只覆盖 44dp 的按钮区域。 */
    private fun circleRipple(palette: Palette): RippleDrawable {
        val ripple = 0x1F000000 or (palette.ink and 0x00FFFFFF)
        val mask =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        return RippleDrawable(ColorStateList.valueOf(ripple), null, mask)
    }

    private fun dp(value: Int): Int = SettingsPalette.dp(ctx, value)
}
