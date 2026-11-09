// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
class ChineseLunarTextTest : FunSpec({
    test("sexagenary year leap month and day vocabulary") {
        ChineseLunarText.format(41,1,1,false,0,false) shouldBe "甲辰年正月初一"
        ChineseLunarText.format(40,2,1,true,0,false) shouldBe "癸卯年闰二月初一"
        ChineseLunarText.format(1,11,20,false,0,false) shouldBe "甲子年冬月二十"
        ChineseLunarText.format(60,12,30,false,0,false) shouldBe "癸亥年腊月三十"
        ChineseLunarText.format(1,1,11,false,0,false) shouldBe "甲子年正月十一"
        ChineseLunarText.format(1,1,29,false,0,false) shouldBe "甲子年正月廿九"
    }
    test("traditional two-hour boundaries retain civil date") {
        ChineseLunarText.format(1,1,1,false,23,true) shouldBe "甲子年正月初一 子时"
        ChineseLunarText.format(1,1,1,false,0,true) shouldBe "甲子年正月初一 子时"
        ChineseLunarText.format(1,1,1,false,1,true) shouldBe "甲子年正月初一 丑时"
        ChineseLunarText.format(1,1,1,false,22,true) shouldBe "甲子年正月初一 亥时"
    }
})
