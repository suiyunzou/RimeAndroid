package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FlickDirectionTest : FunSpec({
    test("default and unknown persisted values select down only") {
        listOf(null, "", "both", "invalid").forEach {
            val direction = FlickDirection.fromId(it)
            direction.allows(true) shouldBe false
            direction.allows(false) shouldBe true
        }
    }
    test("all three settings round trip and never enable both directions") {
        FlickDirection.entries.forEach { direction ->
            FlickDirection.fromId(direction.id) shouldBe direction
            (direction.allows(true) && direction.allows(false)) shouldBe false
        }
        FlickDirection.OFF.allows(true) shouldBe false
        FlickDirection.OFF.allows(false) shouldBe false
        FlickDirection.UP.allows(true) shouldBe true
        FlickDirection.UP.allows(false) shouldBe false
    }
    test("long press owns later vertical movement and cannot produce a digit") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.advanceTime(KeyFlickGesture.LONG_PRESS_MS) shouldBe KeyFlickGesture.Action.LONG_PRESS
        gesture.move(0f, -80f, 650) shouldBe KeyFlickGesture.Action.NONE
        gesture.move(0f, 80f, 750) shouldBe KeyFlickGesture.Action.NONE
        gesture.pendingFlick shouldBe KeyFlickGesture.Action.NONE
        gesture.up(0f, 80f, 800) shouldBe KeyFlickGesture.Action.NONE
    }
    test("a recognized short flick suppresses later long press until release") {
        val gesture = KeyFlickGesture(1f)
        gesture.down(0f, 0f, 0)
        gesture.move(0f, 18f, 100)
        gesture.advanceTime(500) shouldBe KeyFlickGesture.Action.NONE
        gesture.up(0f, 18f, 600) shouldBe KeyFlickGesture.Action.FLICK_DOWN
    }
})
