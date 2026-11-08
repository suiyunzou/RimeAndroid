// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import kotlin.math.roundToInt

/** Drop-only mutation. Drag movement and cancellation never change stored preferences. */
object WheelSlotOrder {
    fun drop(current: List<String>, id: String, leftToRight: Float): List<String> {
        if (id !in SmartInputPrefs.ACTIONS || !leftToRight.isFinite()) return current
        if (id !in current && current.size >= RadialMenuGeometry.MAX_SLOTS) return current
        val count = current.size + if (id in current) 0 else 1
        val target = (leftToRight.coerceIn(0f, 1f) * (count - 1)).roundToInt()
        return current.toMutableList().apply { remove(id); add(target.coerceIn(0, size), id) }
    }
    fun remove(current: List<String>, id: String): List<String> =
        if (current.size > 1 && id in current) current.filterNot { it == id } else current
}
