// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class ReferenceEmojiCatalogTest:FunSpec({
    test("complete shipped inventory is grouped and no source glyph is dropped") {
        val file=File("src/main/assets/emoji-reference.tsv")
        val data=file.reader().use(ReferenceEmojiCatalog::read)
        val source=file.readLines().filter{!it.startsWith('#')&&it.isNotBlank()}.map{it.substringAfter('\t')}
        source.size shouldBe 1366
        source.all{symbol->data.values.any{symbol in it}} shouldBe true
        data.getValue("faces").first() shouldBe "😀"
        data.getValue("food").contains("🍎") shouldBe true
        data.getValue("flags").contains("🇨🇳") shouldBe true
    }
    test("skin tone choices preserve the base emoji and exclude faces") {
        ReferenceEmojiCatalog.variants("👍") shouldBe listOf("👍","👍🏻","👍🏼","👍🏽","👍🏾","👍🏿")
        ReferenceEmojiCatalog.variants("😀") shouldBe emptyList()
        ReferenceEmojiCatalog.variants("") shouldBe emptyList()
    }
    test("reference emoticon strip keeps observed order and expanded inventory") {
        ReferenceEmojiCatalog.emoticons.take(7) shouldBe listOf("^_^","^o^",">_<","T_T","-_-","o_O","(^^)")
        ReferenceEmojiCatalog.emoticons.contains("(＾▽＾)") shouldBe true
    }
})
