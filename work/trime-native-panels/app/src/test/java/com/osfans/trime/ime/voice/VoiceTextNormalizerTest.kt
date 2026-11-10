// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class VoiceTextNormalizerTest : FunSpec({
    test("digit sequences become Arabic digits") {
        VoiceTextNormalizer.normalizeNumbers("我的编号是一三八零零") shouldBe "我的编号是13800"
        VoiceTextNormalizer.normalizeNumbers("二零二六年十一月") shouldBe "2026年11月"
    }

    test("numbers with units keep their value") {
        VoiceTextNormalizer.normalizeNumbers("十五个") shouldBe "15个"
        VoiceTextNormalizer.normalizeNumbers("一百二十三次") shouldBe "123次"
        VoiceTextNormalizer.normalizeNumbers("三千五百块") shouldBe "3500块"
        VoiceTextNormalizer.normalizeNumbers("一万二千") shouldBe "12000"
        VoiceTextNormalizer.normalizeNumbers("一亿二千万") shouldBe "120000000"
    }

    test("lone characters and ordinary words are untouched") {
        VoiceTextNormalizer.normalizeNumbers("一起吃饭") shouldBe "一起吃饭"
        VoiceTextNormalizer.normalizeNumbers("我们走吧") shouldBe "我们走吧"
        VoiceTextNormalizer.normalizeNumbers("第二") shouldBe "第2"
    }

    test("pauses become commas and the sentence is terminated") {
        VoiceTextNormalizer
            .punctuate("今天天气不错我们出去走走吧", listOf("今天天气不错"))
            .shouldBe("今天天气不错，我们出去走走吧。")
        VoiceTextNormalizer.punctuate("hello world", emptyList()) shouldBe "hello world."
        VoiceTextNormalizer.punctuate("已经结束。", emptyList()) shouldBe "已经结束。"
    }

    test("pause prefixes insert in document order") {
        VoiceTextNormalizer
            .punctuate("我们开始工作吧", listOf("我们开始", "我们开始工作"))
            .shouldBe("我们开始，工作吧。")
    }
})
