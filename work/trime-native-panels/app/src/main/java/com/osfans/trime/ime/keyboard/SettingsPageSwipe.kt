/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.ime.keyboard

import kotlin.math.abs

/** Once dragged, a settings card can never turn back into a click. */
internal class SettingsPageSwipe(private val slop: Float) {
    private var startX = 0f
    private var startY = 0f
    private var dragged = false
    private var horizontal = false
    private var canceled = false

    val intercept: Boolean get() = dragged || canceled

    fun begin(x: Float, y: Float) {
        startX = x
        startY = y
        dragged = false
        horizontal = false
        canceled = false
    }

    fun move(x: Float, y: Float) {
        if (dragged || canceled) return
        val dx = x - startX
        val dy = y - startY
        if (dx * dx + dy * dy > slop * slop) {
            dragged = true
            horizontal = abs(dx) > abs(dy)
        }
    }

    fun cancel() {
        canceled = true
    }

    /** -1 next, +1 previous, 0 consume without a navigation or key action. */
    fun release(x: Float, y: Float): Int {
        move(x, y) // Includes an UP that arrived without a final MOVE.
        val dx = x - startX
        val dy = y - startY
        if (canceled || !dragged || !horizontal || abs(dx) <= slop || abs(dx) <= abs(dy)) return 0
        return if (dx < 0) -1 else 1
    }
}