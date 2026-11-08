// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import java.io.Reader

/** Immutable, bounded local data; this class has no Android or network dependency. */
object BookQuoteCatalog {
    data class Book(val id: String, val name: String, val shortName: String, val chapters: Int, val testament: Int)
    data class Verse(val chapter: Int, val number: Int, val text: String)
    data class Passage(val book: Book, val verses: List<Verse>) {
        init { require(verses.isNotEmpty()) }
        val reference: String get() {
            val first = verses.first(); val last = verses.last()
            val end = when {
                first == last -> ""
                first.chapter == last.chapter -> "-${last.number}"
                else -> "-${last.chapter}:${last.number}"
            }
            return "${book.name} ${first.chapter}:${first.number}$end"
        }
        fun format(): String = verses.joinToString(" ") { superscript(it.number) + it.text } + "（$reference）"
    }
    fun readIndex(reader: Reader): List<Book> = reader.buffered().useLines { lines ->
        lines.filter { it.isNotBlank() }.take(67).map { line ->
            val p = line.split('\t'); require(p.size == 5)
            require(p[0].matches(Regex("[A-Z0-9]{3}")))
            Book(p[0], p[1], p[2], p[3].toInt().also { require(it in 1..150) }, p[4].toInt().also { require(it in 0..1) })
        }.toList().also { require(it.size in 1..66 && it.map(Book::id).distinct().size == it.size) }
    }
    fun readBook(reader: Reader, book: Book): List<Verse> = reader.buffered().useLines { lines ->
        lines.filter { it.isNotBlank() }.take(3001).map { line ->
            val p = line.split('\t', limit = 3); require(p.size == 3)
            Verse(p[0].toInt().also { require(it in 1..book.chapters) }, p[1].toInt().also { require(it in 1..200) },
                p[2].also { require(it.isNotEmpty() && it.length <= 4096) })
        }.toList().also { verses ->
            require(verses.isNotEmpty() && verses.size <= 3000)
            require(verses.zipWithNext().all { (a,b) -> b.chapter > a.chapter || (a.chapter == b.chapter && b.number > a.number) })
        }
    }
    private fun superscript(value: Int) = value.toString().map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it - '0'] }.joinToString("")
}

/** Explicit additions preserve order, including ranges from different books or chapters. */
class BookQuoteSelection {
    private val selections = mutableListOf<BookQuoteCatalog.Passage>()
    val size get() = selections.size
    val summary get() = selections.joinToString("；") { it.reference }
    fun clear() = selections.clear()
    fun append(passage: BookQuoteCatalog.Passage) {
        val all = selections + passage
        require(all.size <= 32 && all.sumOf { it.verses.size } <= 256) { "每次最多引用256节" }
        require(all.sumOf { it.format().length } <= 32768) { "引用文字过长" }
        selections.add(passage)
    }
    fun text(current: BookQuoteCatalog.Passage? = null): String {
        val all = if (current == null) selections else selections + current
        require(all.size <= 32 && all.sumOf { it.verses.size } <= 256) { "每次最多引用256节" }
        return all.joinToString("\n") { it.format() }.also { require(it.length <= 32768) { "引用文字过长" } }
    }
}
