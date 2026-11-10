/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

/**
 * Pure state derivation shared by the settings kit widgets.
 *
 * Everything here is framework free on purpose: row decoration, segment selection and the
 * switch slide are decided by these functions, so they can be unit tested on the JVM without
 * an Android runtime.
 */
object SettingsKitState {
    /** Switch position labels, also used in the accessibility description. */
    const val SWITCH_ON_LABEL = "已开启"

    const val SWITCH_OFF_LABEL = "已关闭"

    /** Which decorations a [SettingsBuilder.row] shows for a given content. */
    data class RowVisual(
        val hasValue: Boolean,
        val hasChevron: Boolean,
        val tappable: Boolean,
    ) {
        companion object {
            fun of(
                value: String,
                hasAction: Boolean,
            ): RowVisual =
                RowVisual(
                    hasValue = value.isNotEmpty(),
                    hasChevron = hasAction,
                    tappable = hasAction,
                )
        }
    }

    /** Which switch visual a state maps onto. */
    enum class SwitchVisual { ON, OFF }

    fun switchVisual(checked: Boolean): SwitchVisual = if (checked) SwitchVisual.ON else SwitchVisual.OFF

    fun flipped(checked: Boolean): Boolean = !checked

    fun switchStateLabel(checked: Boolean): String = if (checked) SWITCH_ON_LABEL else SWITCH_OFF_LABEL

    /** `label，已开启` (or just the state when no label is given). */
    fun switchDescription(
        label: CharSequence?,
        checked: Boolean,
    ): CharSequence {
        val state = switchStateLabel(checked)
        return if (label.isNullOrEmpty()) state else "$label，$state"
    }

    /**
     * Position (0..1) of the iOS switch knob for an animation [fraction].
     * Clamped, so a stale or overshooting animator value can never draw past the track.
     */
    fun knobFraction(
        checked: Boolean,
        fraction: Float,
    ): Float {
        val clamped = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
        return if (checked) clamped else 1f - clamped
    }

    /**
     * Index of the selected segment; out of range selections (or an empty list) are clamped so a
     * stale preference can never crash the segmented control. Returns -1 for an empty list.
     */
    fun selectedSegment(
        labels: List<String>,
        selected: Int,
    ): Int = if (labels.isEmpty()) -1 else selected.coerceIn(0, labels.lastIndex)

    /** Index of [label], or the clamped [fallback] when it is not in [labels]. */
    fun segmentIndexOf(
        labels: List<String>,
        label: String,
        fallback: Int = 0,
    ): Int {
        val index = labels.indexOf(label)
        return if (index >= 0) index else selectedSegment(labels, fallback)
    }

    /** Accessibility description of the whole segmented control. */
    fun segmentsDescription(
        labels: List<String>,
        selected: Int,
    ): String {
        val index = selectedSegment(labels, selected)
        if (index < 0) return ""
        return labels.mapIndexed { i, label -> if (i == index) "$label，已选中" else label }.joinToString("，")
    }

    /** Accessibility description of a row: `title` or `title，value`. */
    fun rowDescription(
        title: String,
        value: String,
    ): String = if (value.isEmpty()) title else "$title，$value"

    /**
     * Indices after which a card should draw a divider (all but the last visible row), so cards
     * read like the reference screenshot. Returns an empty set for fewer than two rows.
     */
    fun dividerAfter(visibleIndices: List<Int>): Set<Int> =
        if (visibleIndices.size < 2) emptySet() else visibleIndices.dropLast(1).toSet()
}
