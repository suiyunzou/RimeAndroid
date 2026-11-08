// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import kotlin.math.atan2
import kotlin.math.hypot

/** Actions are ordered visually from left to right. Coordinates are local pixels. */
class RadialMenuGeometry(private val density: Float) {
    init { require(density > 0f && density.isFinite()) }
    var isActive = false
        private set
    var originX = 0f
        private set
    var originY = 0f
        private set
    var slots: List<String> = DEFAULT_SLOTS
        private set
    var selectedIndex = -1
        private set
    var selectedAction: String? = null
        private set
    var timeArmed = false
        private set
    var timeFormatIndex = 0
        private set
    private var timeEntryY = 0f
    private var timeSlideX = 0f

    fun begin(x: Float, y: Float, actions: List<String> = DEFAULT_SLOTS) {
        require(x.isFinite() && y.isFinite())
        originX = x
        originY = y
        slots = actions.filter { it in SUPPORTED_ACTIONS }.distinct().take(MAX_SLOTS)
        selectedIndex = -1
        selectedAction = null
        timeArmed = false
        timeFormatIndex = 0
        isActive = slots.isNotEmpty()
    }

    fun hitTest(x: Float, y: Float): Int {
        if (!isActive || !x.isFinite() || !y.isFinite()) return -1
        val dx = x - originX
        val dy = y - originY
        val distance = hypot(dx, dy) / density
        if (distance < INNER_RADIUS_DP || distance > OUTER_RADIUS_DP || dy > 0f) return -1
        val angle = if (dy == 0f) {
            if (dx < 0f) 180.0 else 0.0
        } else Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
        if (angle < 0.0 || angle > 180.0) return -1
        return ((180.0 - angle) / (180.0 / slots.size)).toInt().coerceAtMost(slots.lastIndex)
    }

    /** Time is a second-level horizontal carousel, not another radial sector. */
    fun update(x: Float, y: Float): Boolean {
        if (!isActive) return false
        if (!x.isFinite() || !y.isFinite()) { cancel(); return false }
        if (timeArmed) {
            if (hypot(x-originX, y-originY) < INNER_RADIUS_DP*density || y > originY + TIME_CANCEL_DISTANCE_DP * density) {
                timeArmed = false
                selectedIndex = -1
                selectedAction = null
                return false
            }
            val steps = ((x - timeSlideX) / (TIME_FORMAT_STEP_DP * density)).toInt()
            if (steps != 0) {
                timeSlideX += steps * TIME_FORMAT_STEP_DP * density
                val next = (timeFormatIndex + steps).coerceIn(TIME_ACTIONS.indices)
                if (next == timeFormatIndex) return false
                timeFormatIndex = next
                selectedAction = TIME_ACTIONS[timeFormatIndex]
                return true
            }
            return false
        }
        // Test before radial hit-testing: an upward drag must be allowed to leave
        // the clock sector to enter the separate date/time preview.
        if (selectedIndex >= 0 && slots[selectedIndex] == "time" &&
            timeEntryY - y >= SUBGESTURE_DISTANCE_DP * density
        ) {
            timeArmed = true
            timeSlideX = x
            timeFormatIndex = 0
            selectedAction = TIME_ACTIONS[0]
            return true
        }
        val next = hitTest(x, y)
        val changed = next != selectedIndex
        if (changed) {
            selectedIndex = next
            selectedAction = slots.getOrNull(next)
            timeEntryY = y
        }
        return changed && next >= 0
    }

    fun release(x: Float, y: Float): String? {
        if (!isActive) return null
        update(x, y)
        val action = if (isActive && (timeArmed || selectedIndex >= 0)) selectedAction else null
        cancel()
        return action
    }

    fun cancel() {
        isActive = false
        selectedIndex = -1
        selectedAction = null
        timeArmed = false
    }

    companion object {
        const val INNER_RADIUS_DP = 36f
        const val OUTER_RADIUS_DP = 120f
        const val MAX_SLOTS = 6
        const val SUBGESTURE_DISTANCE_DP = 12f
        const val TIME_FORMAT_STEP_DP = 36f
        const val TIME_CANCEL_DISTANCE_DP = 24f
        val DEFAULT_SLOTS = listOf("time", "traditional", "symbols", "clipboard", "settings")
        val SUPPORTED_ACTIONS = DEFAULT_SLOTS.toSet() + setOf("calculator", "quotes")
        val TIME_ACTIONS = listOf("chinese_date", "time", "weekday", "datetime", "date")
        val TIME_LABELS = listOf("中文日期", "时间", "星期", "日期时间", "日期")
    }
}

