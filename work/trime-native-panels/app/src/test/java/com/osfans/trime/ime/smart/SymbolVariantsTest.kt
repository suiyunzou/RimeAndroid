// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SymbolVariantsTest : FunSpec({
    test("six and five follow the visible reference order") {
        SymbolVariants.forKey("6").take(10) shouldBe listOf("6", "六", "陆", "６", "⑥", "⑹", "⒍", "❻", "㊅", "㈥")
        SymbolVariants.forKey("5").take(10) shouldBe listOf("5", "五", "伍", "５", "⑤", "⑸", "⒌", "❺", "㊄", "㈤")
    }
    test("all digits retain complete legacy shortcuts without duplicates") {
        for (n in 0..9) {
            val variants = SymbolVariants.forKey(n.toString())
            variants.first() shouldBe n.toString()
            variants.distinct().size shouldBe variants.size
            variants.takeLast(3) shouldBe listOf("⁰¹²³⁴⁵⁶⁷⁸⁹"[n].toString(), (0x2080 + n).toChar().toString(), "$n\uFE0F\u20E3")
            variants.size shouldBe if (n == 0) 11 else 13
        }
    }
    test("zero has valid fallbacks instead of adjacent Unicode numbers") {
        SymbolVariants.forKey("0").take(8) shouldBe listOf("0", "〇", "零", "０", "⓪", "(0)", "0.", "⓿")
    }
    test("punctuation supports full-width and paired opening variants") {
        SymbolVariants.forKey("（").contains("《") shouldBe true
        SymbolVariants.forKey("。").contains("…") shouldBe true
        SymbolVariants.forKey("xyz").isEmpty() shouldBe true
    }
    test("mathematical supplementary characters and joined emoji stay intact") {
        SymbolVariants.categories.getValue("希腊粗体").first().codePointAt(0) shouldBe 0x1d6a8
        SymbolVariants.categories.getValue("希腊斜体").first().codePointAt(0) shouldBe 0x1d6e2
        SymbolVariants.categories.getValue("Emoji").contains("👨‍👩‍👧‍👦") shouldBe true
        SymbolVariants.categories.getValue("部首").size shouldBe 214
    }
})
