// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PinyinLensTextTest : FunSpec({
    test("long raw input keeps the selected end and start visible") {
        val raw = "abcdefghijklmnopqrstuvwxyz"
        PinyinLensText.window(raw + "|", 6) shouldBe "…uvwxyz|"
        PinyinLensText.window("|" + raw, 6) shouldBe "|abcdef…"
        PinyinLensText.window("abcdefghijklm|nopqrstuvwxyz", 6) shouldBe "…klm|nop…"
    }
    test("lens boundaries keep UTF16 surrogate pairs whole") {
        PinyinLensText.window("ab😀|cd", 3) shouldBe "…😀|cd"
        PinyinLensText.window("|", 3) shouldBe "|"
    }
    test("only matching actual correction metadata displays a suggestion") {
        val correction = CorrectionPresentation.Hint("bihao", "ni'hao", false)
        PinyinLensText.matchingSuggestion("bi|hao", correction) shouldBe "ni'hao"
        PinyinLensText.matchingSuggestion("ni|hao", correction) shouldBe null
        PinyinLensText.matchingSuggestion("bi|hao", null) shouldBe null
        PinyinLensText.matchingSuggestion("ni|hao", CorrectionPresentation.Hint("nihao", "ni'hao", false)) shouldBe null
        PinyinLensText.matchingSuggestion("bi|hao", correction.copy(sentence = true)) shouldBe null
    }
})
