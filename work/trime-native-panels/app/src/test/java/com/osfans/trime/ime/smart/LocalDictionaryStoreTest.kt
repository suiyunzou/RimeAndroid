// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class LocalDictionaryStoreTest : FunSpec({
    test("TSV normalizes pinyin, preserves weight, reports bad rows and deduplicates") {
        val result = LocalDictionaryParser.parse("你好\tni hao\t88\n你好\tni'hao\t999\n女儿\tnǚ ér\n错行\n坏码\t123\n".toByteArray())
        result.entries.map { it.code } shouldBe listOf("ni hao", "nv er")
        result.entries.first().weight shouldBe 88
        result.invalid shouldBe 2
        result.duplicates shouldBe 1
        result.errors shouldBe listOf(4,5)
    }
    test("Rime dictionary header is data only and columns are checked") {
        val valid = "---\nname: local\nversion: '1'\ncolumns: [text, code, weight]\n...\n测试\tce shi\t200\n"
        LocalDictionaryParser.parse(valid.toByteArray()).entries.single().text shouldBe "测试"
        shouldThrow<IllegalArgumentException> { LocalDictionaryParser.parse(valid.replace("text, code", "code, text").toByteArray()) }
        shouldThrow<IllegalArgumentException> { LocalDictionaryParser.parse("---\nname: broken\n".toByteArray()) }
    }
    test("empty binary oversized and invalid UTF8 imports cannot replace existing data") {
        listOf(byteArrayOf(), byteArrayOf(0,0), byteArrayOf(0xc0.toByte(),0xaf.toByte()), ByteArray(LocalDictionaryParser.MAX_BYTES + 1)).forEach {
            shouldThrow<IllegalArgumentException> { LocalDictionaryParser.parse(it) }
        }
    }
    test("atomic store persists across instances, merges without overwriting and deletes only a chosen word") {
        val root = Files.createTempDirectory("local-dictionary-test").toFile()
        fun store() = LocalDictionaryStore(root) { from, to -> Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); Unit }
        val a = LocalDictionaryParser.Entry("测试", "ce shi", 20)
        val b = LocalDictionaryParser.Entry("词库", "ci ku", 30)
        try {
            File(root,"unrelated.txt").writeText("preserved")
            store().append(listOf(a)) shouldBe 1
            val revision = File(root,"smart_imported.tsv").readLines().first()
            store().append(listOf(a.copy(weight=900),b)) shouldBe 1
            store().read() shouldBe listOf(a,b)
            (File(root,"smart_imported.tsv").readLines().first() != revision) shouldBe true
            store().remove(a.key) shouldBe true
            store().read() shouldBe listOf(b)
            store().remove("absent") shouldBe false
            File(root,"unrelated.txt").readText() shouldBe "preserved"
            store().remove(b.key) shouldBe true
            store().read() shouldBe emptyList()
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }
    test("failed atomic replacement leaves previous revision intact") {
        val root = Files.createTempDirectory("local-dictionary-failure").toFile()
        try {
            val entry = LocalDictionaryParser.Entry("原词", "yuan ci")
            LocalDictionaryStore(root).append(listOf(entry))
            val failing = LocalDictionaryStore(root) { _,_ -> throw java.io.IOException("simulated failure") }
            shouldThrow<java.io.IOException> { failing.append(listOf(LocalDictionaryParser.Entry("新词", "xin ci"))) }
            LocalDictionaryStore(root).read() shouldBe listOf(entry)
            root.listFiles()!!.map { it.name } shouldBe listOf("smart_imported.tsv")
        } finally { root.listFiles()?.forEach { it.delete() }; root.delete() }
    }
})
