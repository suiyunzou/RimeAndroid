// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** Raw-input editing only. Feed returned positions to Rime, never to InputConnection. */
class PinyinComposingScrubber(density: Float) {
    private val step = 12f * density.also { require(it.isFinite() && it > 0f) }
    private val edge = 25f * density
    private var remainingX = 0f
    private var totalY = 0f
    private var edgeDirection = 0
    private var active = false
    private var raw = ""
    var cursorIndex: Int = 0
        private set
    val preview: String get() = raw.substring(0, cursorIndex) + "|" + raw.substring(cursorIndex)

    fun begin(rawInput: String, rawCaret: Int = rawInput.length) {
        raw = rawInput
        cursorIndex = rawCaret.coerceIn(0, raw.length)
        if (cursorIndex in 1 until raw.length && raw[cursorIndex].isLowSurrogate() && raw[cursorIndex - 1].isHighSurrogate()) cursorIndex--
        remainingX = 0f
        totalY = 0f
        edgeDirection = 0
        active = true
    }

    /** Arguments are incremental deltas, not distances from the touch origin. */
    fun move(deltaX: Float, deltaY: Float): Int? {
        if (!active || !deltaX.isFinite() || !deltaY.isFinite()) return null
        val previous = cursorIndex
        totalY += deltaY
        val direction = when { totalY < -edge -> -1; totalY > edge -> 1; else -> 0 }
        if (direction != 0 && direction != edgeDirection) {
            cursorIndex = if (direction < 0) 0 else raw.length
            remainingX = 0f
        } else {
            remainingX += deltaX
            val steps = (remainingX / step).toInt()
            if (steps != 0) {
                remainingX -= steps * step
                val available = if (steps > 0) raw.codePointCount(cursorIndex, raw.length) else raw.codePointCount(0, cursorIndex)
                val bounded = steps.coerceIn(-available, available)
                cursorIndex = raw.offsetByCodePoints(cursorIndex, bounded)
            }
        }
        edgeDirection = direction
        return cursorIndex.takeIf { it != previous }
    }

    /** librime caret positions are UTF-8 byte offsets; ASCII pinyin is unchanged. */
    fun rimeCaretPosition(): Int = raw.substring(0, cursorIndex).toByteArray(Charsets.UTF_8).size

    fun end() { active = false; remainingX = 0f; totalY = 0f }
}
