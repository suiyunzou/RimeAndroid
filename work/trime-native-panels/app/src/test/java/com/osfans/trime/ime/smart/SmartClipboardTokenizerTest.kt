package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SmartClipboardTokenizerTest : FunSpec({
    test("large input is bounded and keeps original source offsets") {
        val source = "1234 ".repeat(100_000)
        var analyzedCharacters = 0
        val tokens = SmartClipboardTokenizer.tokenize(source) {
            analyzedCharacters += it.length
            listOf(it)
        }
        tokens.all { it.end <= SmartClipboardTokenizer.MAX_ANALYSIS_CHARS } shouldBe true
        (analyzedCharacters <= SmartClipboardTokenizer.MAX_ANALYSIS_CHARS) shouldBe true
        source.length shouldBe 500_000
        tokens.all { source.substring(it.start, it.end) == it.text } shouldBe true
    }
    test("reference message keeps mixed parent cards and separate amount units") {
        val source = "订单RR20261117已发货，金额128.50元，明天下午送达，物流https://example.com/t/RR202611\n17，取件码A8K2"
        val tokens = SmartClipboardTokenizer.tokenize(source) { value ->
            when (value) {
                "订单RR20261117已发货" -> listOf("订单", "RR20261117", "已", "发", "货")
                "明天下午送达" -> listOf("明天", "下午", "送", "达")
                "取件码A8K2" -> listOf("取", "件码", "A8K2")
                else -> listOf(value)
            }
        }
        tokens.map { it.text } shouldBe listOf(
            "订单RR20261117已发货", "，", "金额", "128.50", "元", "，", "明天", "下午", "送达", "，", "物流",
            "https://example.com/t/RR202611", "17", "，", "取件码A8K2",
        )
        SmartClipboardTokenizer.filter(tokens, SmartClipboardTokenizer.Filter.NUMBERS).map { it.text } shouldBe listOf("128.50", "17")
        tokens.all { source.substring(it.start, it.end) == it.text } shouldBe true
        val order = tokens.first()
        val firstSplit = SmartClipboardTokenizer.split(order, order.splitPoints.first())
        firstSplit.map { it.text } shouldBe listOf("订单", "RR20261117已发货")
        val secondSplit = SmartClipboardTokenizer.split(firstSplit.last(), firstSplit.last().splitPoints.first())
        secondSplit.map { it.text } shouldBe listOf("RR20261117", "已发货")
        val thirdSplit = SmartClipboardTokenizer.split(secondSplit.last(), secondSplit.last().splitPoints.first())
        thirdSplit.map { it.text } shouldBe listOf("已", "发货")
        SmartClipboardTokenizer.split(thirdSplit.last(), 1).map { it.text } shouldBe listOf("发", "货")
        (firstSplit + secondSplit + thirdSplit).all { source.substring(it.start, it.end) == it.text } shouldBe true
    }
    test("URL stops at Chinese punctuation and never consumes following pickup text") {
        val source = "物流https://example.com/t/RR20261117?x=42，取件码A8K2，请17点前取件。"
        val tokens = SmartClipboardTokenizer.tokenize(source)
        tokens.filter { it.kind == SmartClipboardTokenizer.Kind.URL }.map { it.text } shouldBe listOf("https://example.com/t/RR20261117?x=42")
        tokens.any { it.text == "取件码A8K2" } shouldBe true
        SmartClipboardTokenizer.filter(tokens, SmartClipboardTokenizer.Filter.NUMBERS).map { it.text } shouldBe listOf("17")
    }
    test("numeric filtering does not extract digits from identifiers or URL paths") {
        val source = "订单 AB-1234 金额￥19.90元 链接 https://a.test/12345?x=42。 取件码 A8K2 8352"
        val tokens = SmartClipboardTokenizer.tokenize(source)
        tokens.filter { it.kind == SmartClipboardTokenizer.Kind.AMOUNT }.map { it.text } shouldBe listOf("19.90")
        tokens.filter { it.kind == SmartClipboardTokenizer.Kind.CODE }.map { it.text } shouldBe listOf("AB-1234", "A8K2")
        SmartClipboardTokenizer.filter(tokens, SmartClipboardTokenizer.Filter.NUMBERS).map { it.text } shouldBe listOf("19.90", "8352")
        tokens.zipWithNext().all { (a, b) -> a.end <= b.start } shouldBe true
        tokens.all { source.substring(it.start, it.end) == it.text } shouldBe true
    }
    test("separator refinement keeps source offsets and ignores invalid boundaries") {
        val token = SmartClipboardTokenizer.Token("AB-1234-56", SmartClipboardTokenizer.Kind.CODE, 10, 20)
        val parts = SmartClipboardTokenizer.split(token, 2)
        parts.map { it.text } shouldBe listOf("AB", "1234-56")
        parts.map { it.start } shouldBe listOf(10, 13)
        SmartClipboardTokenizer.split(parts.last(), 4).map { it.text } shouldBe listOf("1234", "56")
        SmartClipboardTokenizer.split(token, 1) shouldBe listOf(token)
    }
    test("empty and repeated standalone numbers retain separate occurrences") {
        SmartClipboardTokenizer.tokenize("") shouldBe emptyList()
        SmartClipboardTokenizer.tokenize("  ") shouldBe emptyList()
        val tokens = SmartClipboardTokenizer.tokenize("7 42 1234 1234")
        SmartClipboardTokenizer.filter(tokens, SmartClipboardTokenizer.Filter.NUMBERS).map { it.text } shouldBe listOf("7", "42", "1234", "1234")
    }
    test("Chinese word cards can be refined even without an ASCII separator") {
        val source = "今天送达，谢谢"
        val tokens = SmartClipboardTokenizer.tokenize(source) { value ->
            when (value) {
                "今天送达" -> listOf("今天", "送", "达")
                else -> listOf(value)
            }
        }
        tokens.map { it.text } shouldBe listOf("今天", "送达", "，", "谢谢")
        val delivered = tokens[1]
        val expanded = SmartClipboardTokenizer.expand(tokens, delivered, 1)
        expanded.map { it.text } shouldBe listOf("今天", "送达", "送", "达", "，", "谢谢")
        SmartClipboardTokenizer.expand(expanded, delivered, 1) shouldBe expanded
        expanded.all { source.substring(it.start, it.end) == it.text } shouldBe true
    }
    test("URL refinement retains parents and link provenance") {
        val token = SmartClipboardTokenizer.Token("https://example.com/t/RR1234", SmartClipboardTokenizer.Kind.URL, 0, 28)
        token.splitPoints.first() shouldBe 19
        val expanded = SmartClipboardTokenizer.expand(listOf(token), token, 19)
        expanded.map { it.text } shouldBe listOf(token.text, "https://example.com", "/t/RR1234")
        SmartClipboardTokenizer.expand(expanded, token, 19) shouldBe expanded
        SmartClipboardTokenizer.filter(expanded, SmartClipboardTokenizer.Filter.URLS) shouldBe expanded
        SmartClipboardTokenizer.split(expanded.last(), 2).map { it.text } shouldBe listOf("/t", "/RR1234")
    }
    test("supplementary Chinese characters remain whole at all refinement boundaries") {
        val source = "𠮷野"
        val token = SmartClipboardTokenizer.tokenize(source).single()
        token.splitPoints shouldBe listOf(2)
        SmartClipboardTokenizer.split(token, 2).map { it.text } shouldBe listOf("𠮷", "野")
        SmartClipboardTokenizer.split(token, 1) shouldBe listOf(token)
        val bounded = SmartClipboardTokenizer.tokenize("a".repeat(SmartClipboardTokenizer.MAX_ANALYSIS_CHARS - 1) + "𠮷")
        bounded.last().end shouldBe SmartClipboardTokenizer.MAX_ANALYSIS_CHARS - 1
    }
    test("emoji and combining sequences stay selectable as complete cards") {
        val source = "家人👨‍👩‍👧‍👦，👍🏽🇨🇳❤️1️⃣ café"
        val tokens = SmartClipboardTokenizer.tokenize(source)
        tokens.map { it.text } shouldBe listOf("家人", "👨‍👩‍👧‍👦", "，", "👍🏽", "🇨🇳", "❤️", "1️⃣", "café")
        SmartClipboardTokenizer.filter(tokens, SmartClipboardTokenizer.Filter.NUMBERS) shouldBe emptyList()
        tokens.all { source.substring(it.start, it.end) == it.text } shouldBe true
        tokens.filter { it.text == "1️⃣" || it.text == "café" }.all { it.splitPoints.isEmpty() } shouldBe true
    }
    test("negative and positive numeric amounts remain intact") {
        val tokens = SmartClipboardTokenizer.tokenize("退款-128.50元，余额+17元，编号A-1234")
        SmartClipboardTokenizer.filter(tokens, SmartClipboardTokenizer.Filter.NUMBERS).map { it.text } shouldBe listOf("-128.50", "+17")
    }
    test("incomplete native segmentation cannot silently drop source characters") {
        val source = "按时送达"
        val tokens = SmartClipboardTokenizer.tokenize(source) { listOf("送达") }
        tokens.joinToString("") { it.text } shouldBe source
        tokens.all { source.substring(it.start, it.end) == it.text } shouldBe true
    }
})


