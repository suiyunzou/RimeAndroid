/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorInt
import kotlin.math.roundToInt

/**
 * Colour tokens for the reference settings pages.
 *
 * All colour maths lives in [Palette], which never touches the Android framework, so the
 * tokens can be verified by plain JVM unit tests. Only [paletteOf] and [dp] need a Context.
 */
object SettingsPalette {
    const val RADIUS_GROUP = 22
    const val RADIUS_PILL = 22
    const val RADIUS_SEGMENT = 19
    const val ROW_HEIGHT_DP = 54

    /** iOS style switch metrics, in dp. */
    const val SWITCH_WIDTH_DP = 51
    const val SWITCH_HEIGHT_DP = 31
    const val SWITCH_ANIMATION_MS = 200

    const val INK_LIGHT = 0xFF111111.toInt()
    const val INK_DARK = 0xFFFFFFFF.toInt()
    const val MUTED_LIGHT = 0xFF818186.toInt()
    const val MUTED_DARK = 0xFF929296.toInt()
    const val BACKGROUND_LIGHT = 0xFFF2F2F6.toInt()
    const val BACKGROUND_DARK = 0xFF000000.toInt()
    const val GROUP_LIGHT = 0xFFFFFFFF.toInt()
    const val GROUP_DARK = 0xFF262628.toInt()
    const val SELECTED_LIGHT = 0xFFFFFFFF.toInt()
    const val SELECTED_DARK = 0xFF56565A.toInt()
    const val TRACK_LIGHT = 0xFFE4E4E9.toInt()
    const val TRACK_DARK = 0xFF363639.toInt()
    const val SWITCH_ON_COLOR = 0xFF30D958.toInt()
    const val SWITCH_OFF_LIGHT = 0xFFD1D1D6.toInt()
    const val SWITCH_OFF_DARK = 0xFF636366.toInt()

    /** Density conversion, equivalent to the old `dp(v)` helper on the reference pages. */
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).roundToInt()

    fun isNightMode(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** Resolves the palette for a context, honouring the night mode of its configuration. */
    fun paletteOf(context: Context): Palette = Palette.of(isNightMode(context))

    fun paletteOf(dark: Boolean): Palette = Palette.of(dark)
}

/**
 * An immutable, framework free palette snapshot, safe to keep for one rendered page.
 *
 * ```kotlin
 * val palette = SettingsPalette.paletteOf(context) // follows night mode
 * val palette = SettingsPalette.paletteOf(dark = true)
 * canvas.drawColor(palette.background)
 * val pad = SettingsPalette.dp(context, 16)
 * ```
 */
class Palette private constructor(
    val dark: Boolean,
) {
    @ColorInt val ink: Int = if (dark) SettingsPalette.INK_DARK else SettingsPalette.INK_LIGHT

    @ColorInt val muted: Int = if (dark) SettingsPalette.MUTED_DARK else SettingsPalette.MUTED_LIGHT

    @ColorInt val background: Int = if (dark) SettingsPalette.BACKGROUND_DARK else SettingsPalette.BACKGROUND_LIGHT

    @ColorInt val group: Int = if (dark) SettingsPalette.GROUP_DARK else SettingsPalette.GROUP_LIGHT

    @ColorInt val selected: Int = if (dark) SettingsPalette.SELECTED_DARK else SettingsPalette.SELECTED_LIGHT

    @ColorInt val track: Int = if (dark) SettingsPalette.TRACK_DARK else SettingsPalette.TRACK_LIGHT

    @ColorInt val switchOn: Int = SettingsPalette.SWITCH_ON_COLOR

    @ColorInt val switchOff: Int = if (dark) SettingsPalette.SWITCH_OFF_DARK else SettingsPalette.SWITCH_OFF_LIGHT

    @ColorInt val chevron: Int = if (dark) SettingsPalette.MUTED_DARK else 0xFFC4C4C9.toInt()

    @ColorInt val divider: Int = if (dark) 0xFF3A3A3D.toInt() else 0xFFEAEAEF.toInt()

    @ColorInt val note: Int = if (dark) SettingsPalette.MUTED_DARK else SettingsPalette.MUTED_LIGHT

    /** Track colour for a switch in the given state. */
    @ColorInt
    fun switchTrack(checked: Boolean): Int = if (checked) switchOn else switchOff

    companion object {
        fun of(dark: Boolean): Palette = Palette(dark)

        fun of(context: Context): Palette = Palette.of(SettingsPalette.isNightMode(context))
    }
}
