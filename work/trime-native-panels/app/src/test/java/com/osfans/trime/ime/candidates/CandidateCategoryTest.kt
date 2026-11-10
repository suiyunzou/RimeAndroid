// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.candidates

import com.osfans.trime.core.CandidateProto
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CandidateCategoryTest : FunSpec({
    fun candidate(text: String, comment: String = "") = CandidateProto(text, comment, "")

    test("local correction and sentence metadata select the emphasis group") {
        CandidatePresentation.classify(candidate("你好", "\u2063local:correction|nihao|ni hao")) shouldBe CandidateCategory.Correction
        CandidatePresentation.classify(candidate("你好", "\u2063local:sentence|nihao|ni hao")) shouldBe CandidateCategory.Sentence
        CandidatePresentation.classify(candidate("你好", "用户词典")) shouldBe CandidateCategory.Text
        CandidatePresentation.classify(candidate("你好")) shouldBe CandidateCategory.Text
    }

    test("engine type wins when the native binding exposes it") {
        CandidatePresentation.classify(candidate("hi"), "prediction") shouldBe CandidateCategory.Prediction
        CandidatePresentation.classify(candidate("hi"), "completion") shouldBe CandidateCategory.Prediction
        CandidatePresentation.classify(candidate("hi"), "sentence") shouldBe CandidateCategory.Sentence
        CandidatePresentation.classify(candidate("hi"), "correction") shouldBe CandidateCategory.Correction
        CandidatePresentation.classify(candidate("hi"), null) shouldBe CandidateCategory.Text
        // A comment marker still classifies a candidate without an engine type.
        CandidatePresentation.classify(candidate("你好", "\u2063local:correction|nihao|ni hao"), null) shouldBe CandidateCategory.Correction
    }

    test("emoji-only candidates are recognised without catching ordinary text") {
        listOf("😀", "👨‍👩‍👧‍👦", "❤️", "1️⃣", "🇨🇳", "☺️", "™️").forEach {
            CandidatePresentation.classify(candidate(it)) shouldBe CandidateCategory.Emoji
        }
        listOf("你好", "😀你好", "（＾▽＾）", "→", "abc", "", "123").forEach {
            CandidatePresentation.classify(candidate(it)) shouldBe CandidateCategory.Text
        }
    }

    test("accent run is only contiguous when the engine already grouped it") {
        val categories = listOf(
            candidate("错", "\u2063local:correction|cuo|cuo"),
            candidate("整句", "\u2063local:sentence|zheng|zheng ju"),
            candidate("甲"),
            candidate("乙"),
        )
        CandidatePresentation.accentRunIsContiguous(categories.map { CandidatePresentation.classify(it) }) shouldBe true

        // Interleaved engine order: painting only the emphasis rows would make
        // the strip alternate blue/black, so the caller disables the accent.
        val interleaved = listOf(
            CandidatePresentation.classify(candidate("错", "\u2063local:correction|cuo|cuo")),
            CandidatePresentation.classify(candidate("甲")),
            CandidatePresentation.classify(candidate("整句", "\u2063local:sentence|zheng|zheng ju")),
        )
        CandidatePresentation.accentRunIsContiguous(interleaved) shouldBe false
    }

    test("accent run treats emoji and text as neutral") {
        CandidatePresentation.accentRunIsContiguous(emptyList()) shouldBe true
        CandidatePresentation.accentRunIsContiguous(
            listOf(CandidateCategory.Text, CandidateCategory.Emoji, CandidateCategory.Emoji),
        ) shouldBe true
        CandidatePresentation.accentRunIsContiguous(listOf(CandidateCategory.Correction)) shouldBe true
        CandidatePresentation.accentRunIsContiguous(
            listOf(CandidateCategory.Correction, CandidateCategory.Emoji, CandidateCategory.Sentence),
        ) shouldBe false
    }

    test("only externally cleared editors hide idle prediction candidates") {
        // Normal commit: the user still wants next-word suggestions.
        CandidatePresentation.shouldHideIdleCandidates(
            suppressIdle = false,
            compositionLength = 0,
            candidateCount = 5,
        ) shouldBe false
        // A live composition is real input, never hidden.
        CandidatePresentation.shouldHideIdleCandidates(
            suppressIdle = true,
            compositionLength = 3,
            candidateCount = 5,
        ) shouldBe false
        // Nothing to hide.
        CandidatePresentation.shouldHideIdleCandidates(
            suppressIdle = true,
            compositionLength = 0,
            candidateCount = 0,
        ) shouldBe false
        // No response identity: keep the conservative default.
        CandidatePresentation.shouldHideIdleCandidates(
            suppressIdle = true,
            compositionLength = null,
            candidateCount = 5,
        ) shouldBe false
        // External clear plus an empty-input candidate list: hide it.
        CandidatePresentation.shouldHideIdleCandidates(
            suppressIdle = true,
            compositionLength = 0,
            candidateCount = 5,
        ) shouldBe true
    }
})
