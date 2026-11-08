// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class BookQuoteCatalogTest : FunSpec({
    val assets = File("src/main/assets/book-quotes").let { if (it.exists()) it else File("app/src/main/assets/book-quotes") }
    val books = BookQuoteCatalog.readIndex(File(assets,"index.tsv").reader())
    test("bundled source contains every book and chapter with unique ordered verses") {
        books.size shouldBe 66
        books.sumOf { it.chapters } shouldBe 1189
        books.sumOf { book ->
            val verses = BookQuoteCatalog.readBook(File(assets,"${book.id}.tsv").reader(),book)
            verses.map { it.chapter }.distinct() shouldBe (1..book.chapters).toList()
            verses.size
        } shouldBe 31021
    }
    test("real Romans 3 range formats verse markers and one reference") {
        val book = books.first { it.id == "ROM" }
        val verses = BookQuoteCatalog.readBook(File(assets,"ROM.tsv").reader(),book).filter { it.chapter == 3 && it.number in 11..14 }
        val passage = BookQuoteCatalog.Passage(book,verses)
        passage.reference shouldBe "罗马书 3:11-14"
        passage.format().contains("¹¹没有明白的") shouldBe true
        passage.format().endsWith("（罗马书 3:11-14）") shouldBe true
    }
    test("queued ranges cross chapter and book without replacing the prior selection") {
        val book = books.first()
        val verses = BookQuoteCatalog.readBook(File(assets,"GEN.tsv").reader(),book)
        val selection = BookQuoteSelection()
        val first = BookQuoteCatalog.Passage(book, verses.filter { it.chapter == 1 && it.number == 31 })
        val second = BookQuoteCatalog.Passage(book, verses.filter { it.chapter == 2 && it.number == 1 })
        selection.append(first); selection.append(second)
        selection.size shouldBe 2
        selection.text() shouldBe first.format() + "\n" + second.format()
        selection.clear(); selection.text() shouldBe ""
    }
    test("malformed data and oversized quote drafts are rejected") {
        shouldThrow<IllegalArgumentException> { BookQuoteCatalog.readIndex("../x\t书\t书\t1\t0".reader()) }
        val book = books.first()
        shouldThrow<IllegalArgumentException> { BookQuoteCatalog.readBook("1\t2\t乙\n1\t1\t甲".reader(),book) }
        val huge = BookQuoteCatalog.Passage(book,List(257) { BookQuoteCatalog.Verse(1,it+1,"字") })
        shouldThrow<IllegalArgumentException> { BookQuoteSelection().append(huge) }
    }
})
