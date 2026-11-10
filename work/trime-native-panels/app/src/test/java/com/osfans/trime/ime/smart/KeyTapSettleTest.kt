// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import com.osfans.trime.ime.smart.KeyFlickGesture.Action
import com.osfans.trime.ime.smart.KeyFlickGesture.State
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeyTapSettleTest : FunSpec({
    test("only a plain held press inside its own key can settle as a tap") {
        KeyTapSettle.canSettle(State.DOWN, Action.NONE, insideHeldKey = true) shouldBe true
        KeyTapSettle.canSettle(State.DOWN, Action.NONE, insideHeldKey = false) shouldBe false
    }
    test("a detected flick, long press, or finished press never settles as a tap") {
        KeyTapSettle.canSettle(State.FLICK_DETECTED, Action.FLICK_DOWN, true) shouldBe false
        KeyTapSettle.canSettle(State.FLICK_DETECTED, Action.FLICK_UP, true) shouldBe false
        KeyTapSettle.canSettle(State.LONG_PRESSED, Action.NONE, true) shouldBe false
        KeyTapSettle.canSettle(State.UP, Action.NONE, true) shouldBe false
        KeyTapSettle.canSettle(State.CANCELLED, Action.NONE, true) shouldBe false
    }
    test("key face includes expanded hit width and excludes the row above and below") {
        // A 108 px key face with the touch area expanded 12 px to the left.
        KeyTapSettle.isInsideKey(10f, 100f, 8f, 116f, 50f, 150f) shouldBe true
        KeyTapSettle.isInsideKey(7.9f, 100f, 8f, 116f, 50f, 150f) shouldBe false
        KeyTapSettle.isInsideKey(116f, 100f, 8f, 116f, 50f, 150f) shouldBe false
        KeyTapSettle.isInsideKey(60f, 49.9f, 8f, 116f, 50f, 150f) shouldBe false
        KeyTapSettle.isInsideKey(60f, 150f, 8f, 116f, 50f, 150f) shouldBe false
    }
    test("a roll that stays on the key settles but a roll into the neighbour does not") {
        val keyLeft = 100f
        val keyRight = 200f
        val keyTop = 400f
        val keyBottom = 500f
        fun canSettleAt(x: Float, y: Float) = KeyTapSettle.canSettle(
            State.DOWN, Action.NONE,
            KeyTapSettle.isInsideKey(x, y, keyLeft, keyRight, keyTop, keyBottom),
        )
        canSettleAt(104f, 404f) shouldBe true
        canSettleAt(199f, 499f) shouldBe true
        canSettleAt(201f, 450f) shouldBe false
        canSettleAt(150f, 501f) shouldBe false
    }
})
