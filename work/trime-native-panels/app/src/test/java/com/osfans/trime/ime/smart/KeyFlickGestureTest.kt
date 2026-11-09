// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import com.osfans.trime.ime.smart.KeyFlickGesture.Action
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeyFlickGestureTest : FunSpec({
    test("tap tolerates exactly eight dp and density scales distance") {
        val gesture = KeyFlickGesture(2f)
        gesture.down(100f, 100f, 0)
        gesture.up(116f, 100f, 100) shouldBe Action.TAP
        gesture.down(100f, 100f, 200)
        gesture.up(100f, 132f, 300) shouldBe Action.FLICK_DOWN
    }
    test("motion below flick threshold remains a single tap") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.up(0f, -15.9f, 100) shouldBe Action.TAP
    }
    test("flick may arrive only in up and dispatches once") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.up(0f, -16f, 100) shouldBe Action.FLICK_UP
        gesture.up(0f, -16f, 101) shouldBe Action.NONE
    }
    test("angle gate excludes diagonal and horizontal swipes") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.up(70f, 100f, 100) shouldBe Action.CANCEL
        gesture.down(0f, 0f, 200)
        gesture.up(69f, 100f, 300) shouldBe Action.FLICK_DOWN
        gesture.down(0f, 0f, 400)
        gesture.up(100f, 0f, 500) shouldBe Action.CANCEL
    }
    test("flick release is valid at 800 ms but not after") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.move(0f, 22f, 100)
        gesture.pendingFlick shouldBe Action.FLICK_DOWN
        gesture.up(0f, 22f, 800) shouldBe Action.FLICK_DOWN
        gesture.down(0f, 0f, 1000)
        gesture.move(0f, 22f, 1100)
        gesture.up(0f, 22f, 1801) shouldBe Action.CANCEL
    }
    test("long press needs a deliberate 800 ms hold and never taps on release") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.advanceTime(280) shouldBe Action.NONE
        gesture.advanceTime(799) shouldBe Action.NONE
        gesture.advanceTime(800) shouldBe Action.LONG_PRESS
        gesture.advanceTime(850) shouldBe Action.NONE
        gesture.up(0f, 100f, 900) shouldBe Action.NONE
    }
    test("crossing slop permanently cancels long press even after returning") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.move(8.1f, 0f, 100)
        gesture.longPressPending shouldBe false
        gesture.move(0f, 0f, 200)
        gesture.advanceTime(280) shouldBe Action.NONE
        gesture.up(0f, 0f, 300) shouldBe Action.TAP
    }
    test("cancel suppresses delayed callbacks and up") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.cancel() shouldBe Action.CANCEL
        gesture.advanceTime(400) shouldBe Action.NONE
        gesture.up(0f, 0f, 500) shouldBe Action.NONE
    }
    test("non-letter keys may disable long press") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0, allowLongPress = false)
        gesture.advanceTime(500) shouldBe Action.NONE
        gesture.up(0f, 0f, 600) shouldBe Action.TAP
    }
    test("reset starts a clean gesture and invalid coordinates cancel") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.move(0f, -30f, 100)
        gesture.cancel()
        gesture.down(10f, 10f, 200)
        gesture.up(10f, 10f, 250) shouldBe Action.TAP
        gesture.down(Float.NaN, 0f, 300) shouldBe Action.CANCEL
    }
    test("overlapping thumbs commit in press order even when released in reverse order") {
        val first = KeyFlickGesture(1f)
        val second = KeyFlickGesture(1f)
        val committed = mutableListOf<String>()
        first.down(0f, 0f, 0)
        if (first.finishOverlappingTap(2f, 1f) == Action.TAP) committed += "a"
        second.down(40f, 0f, 30)
        if (second.up(40f, 0f, 60) == Action.TAP) committed += "b"
        if (first.up(2f, 1f, 90) == Action.TAP) committed += "a"
        committed shouldBe listOf("a", "b")
    }
    test("overlap does not settle an existing flick or a moved or held letter") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.move(0f, -16f, 30)
        gesture.finishOverlappingTap(0f, -16f) shouldBe Action.NONE
        gesture.up(0f, -16f, 60) shouldBe Action.FLICK_UP
        gesture.down(0f, 0f, 100)
        gesture.move(10f, 0f, 110)
        gesture.move(0f, 0f, 120)
        gesture.finishOverlappingTap(0f, 0f) shouldBe Action.NONE
        gesture.down(0f, 0f, 200)
        gesture.advanceTime(1000) shouldBe Action.LONG_PRESS
        gesture.finishOverlappingTap(0f, 0f) shouldBe Action.NONE
    }
    test("stationary hold then flick locks before the cursor deadline") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.advanceTime(350) shouldBe Action.NONE
        gesture.move(0f, 20f, 450)
        gesture.advanceTime(600) shouldBe Action.NONE
        gesture.up(0f, 20f, 700) shouldBe Action.LOCK_FLICK_DOWN
    }
    test("queued pre-deadline movement can undo an overtaking timer") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.advanceTime(850) shouldBe Action.LONG_PRESS
        gesture.restoreBeforeLongPress(0f, 10f, 400) shouldBe true
        gesture.move(0f, 10f, 400)
        gesture.move(0f, 25f, 650)
        gesture.up(0f, 25f, 700) shouldBe Action.LOCK_FLICK_DOWN
    }
    test("queued early release is a tap while genuine post-deadline motion stays cursor") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.advanceTime(850) shouldBe Action.LONG_PRESS
        gesture.restoreBeforeLongPress(0f, 0f, 400, released = true) shouldBe true
        gesture.up(0f, 0f, 400) shouldBe Action.TAP
        gesture.down(0f, 0f, 1000)
        gesture.advanceTime(1800) shouldBe Action.LONG_PRESS
        gesture.restoreBeforeLongPress(0f, 30f, 1800) shouldBe false
        gesture.move(0f, 30f, 1850) shouldBe Action.NONE
        gesture.up(0f, 30f, 1900) shouldBe Action.NONE
    }
    test("a delayed timer does not turn a genuine hold into a flick") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.move(0f, 30f, 800) shouldBe Action.LONG_PRESS
        gesture.up(0f, 30f, 900) shouldBe Action.NONE
    }
    test("early movement cannot become a lock just by slowing down") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.move(0f, 9f, 100)
        gesture.move(0f, 20f, 500)
        gesture.up(0f, 20f, 700) shouldBe Action.FLICK_DOWN
    }
    test("lock starts at 350 ms and respects upward direction and cancel") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.up(0f, -20f, 349) shouldBe Action.FLICK_UP
        gesture.down(0f, 0f, 1000)
        gesture.up(0f, -20f, 1350) shouldBe Action.LOCK_FLICK_UP
        gesture.down(0f, 0f, 2000)
        gesture.move(0f, 20f, 2400)
        gesture.cancel()
        gesture.up(0f, 20f, 2450) shouldBe Action.NONE
    }
    test("held flick has its own release budget after cursor threshold") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.move(0f, 20f, 700)
        gesture.advanceTime(850) shouldBe Action.NONE
        gesture.up(0f, 20f, 900) shouldBe Action.LOCK_FLICK_DOWN
        gesture.down(0f, 0f, 1000)
        gesture.move(0f, 20f, 1700)
        gesture.up(0f, 20f, 2301) shouldBe Action.CANCEL
    }
    test("held custom text locks but commands and empty custom text do not") {
        SmartKeyboardTouchController.resolveFlickBinding("text:@", true) shouldBe "lock_flick_symbol:@"
        SmartKeyboardTouchController.resolveFlickBinding("text:@", false) shouldBe "text:@"
        SmartKeyboardTouchController.resolveFlickBinding("action:copy", true) shouldBe "action:copy"
        SmartKeyboardTouchController.resolveFlickBinding("text:", true) shouldBe "text:"
    }
    test("locked taps use custom corner text and never fall back from disabled text") {
        FlickSymbolBinding.lockedText("text:#", "1") shouldBe "#"
        FlickSymbolBinding.lockedText("text:", "1") shouldBe ""
        FlickSymbolBinding.lockedText("action:copy", "1") shouldBe null
        FlickSymbolBinding.lockedText(null, "1") shouldBe "1"
        FlickSymbolBinding.lockedText(null, null) shouldBe null
    }
})
