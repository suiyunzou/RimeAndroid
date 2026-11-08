// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class FeedbackTuningProfileTest : FunSpec({
    test("invalid persisted settings cannot reach unsupported waveform parameters") {
        FeedbackTuningProfile("unknown",900,-3,0,800,900).bounded() shouldBe FeedbackTuningProfile("system",100,0,1,255,40)
    }
    test("sound respects all disabling conditions including release and mute") {
        fun eligible(enabled:Boolean=true,normal:Boolean=true,system:Int=2,volume:Int=65,release:Boolean=false,releaseSound:Boolean=false)=FeedbackTuningProfile.shouldPlay(enabled,normal,system,volume,release,releaseSound)
        eligible() shouldBe true
        eligible(enabled=false) shouldBe false;eligible(normal=false) shouldBe false;eligible(system=0) shouldBe false
        eligible(volume=0) shouldBe false;eligible(release=true) shouldBe false;eligible(release=true,releaseSound=true) shouldBe true
    }
    test("haptic respects system master silent strength and view controls") {
        FeedbackTuningProfile.shouldVibrate(true,true,false,128,true) shouldBe true
        FeedbackTuningProfile.shouldVibrate(false,true,false,128,true) shouldBe false
        FeedbackTuningProfile.shouldVibrate(true,false,false,128,true) shouldBe false
        FeedbackTuningProfile.shouldVibrate(true,true,true,128,true) shouldBe false
        FeedbackTuningProfile.shouldVibrate(true,true,false,0,true) shouldBe false
        FeedbackTuningProfile.shouldVibrate(true,true,false,128,false) shouldBe false
    }
    test("profiles retain separate press release and tone choices") {
        val normal=FeedbackTuningProfile("soft",27,50,7,40,5,true).bounded()
        val delete=FeedbackTuningProfile("deep",74,200,16,0,2,false).bounded()
        normal.tone shouldBe "soft";normal.releaseSound shouldBe true
        delete.volume shouldBe 74;delete.releaseStrength shouldBe 0
        normal.copy(volume=0).bounded().pressStrength shouldBe 50
    }
})
