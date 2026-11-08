// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import kotlin.math.abs

/** A gesture-local caret tracker. End the gesture when its input editor changes. */
class SpatialCursorScrubber private constructor(
    density: Float,
    private val editor: () -> Editor?,
    private val onStep: (Direction) -> Unit,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    constructor(density: Float, connection: () -> InputConnection?, onStep: (Direction) -> Unit = {}) :
        this(density, { connection()?.let(::ConnectionEditor) }, onStep, Unit)

    enum class Direction { LEFT, RIGHT, UP, DOWN }
    private val step = 12f * density.also { require(it.isFinite() && it > 0f) }
    private var remainingX = 0f
    private var remainingY = 0f
    private var active = false
    private var editorIdentity: Any? = null
    private var snapshot: Snapshot? = null

    fun begin() {
        remainingX = 0f
        remainingY = 0f
        snapshot = null
        editorIdentity = null
        active = true
    }

    fun end() { active = false; snapshot = null; editorIdentity = null }

    /** Incremental deltas. At most 128 steps per event prevent malformed input stalls. */
    fun move(deltaX: Float, deltaY: Float): Boolean {
        if (!active || !deltaX.isFinite() || !deltaY.isFinite()) return false
        val target = safely<Editor?>(null) { editor() } ?: run { end(); return false }
        if (editorIdentity != null && editorIdentity !== target.identity) { end(); return false }
        editorIdentity = target.identity
        remainingX += deltaX
        remainingY += deltaY
        val horizontal = (remainingX / step).toInt().coerceIn(-128, 128)
        val vertical = (remainingY / step).toInt().coerceIn(-128, 128)
        remainingX -= horizontal * step
        remainingY -= vertical * step
        var moved = false
        repeat(abs(horizontal)) {
            val direction = if (horizontal < 0) Direction.LEFT else Direction.RIGHT
            if (horizontalStep(target, direction)) { moved = true; safely(Unit) { onStep(direction) } }
        }
        repeat(abs(vertical)) {
            val direction = if (vertical < 0) Direction.UP else Direction.DOWN
            snapshot = null // Vertical navigation changes the absolute position in the editor.
            if (safely(false) { target.arrow(direction) }) { moved = true; safely(Unit) { onStep(direction) } }
        }
        return moved
    }

    private fun horizontalStep(target: Editor, direction: Direction): Boolean {
        val state = snapshot ?: safely<Snapshot?>(null) { target.snapshot() }
        if (state != null && state.valid()) {
            val left = direction == Direction.LEFT
            val selected = state.start != state.end
            val position = if (left) minOf(state.start, state.end) else maxOf(state.start, state.end)
            if (!selected && left && position == 0 && state.offset == 0) return false
            val next = when {
                selected -> position
                left && position > 0 -> state.text.offsetByCodePoints(position, -1)
                !left && position < state.text.length -> state.text.offsetByCodePoints(position, 1)
                else -> null
            }
            if (next != null && state.offset.toLong() + next <= Int.MAX_VALUE &&
                safely(false) { target.select(state.offset + next) }
            ) {
                snapshot = state.copy(start = next, end = next)
                return true
            }
        }
        snapshot = null
        return safely(false) { target.arrow(direction) }
    }

    internal data class Snapshot(val text: String, val offset: Int, val start: Int, val end: Int) {
        fun valid() = offset >= 0 && start in 0..text.length && end in 0..text.length
    }
    internal interface Editor {
        val identity: Any
        fun snapshot(): Snapshot?
        fun select(position: Int): Boolean
        fun arrow(direction: Direction): Boolean
    }
    private class ConnectionEditor(private val connection: InputConnection) : Editor {
        override val identity: Any get() = connection
        override fun snapshot(): Snapshot? = connection.getExtractedText(ExtractedTextRequest(), 0)?.let {
            Snapshot(it.text?.toString() ?: return null, it.startOffset, it.selectionStart, it.selectionEnd)
        }
        override fun select(position: Int) = connection.setSelection(position, position)
        override fun arrow(direction: Direction): Boolean {
            val code = when (direction) {
                Direction.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                Direction.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                Direction.UP -> KeyEvent.KEYCODE_DPAD_UP
                Direction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            }
            val down = safely(false) { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)) }
            val up = safely(false) { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }
            return down && up
        }
    }
    companion object {
        internal fun forEditor(density: Float, editor: () -> Editor?, onStep: (Direction) -> Unit = {}) =
            SpatialCursorScrubber(density, editor, onStep, Unit)
        private inline fun <T> safely(fallback: T, block: () -> T): T = try { block() } catch (_: Exception) { fallback }
    }
}
