// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/**
 * Pure decision for "this press is still a plain tap on its original key".
 *
 * The touch controller uses it in two places:
 * - a second thumb presses down while the first thumb is still held: the first
 *   letter must be committed first even when it rolled a few pixels;
 * - the first thumb lifts after rolling past the flick distance without ever
 *   producing a flick: the key must not be silently dropped.
 *
 * A detected flick, a long press, or a press that left its key face is not a
 * tap and keeps the existing gesture behavior.
 */
internal object KeyTapSettle {
    fun isInsideKey(
        x: Float,
        y: Float,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
    ): Boolean = x >= left && x < right && y >= top && y < bottom

    fun canSettle(
        state: KeyFlickGesture.State,
        pendingFlick: KeyFlickGesture.Action,
        insideHeldKey: Boolean,
    ): Boolean = state == KeyFlickGesture.State.DOWN &&
        pendingFlick == KeyFlickGesture.Action.NONE &&
        insideHeldKey
}
