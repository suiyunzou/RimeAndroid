package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class KeycapPunctuationTest : FunSpec({
    test("Chinese mode shows full-width marks and the opening half of pairs") {
        mapOf(
            ',' to "，", '.' to "。", '!' to "！", '?' to "？", ':' to "：", ';' to "；",
            '(' to "（", ')' to "（", '[' to "【", ']' to "【", '{' to "｛", '}' to "｛",
            '"' to "“", '\'' to "‘", '<' to "<", '>' to "<",
            '”' to "“", '’' to "‘", '》' to "《", '〉' to "〈", '」' to "「", '』' to "『",
            '】' to "【", '〕' to "〔", '）' to "（", '］' to "［", '｝' to "｛",
            '、' to "、", '…' to "…",
        ).forEach { (raw, expected) ->
            KeycapPunctuation.display(raw.toString(), ascii = false) shouldBe expected
        }
    }

    test("ASCII mode shows ASCII marks and the opening half of pairs") {
        mapOf(
            ',' to ",", '.' to ".", '!' to "!", '?' to "?", ':' to ":", ';' to ";",
            '(' to "(", ')' to "(", '[' to "[", ']' to "[", '{' to "{", '}' to "{",
            '"' to "\"", '\'' to "'", '<' to "<", '>' to "<",
            '，' to ",", '。' to ".", '！' to "!", '？' to "?", '：' to ":", '；' to ";",
            '（' to "(", '）' to "(", '【' to "[", '】' to "[", '｛' to "{", '｝' to "{",
            '“' to "\"", '”' to "\"", '‘' to "'", '’' to "'", '《' to "《", '》' to "《",
            '、' to ",", '…' to "...",
            '〉' to "〈", '」' to "「", '』' to "『", '〕' to "〔", '］' to "［",
        ).forEach { (raw, expected) ->
            KeycapPunctuation.display(raw.toString(), ascii = true) shouldBe expected
        }
    }

    test("two-character pairs collapse to their opening half") {
        mapOf("“”" to "“", "\"\"" to "“", "''" to "‘", "<>" to "<", "《》" to "《",
            "「」" to "「", "『』" to "『", "()" to "（", "（）" to "（").forEach { (raw, expected) ->
            KeycapPunctuation.display(raw, ascii = false) shouldBe expected
        }
        mapOf("“”" to "\"", "\"\"" to "\"", "''" to "'", "<>" to "<", "()" to "(").forEach { (raw, expected) ->
            KeycapPunctuation.display(raw, ascii = true) shouldBe expected
        }
    }

    test("texts that are not exactly one pair stay unchanged") {
        listOf("ab", "...", "，。", "““", "」「", "abc").forEach { raw ->
            KeycapPunctuation.display(raw, ascii = false) shouldBe raw
            KeycapPunctuation.display(raw, ascii = true) shouldBe raw
        }
        KeycapPunctuation.display("", ascii = false) shouldBe ""
    }

    test("the commit table never folds pairs; only the keycap does") {
        KeycapPunctuation.localized(")", ascii = false) shouldBe "）"
        KeycapPunctuation.localized("“", ascii = true) shouldBe "\""
        KeycapPunctuation.localized("“”", ascii = false) shouldBe "“”"
        KeycapPunctuation.localized("<", ascii = false) shouldBe "<"
        KeycapPunctuation.localized(">", ascii = false) shouldBe ">"
        KeycapPunctuation.localized("、", ascii = true) shouldBe ","
        KeycapPunctuation.localized("…", ascii = true) shouldBe "..."
        KeyboardPunctuation.localized("，", ascii = true) shouldBe ","
        KeyboardPunctuation.localized("”", ascii = true) shouldBe "\""
        KeyboardPunctuation.localized("》", ascii = false) shouldBe "》"
        KeyboardPunctuation.isLocalizable(",") shouldBe true
        KeyboardPunctuation.isLocalizable("<") shouldBe false
        KeyboardPunctuation.isLocalizable("“”") shouldBe false
    }

    test("only ASCII single marks are pen-drawn and only full-width marks use the font") {
        "!?:;.,-+_/()\"'".forEach { KeycapPunctuation.isHandDrawnMark(it) shouldBe true }
        listOf('，', '。', '！', '？', '：', '；', '（', '）', '【', '】', '“', '”', '…', '、')
            .forEach { KeycapPunctuation.isHandDrawnMark(it) shouldBe false }
        KeycapPunctuation.isFullwidthMark("，") shouldBe true
        KeycapPunctuation.isFullwidthMark("“") shouldBe true
        KeycapPunctuation.isFullwidthMark(",") shouldBe false
        KeycapPunctuation.isFullwidthMark("...") shouldBe false
        KeycapPunctuation.isFullwidthMark("完成") shouldBe false
    }

    test("default corner hints render Chinese glyphs in Chinese mode") {
        mapOf(
            "c" to "，", "v" to "？", "b" to "！", "n" to "。", "s" to "：", "d" to "；",
            "f" to "（", "g" to "（", "l" to "“", "x" to "‘", "m" to "…",
        ).forEach { (key, expected) ->
            val hint = KeyBindingLabels.defaultHint(key)
            KeycapPunctuation.display(hint, ascii = false) shouldBe expected
        }
    }
})
