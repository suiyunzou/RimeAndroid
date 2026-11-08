package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class T9PinyinEngineTest : FunSpec({
    test("real syllables and ambiguity") {
        T9PinyinEngine.candidates("64") shouldContain "ni"
        T9PinyinEngine.candidates("426") shouldContain "hao"
        T9PinyinEngine.candidates("744") shouldContain "shi"
        T9PinyinEngine.candidates("58") shouldContain "lv"
    }
    test("empty invalid and bounded input") {
        listOf("", "1", "0", "123", "abcdef", "2345678").forEach { T9PinyinEngine.candidates(it) shouldBe emptyList() }
        T9PinyinEngine.encode("ZHUANG") shouldBe "948264"
    }
})
