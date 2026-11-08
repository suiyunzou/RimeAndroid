package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CorrectionStyleTest : FunSpec({
    test("preedit soft caret matches raw input without admitting stale or arbitrary text") {
        CorrectionStyle.matchesPreedit("xingheceshi", "xing he ce shi‸") shouldBe true
        CorrectionStyle.matchesPreedit("xingheceshi", "xing‸ he ce shi") shouldBe true
        CorrectionStyle.matchesPreedit("xingheceshi", "xing ge ce shi‸") shouldBe false
        CorrectionStyle.matchesPreedit("xingheceshi", "xing he ce shi?") shouldBe false
        val plan=CorrectionStyle.plan("xingheceshi", "xìng gé cè shì", "strike_color")
        plan.marks.any { it.kind == CorrectionStyle.Kind.ERROR } shouldBe true
        plan.byteOffset(plan.text.length, "xingheceshi") shouldBe 11
    }
    test("all six styles use the same real edit without changing raw input") {
        val raw="qugio"
        CorrectionStyle.labels.size shouldBe 6
        CorrectionStyle.plan(raw,"quguo","underline").text shouldBe "qugio"
        CorrectionStyle.plan(raw,"quguo","plain").text shouldBe "qugio"
        CorrectionStyle.plan(raw,"quguo","highlight").text shouldBe "quguo"
        CorrectionStyle.plan(raw,"quguo","strike_background").text shouldBe "qugiuo"
        CorrectionStyle.plan(raw,"quguo","strike_color").text shouldBe "qugiuo"
        CorrectionStyle.plan(raw,"quguo","inline").text shouldBe "qugi→uo"
        raw shouldBe "qugio"
    }
    test("expanded replacement and arrow map to original byte caret") {
        val plan=CorrectionStyle.plan("qugio","quguo","inline")
        plan.byteOffset(3,"qugio") shouldBe 3
        plan.byteOffset(4,"qugio") shouldBe 3
        plan.byteOffset(5,"qugio") shouldBe 3
        plan.byteOffset(6,"qugio") shouldBe 4
        plan.byteOffset(7,"qugio") shouldBe 5
        plan.byteOffset(-5,"qugio") shouldBe 0
        plan.byteOffset(100,"qugio") shouldBe 5
    }
    test("insertions deletions and apostrophes have bounded monotonic mapping") {
        CorrectionStyle.plan("niho","nihao","underline").marks.isNotEmpty() shouldBe true
        for((raw,target) in listOf("niho" to "nihao","ni'hao" to "ni hao","nihao" to "niho","" to "ni"))
            for(style in CorrectionStyle.labels.keys) {
                val plan=CorrectionStyle.plan(raw,target,style)
                plan.originalOffsets.size shouldBe plan.text.length+1
                plan.originalOffsets.all{it in 0..raw.length} shouldBe true
                plan.originalOffsets.zipWithNext().all{it.first<=it.second} shouldBe true
                plan.byteOffset(plan.text.length,raw) shouldBe raw.toByteArray().size
            }
    }
    test("unicode byte offsets never split surrogate pairs") {
        val raw="nü'hao";val plan=CorrectionStyle.plan(raw,"nvhao","highlight")
        plan.byteOffset(2,raw) shouldBe 4
        val emoji="ni😀hao";val fallback=CorrectionStyle.plan(emoji,"nihao","inline")
        fallback.byteOffset(3,emoji) shouldBe 2
        fallback.byteOffset(4,emoji) shouldBe 6
        fallback.byteOffset(999,emoji) shouldBe emoji.toByteArray().size
    }
    test("malformed and oversized values stay literal") {
        for (raw in listOf("a".repeat(90), "1234", "你好")) {
            CorrectionStyle.plan(raw,"abc","highlight").text shouldBe raw
        }
        CorrectionStyle.plan("nihao","nihao","plain").marks.isEmpty() shouldBe true
    }
    test("tone-marked dictionary suggestions keep all styles and caret mappings") {
        for (style in CorrectionStyle.labels.keys) {
            CorrectionStyle.plan("ni'hso", "nǐ hǎo", style) shouldBe
                CorrectionStyle.plan("ni'hso", "ni hao", style)
            CorrectionStyle.plan("nv'lv", "nǚ lǜ", style) shouldBe
                CorrectionStyle.plan("nv'lv", "nv lv", style)
            CorrectionStyle.plan("nv'lv", "nu\u0308\u030c lu\u0308\u0300", style) shouldBe
                CorrectionStyle.plan("nv'lv", "nv lv", style)
        }
        CorrectionStyle.plan("nihso", "nǐ hǎo", "highlight").text shouldBe "nihao"
        CorrectionStyle.plan("nihso", "nǐ hǎo", "underline").marks shouldBe
            listOf(CorrectionStyle.Mark(3, 4, CorrectionStyle.Kind.UNDERLINE))
        CorrectionStyle.plan("jintiantianqihenhao", "jīn tiān tiān qì hěn hǎo", "underline").marks.isEmpty() shouldBe true
    }
})
