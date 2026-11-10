// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class TailCompletionTest : FunSpec({
    test("considers an alternate tail boundary even when preedit already covers the input") {
        TailCompletion.isInputEligible("mingtia", "ming ti a", 7) shouldBe true
        TailCompletion.trials("mingtia", mapOf("mingtian" to 32_000)).first() shouldBe
            TailCompletion.Trial("mingtian", "tian")
    }

    test("keeps the trial budget and prioritizes common full spellings") {
        val list = TailCompletion.trials("zhonggu", mapOf("zhongguo" to 400_000, "zhonggui" to 3_000))
        list shouldBe listOf(
            TailCompletion.Trial("zhongguo", "guo"),
            TailCompletion.Trial("zhonggui", "gui"),
        )
    }

    test("skips unsupported full spellings and demands stronger evidence for a one-letter tail") {
        TailCompletion.trials("jintiantianqihenha", emptyMap()) shouldBe emptyList()
        TailCompletion.trials("jian", mapOf("jianeng" to 40_000)) shouldBe emptyList()
        TailCompletion.trials("beij", mapOf("beijing" to 9_000)) shouldBe emptyList()
        TailCompletion.trials("beij", mapOf("beijing" to 40_000)) shouldBe
            listOf(TailCompletion.Trial("beijing", "jing"))
    }

    test("does not probe selected text, middle-caret edits, explicit delimiters, or all initials") {
        TailCompletion.isInputEligible("zhongyijian", "中一级an", 11) shouldBe false
        TailCompletion.isInputEligible("kaixi", "kai xi", 3) shouldBe false
        TailCompletion.isInputEligible("xi'an", "xi'an", 5) shouldBe false
        TailCompletion.trials("bjdx", emptyMap()) shouldBe emptyList()
    }

    test("allows librime's display caret only at the end of an otherwise valid preedit") {
        TailCompletion.isInputEligible("mingtia", "ming ti a\u2038", 7) shouldBe true
        TailCompletion.isInputEligible("mingtia", "ming\u2038 ti a", 4) shouldBe false
        TailCompletion.isInputEligible("zhongyijian", "中一级an\u2038", 11) shouldBe false
    }
})
