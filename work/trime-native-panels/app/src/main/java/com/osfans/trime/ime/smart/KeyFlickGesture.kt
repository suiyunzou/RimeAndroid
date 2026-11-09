// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import kotlin.math.abs
import kotlin.math.hypot

/**
 * One-pointer key gesture classifier. Coordinates are pixels; times use the same monotonic clock
 * (MotionEvent.eventTime / SystemClock.uptimeMillis). This class never dispatches key input.
 *
 * Schedule [advanceTime] at [longPressDeadlineMs] after down; cancel that callback as soon as
 * [longPressPending] becomes false. A returned LONG_PRESS is emitted once, never again on up.
 * Read [pendingFlick] after move for preview only; commit the returned action from [up].
 * Cancel on ACTION_CANCEL, a second pointer, window detachment, or keyboard replacement.
 */
class KeyFlickGesture(density: Float) {
    enum class State { IDLE, DOWN, FLICK_DETECTED, LONG_PRESSED, UP, CANCELLED }
    enum class Action { NONE, TAP, FLICK_UP, FLICK_DOWN, LOCK_FLICK_UP, LOCK_FLICK_DOWN, LONG_PRESS, CANCEL }

    init { require(density.isFinite() && density > 0f) }

    val touchSlopPx = TOUCH_SLOP_DP * density
    val flickDistancePx = FLICK_DISTANCE_DP * density
    var state = State.IDLE
        private set
    var pendingFlick = Action.NONE
        private set
    var longPressPending = false
        private set
    var longPressDeadlineMs = 0L
        private set

    private var startX = 0f
    private var startY = 0f
    private var downTimeMs = 0L
    private var movedBeyondSlop = false
    private var heldBeforeMovement = false
    private var flickDetectedAt = 0L

    fun down(xPx: Float, yPx: Float, timeMs: Long, allowLongPress: Boolean = true): Action {
        reset()
        if (!xPx.isFinite() || !yPx.isFinite()) return cancel()
        startX = xPx
        startY = yPx
        downTimeMs = timeMs
        longPressDeadlineMs = timeMs + LONG_PRESS_MS
        longPressPending = allowLongPress
        state = State.DOWN
        return Action.NONE
    }

    fun move(xPx: Float, yPx: Float, timeMs: Long): Action {
        if (state != State.DOWN && state != State.FLICK_DETECTED) return Action.NONE
        if (!xPx.isFinite() || !yPx.isFinite() || timeMs < downTimeMs) return cancel()
        // If no earlier sample moved, the hold already won even when the timer
        // itself was delayed. Batched samples are replayed chronologically.
        if (state == State.DOWN && longPressPending && timeMs >= longPressDeadlineMs) return advanceTime(timeMs)
        val dx = xPx - startX
        val dy = yPx - startY
        if (hypot(dx, dy) > touchSlopPx) {
            if (!movedBeyondSlop) heldBeforeMovement = timeMs - downTimeMs >= SYMBOL_HOLD_MS
            movedBeyondSlop = true
            longPressPending = false
        }
        if (state == State.DOWN && timeMs - downTimeMs <= FLICK_MAX_DURATION_MS &&
            abs(dy) >= flickDistancePx && abs(dx) / abs(dy) < FLICK_MAX_HORIZONTAL_RATIO
        ) {
            pendingFlick = if (heldBeforeMovement) {
                if (dy > 0f) Action.LOCK_FLICK_DOWN else Action.LOCK_FLICK_UP
            } else if (dy > 0f) Action.FLICK_DOWN else Action.FLICK_UP
            flickDetectedAt = timeMs
            state = State.FLICK_DETECTED
            longPressPending = false
        }
        return advanceTime(timeMs)
    }

    fun advanceTime(timeMs: Long): Action {
        if (state == State.DOWN && longPressPending && timeMs >= longPressDeadlineMs) {
            longPressPending = false
            state = State.LONG_PRESSED
            return Action.LONG_PRESS
        }
        return Action.NONE
    }

    /** A timer may overtake queued touch events when the UI thread is busy. */
    fun restoreBeforeLongPress(xPx: Float, yPx: Float, timeMs: Long, released: Boolean = false): Boolean {
        if (state != State.LONG_PRESSED || timeMs < downTimeMs || timeMs >= longPressDeadlineMs ||
            !xPx.isFinite() || !yPx.isFinite() ||
            (!released && hypot(xPx - startX, yPx - startY) <= touchSlopPx)) return false
        state = State.DOWN
        // The historic movement/release ends stationary holding. The caller replays
        // the event normally, so neither the letter nor its symbol is committed twice.
        longPressPending = false
        return true
    }

    fun up(xPx: Float, yPx: Float, timeMs: Long): Action {
        if (state != State.DOWN && state != State.FLICK_DETECTED && state != State.LONG_PRESSED) {
            return Action.NONE
        }
        if (!xPx.isFinite() || !yPx.isFinite() || timeMs < downTimeMs) return cancel()
        // UP can be the first event after a fast flick, so include its coordinates.
        val event = move(xPx, yPx, timeMs)
        val result = when {
            event == Action.CANCEL -> Action.CANCEL
            event == Action.LONG_PRESS -> Action.LONG_PRESS
            state == State.LONG_PRESSED -> Action.NONE
            state == State.FLICK_DETECTED && (if (heldBeforeMovement) timeMs - flickDetectedAt <= HELD_FLICK_FINISH_MS
                else timeMs - downTimeMs <= FLICK_MAX_DURATION_MS) -> pendingFlick
            state == State.DOWN && hypot(xPx - startX, yPx - startY) < flickDistancePx -> Action.TAP
            else -> Action.CANCEL
        }
        state = State.UP
        longPressPending = false
        pendingFlick = Action.NONE
        return result
    }

    /** A second thumb may settle a stationary letter before either finger lifts. */
    fun finishOverlappingTap(xPx: Float, yPx: Float): Action {
        if (state != State.DOWN || movedBeyondSlop || !xPx.isFinite() || !yPx.isFinite() ||
            hypot(xPx - startX, yPx - startY) > touchSlopPx) return Action.NONE
        state = State.UP
        longPressPending = false
        pendingFlick = Action.NONE
        return Action.TAP
    }

    fun cancel(): Action {
        state = State.CANCELLED
        longPressPending = false
        pendingFlick = Action.NONE
        return Action.CANCEL
    }

    fun reset() {
        state = State.IDLE
        pendingFlick = Action.NONE
        longPressPending = false
        movedBeyondSlop = false
        heldBeforeMovement = false
        flickDetectedAt = 0L
        longPressDeadlineMs = 0L
    }

    companion object {
        const val TOUCH_SLOP_DP = 8f
        const val FLICK_DISTANCE_DP = 16f
        const val FLICK_MAX_DURATION_MS = 800L
        const val FLICK_MAX_HORIZONTAL_RATIO = 0.7f
        // Deliberate hold, not an ordinary slightly slow flick. This is an
        // implementation threshold, not a timing measurement from the video.
        const val HELD_FLICK_FINISH_MS = 600L
        const val SYMBOL_HOLD_MS = 350L
        const val LONG_PRESS_MS = 800L
    }
}
