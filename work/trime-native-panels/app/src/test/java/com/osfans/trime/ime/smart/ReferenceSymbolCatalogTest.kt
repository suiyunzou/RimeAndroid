// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ReferenceSymbolCatalogTest : FunSpec({
    test("observed root order and numeric submenu remain stable") {
        ReferenceSymbolCatalog.groups.map { it.label } shouldBe
            listOf("标点", "数字", "数学", "箭头", "图形", "拉丁", "字体", "希腊", "文字", "汉字", "历法", "标志娱乐")
        ReferenceSymbolCatalog.group(ReferenceSymbolCatalog.defaultGroupId)?.label shouldBe "标点"
        ReferenceSymbolCatalog.group("数字")!!.pages.map { it.label } shouldBe listOf(
            "圈数字", "括号数字", "点号数字", "中文数字", "上标", "下标", "全角数字", "字体数字", "罗马数字", "数字与数词", "分数",
        )
        ReferenceSymbolCatalog.group("numbers")!!.pages.first().id shouldBe "circled"
        ReferenceSymbolCatalog.group("missing") shouldBe null
        ReferenceSymbolCatalog.groups.none { it.id in listOf("emoticons", "emoji") } shouldBe true
    }

    test("circled numbers continue numerically across Unicode blocks") {
        val circles = ReferenceSymbolCatalog.group("numbers")!!.pages.first().symbols
        circles.size shouldBe 51
        listOf(0, 1, 20, 21, 35, 36, 50).map { circles[it] } shouldBe
            listOf("⓪", "①", "⑳", "㉑", "㉟", "㊱", "㊿")
        ReferenceSymbolCatalog.group("han")!!.pages.first().symbols.size shouldBe 214
    }

    test("Roman and Greek pages interleave matching upper and lower forms") {
        val roman = ReferenceSymbolCatalog.group("numbers")!!.pages.first { it.id == "roman" }.symbols
        roman.take(6) shouldBe listOf("Ⅰ", "ⅰ", "Ⅱ", "ⅱ", "Ⅲ", "ⅲ")
        roman.slice(30..31) shouldBe listOf("Ⅿ", "ⅿ")
        val greek = ReferenceSymbolCatalog.group("greek")!!.pages
        greek[0].symbols.take(4) shouldBe listOf("Α", "α", "Β", "β")
        greek[0].symbols.slice(34..35) shouldBe listOf("Σ", "σ")
        greek[0].symbols.indexOf("ς").let { it >= 48 } shouldBe true
        listOf(0x1d6a8, 0x1d6e2).forEachIndexed { index, base ->
            val symbols = greek[index + 1].symbols.map { it.codePointAt(0) }
            symbols.take(4) shouldBe listOf(base, base + 26, base + 1, base + 27)
            symbols.slice(34..35) shouldBe listOf(base + 18, base + 44)
            symbols.slice(46..47) shouldBe listOf(base + 24, base + 50)
            symbols.indexOf(base + 17).let { it >= 48 } shouldBe true
            symbols.indexOf(base + 43).let { it >= 48 } shouldBe true
            symbols.toSet() shouldBe (base..base + 57).toSet()
        }
    }

    test("supplementary glyphs and joined emoji remain complete strings") {
        val greek = ReferenceSymbolCatalog.group("greek")!!.pages
        greek.map { it.label } shouldBe listOf("希腊字母", "希腊粗体字母", "希腊斜体字母")
        greek.drop(1).map { it.symbols.first().codePointAt(0) } shouldBe listOf(0x1d6a8, 0x1d6e2)
        greek.drop(1).all { it.symbols.all { glyph -> glyph.codePointCount(0, glyph.length) == 1 } } shouldBe true
        ReferenceSymbolCatalog.group("emoji")!!.pages.single().symbols.contains("👨‍👩‍👧‍👦") shouldBe true
        ReferenceSymbolCatalog.group("emoticons")!!.pages.single().symbols shouldBe SymbolVariants.categories.getValue("颜文字")
        (ReferenceSymbolCatalog.groups + ReferenceSymbolCatalog.extraGroups).all { group ->
            group.pages.all { page ->
                page.symbols.all { symbol ->
                    symbol.isNotEmpty() && symbol.codePoints().toArray().none { it in 0xd800..0xdfff }
                }
            }
        } shouldBe true
    }
})
