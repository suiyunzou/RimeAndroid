// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CursorLensTextTest : FunSpec({
    test("lens never cuts a surrogate pair at the caret or edge") {
        CursorLensText.safeCaret("a😀b", 2) shouldBe 1
        CursorLensText.around("a😀b", 3, 1) shouldBe ("😀" to "b")
        CursorLensText.around("a😀b", 2, 1) shouldBe ("a" to "😀")
    }
    test("empty and out of range contexts stay bounded") {
        CursorLensText.around("", 100) shouldBe ("" to "")
        CursorLensText.around("abc", -10) shouldBe ("" to "abc")
        CursorLensText.around("abc", 99) shouldBe ("abc" to "")
    }
    test("lens follows one explicit line without revealing adjacent paragraphs") {
        CursorLensText.around("first\nsecond\nthird", 9) shouldBe ("sec" to "ond")
        CursorLensText.around("first\nsecond", 6) shouldBe ("" to "second")
    }
})
