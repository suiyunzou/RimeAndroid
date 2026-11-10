/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** Rounded surface with a light ripple, used by cards, rows and buttons of the settings pages. */
internal object SettingsKitDrawables {
    fun rounded(
        context: android.content.Context,
        color: Int,
        radiusDp: Int,
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = SettingsPalette.dp(context, radiusDp).toFloat()
        }

    /** Same shape with an alpha ripple so taps read as presses on both light and dark pages. */
    fun pressable(
        context: android.content.Context,
        color: Int,
        radiusDp: Int,
        ripple: Int,
    ): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(ripple),
            rounded(context, color, radiusDp),
            null,
        )
}
