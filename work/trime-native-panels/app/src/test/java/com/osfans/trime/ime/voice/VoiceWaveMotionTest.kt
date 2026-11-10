// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class VoiceWaveMotionTest : FunSpec({
    test("room noise and invalid input leave a still baseline") {
        val wave = VoiceWaveMotion()
        for (sample in listOf(0f, 0.002f, Float.NaN, Float.POSITIVE_INFINITY)) {
            wave.sample(sample, 25)
            wave.advance(41, animate = true, listening = true) shouldBe false
            wave.level shouldBe 0f
            wave.seconds shouldBe 0f
        }
    }

    test("speech responds equally over the same time at 60 and 120 Hz") {
        fun levelAt96ms(step: Long): Float {
            val wave = VoiceWaveMotion()
            for (t in 0L..96L step step) {
                if (t % 32L == 0L) wave.sample(0.08f, t)
                wave.advance(t, animate = true, listening = true)
            }
            return wave.level
        }
        val level = levelAt96ms(16)
        (level > 0.7f) shouldBe true
        (abs(level - levelAt96ms(8)) < 0.0001f) shouldBe true
    }

    test("finishing freezes the carrier and settles without fresh samples") {
        val wave = VoiceWaveMotion()
        wave.sample(0.12f, 0)
        wave.advance(32, animate = true, listening = true)
        val before = wave.seconds
        wave.stop()
        var ticking = true
        for (time in 48L..560L step 16L) {
            ticking = wave.advance(time, animate = true, listening = false)
        }
        wave.seconds shouldBe before
        wave.level shouldBe 0f
        ticking shouldBe false
    }

    test("reduced motion reports the level without moving the carrier and expires stale audio") {
        val wave = VoiceWaveMotion()
        wave.sample(0.08f, 0)
        wave.advance(0, animate = false, listening = true) shouldBe false
        (wave.level > 0.7f) shouldBe true
        wave.seconds shouldBe 0f
        wave.advance(VoiceWaveMotion.SAMPLE_TIMEOUT_MS, animate = false, listening = true) shouldBe false
        wave.level shouldBe 0f
    }
})
