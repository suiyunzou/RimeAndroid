// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PinyinComposingScrubberTest : FunSpec({
    test("accumulates horizontal steps at actual display density") {
        val scrub = PinyinComposingScrubber(2f)
        scrub.begin("nihao")
        scrub.move(-23f, 0f) shouldBe null
        scrub.move(-2f, 0f) shouldBe 4
        scrub.preview shouldBe "niha|o"
        scrub.move(-47f, 0f) shouldBe 2
        scrub.preview shouldBe "ni|hao"
    }
    test("vertical edges move to raw beginning and ending") {
        val scrub = PinyinComposingScrubber(1f)
        scrub.begin("nihao", 2)
        scrub.move(0f, -25f) shouldBe null
        scrub.move(0f, -1f) shouldBe 0
        scrub.move(0f, -2f) shouldBe null
        scrub.move(0f, 54f) shouldBe 5
        scrub.move(120f, 0f) shouldBe null
    }
    test("cancelled gesture cannot change cursor and a new gesture resets residual") {
        val scrub = PinyinComposingScrubber(1f)
        scrub.begin("nihao")
        scrub.move(-11f, 0f) shouldBe null
        scrub.end()
        scrub.move(-50f, 0f) shouldBe null
        scrub.begin("wo", 1)
        scrub.move(-1f, 0f) shouldBe null
        scrub.preview shouldBe "w|o"
    }
    test("empty input and oversized deltas remain in bounds") {
        val scrub = PinyinComposingScrubber(1f)
        scrub.begin("")
        scrub.move(-1000f, -1000f) shouldBe null
        scrub.begin("nihao", 2)
        scrub.move(-1000f, 0f) shouldBe 0
        scrub.move(2000f, 0f) shouldBe 5
    }
    test("non ASCII raw input uses whole code points and explicit UTF8 offsets") {
        val scrub = PinyinComposingScrubber(1f)
        scrub.begin("a😀b")
        scrub.move(-12f, 0f) shouldBe 3
        scrub.rimeCaretPosition() shouldBe 5
        scrub.move(-12f, 0f) shouldBe 1
        scrub.rimeCaretPosition() shouldBe 1
        scrub.begin("a😀b", 2)
        scrub.cursorIndex shouldBe 1
    }
})
