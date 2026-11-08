package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CorrectionPresentationTest : FunSpec({
    test("ignores unmarked and malformed comments") {
        CorrectionPresentation.parse("ni hao") shouldBe null
        CorrectionPresentation.parse(CorrectionPresentation.PREFIX + "correction|") shouldBe null
        CorrectionPresentation.parse(CorrectionPresentation.PREFIX + "correction|secret123|ni hao") shouldBe null
    }
    test("correction metadata preserves original") {
        val hint = CorrectionPresentation.parse(CorrectionPresentation.PREFIX + "correction|nihso|ni hao")!!
        hint.original shouldBe "nihso"
        hint.sentence shouldBe false
        CorrectionPresentation.changedOriginalIndices(hint.original, hint.corrected) shouldBe setOf(3)
    }
    test("apostrophes and umlauts are not mistakes") {
        CorrectionPresentation.changedOriginalIndices("nv'er", "nü er").isEmpty() shouldBe true
        CorrectionPresentation.changedOriginalIndices("ni'hso", "ni hao") shouldBe setOf(4)
    }
    test("insertion deletion and empty stay in bounds") {
        val deletion = CorrectionPresentation.changedOriginalIndices("nihhao", "ni hao")
        deletion.size shouldBe 1
        (deletion.single() in 2..3) shouldBe true
        val marked = CorrectionPresentation.changedOriginalIndices("niho", "ni hao")
        marked.isNotEmpty() shouldBe true
        marked.all { it in 0..3 } shouldBe true
        CorrectionPresentation.changedOriginalIndices("", "a").isEmpty() shouldBe true
    }
    test("dictionary tones do not become typing errors") {
        val spelling = "jīn tiān tiān qì hěn hǎo"
        val hint = CorrectionPresentation.parse(CorrectionPresentation.PREFIX + "sentence|jintiantianqihenhao|" + spelling)!!
        hint.corrected shouldBe spelling
        CorrectionPresentation.changedOriginalIndices(hint.original, hint.corrected).isEmpty() shouldBe true
        CorrectionPresentation.changedOriginalIndices("nvlv", "nǚ lǜ").isEmpty() shouldBe true
        CorrectionPresentation.changedOriginalIndices("nv", "nu\u0308\u030c").isEmpty() shouldBe true
        CorrectionPresentation.changedOriginalIndices("nihso", "nǐ hǎo") shouldBe setOf(3)
        CorrectionPresentation.parse(CorrectionPresentation.PREFIX + "sentence|nihao|你好") shouldBe null
    }
})

