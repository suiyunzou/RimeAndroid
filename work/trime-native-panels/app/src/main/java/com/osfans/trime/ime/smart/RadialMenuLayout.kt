// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Visual positions only. Gesture distances are owned by [RadialMenuGeometry]. */
internal class RadialMenuLayout(
    private val x: Float,
    private val y: Float,
    width: Float,
    density: Float,
    slotCount: Int,
) {
    data class Point(val x: Float, val y: Float)

    private val padding = 21f * density
    // Keep the focus lens within the keyboard on short layouts and off-centre holds.
    private val horizontalRadius = (if (slotCount == 1) OUTER_ARC_DP else ICON_RADIUS_DP) * density
    val unit = density * minOf(
        1f,
        (minOf(x, width - x) - padding) / horizontalRadius,
        (y - padding) / (ICON_RADIUS_DP * density),
    ).coerceIn(0.12f, 1f)

    val outerRadius = OUTER_ARC_DP * unit
    val gestureRadius = GESTURE_ARC_DP * unit

    fun icon(index: Int, count: Int): Point {
        val angle = Math.toRadians(iconDegrees(index, count).toDouble())
        return Point(
            x + ICON_RADIUS_DP * unit * cos(angle).toFloat(),
            y - ICON_RADIUS_DP * unit * sin(angle).toFloat(),
        )
    }

    /** Limit the displayed cursor to the inner arc; the real touch still selects the sector. */
    fun cursor(fingerX: Float, fingerY: Float, above: Boolean): Point {
        val dx = fingerX - x
        val dy = (fingerY - y - if (above) 8f * unit else 0f).coerceAtMost(0f)
        val distance = hypot(dx, dy)
        val scale = if (distance > gestureRadius) gestureRadius / distance else 1f
        return Point(x + dx * scale, y + dy * scale)
    }

    companion object {
        const val ARC_MAX_DEGREES = 156f
        const val ARC_MIN_DEGREES = 24f
        const val ARC_SWEEP = ARC_MAX_DEGREES - ARC_MIN_DEGREES
        private const val OUTER_ARC_DP = 94f
        private const val GESTURE_ARC_DP = 43f
        private const val ICON_RADIUS_DP = 118f

        fun iconDegrees(index: Int, count: Int): Float =
            if (count <= 1) 90f else ARC_MAX_DEGREES - index * ARC_SWEEP / (count - 1)
    }
}
