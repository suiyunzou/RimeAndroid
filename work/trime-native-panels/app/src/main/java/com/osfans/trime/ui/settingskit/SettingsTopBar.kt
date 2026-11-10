/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Shared top bar for settings activities: centred title and the white 「‹ 返回」 capsule. */
object SettingsTopBar {
    fun create(
        context: Context,
        title: String,
        palette: Palette,
        onBack: (() -> Unit)?,
        rightView: View? = null,
    ): FrameLayout {
        fun dp(value: Int) = SettingsPalette.dp(context, value)
        val bar =
            FrameLayout(context).apply {
                setPadding(dp(16), dp(7), dp(16), dp(7))
                setBackgroundColor(palette.background)
            }
        if (onBack != null) {
            val back =
                TextView(context).apply {
                    text = "‹ 返回"
                    textSize = 19f
                    setTextColor(palette.ink)
                    gravity = Gravity.CENTER
                    background =
                        SettingsKitDrawables.rounded(
                            context,
                            if (palette.dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt(),
                            24,
                        ).apply {
                            setStroke(
                                dp(1),
                                if (palette.dark) 0xFF3A3A3C.toInt() else 0xFFE4E4E9.toInt(),
                            )
                        }
                    contentDescription = "返回"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onBack() }
                }
            bar.addView(
                back,
                FrameLayout.LayoutParams(dp(88), dp(40), Gravity.START or Gravity.CENTER_VERTICAL),
            )
        }
        bar.addView(
            TextView(context).apply {
                text = title
                textSize = 19f
                setTextColor(palette.ink)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                contentDescription = title
            },
            FrameLayout.LayoutParams(dp(200), dp(44), Gravity.CENTER),
        )
        rightView?.let {
            bar.addView(
                it,
                FrameLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40),
                    Gravity.END or Gravity.CENTER_VERTICAL,
                ),
            )
        }
        return bar
    }
}
