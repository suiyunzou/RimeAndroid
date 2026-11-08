// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/** Bounded, data-only TSV / Rime dictionary import. YAML is never executed. */
object LocalDictionaryParser {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_ENTRIES = 100_000
    data class Entry(val text: String, val code: String, val weight: Int = 100) {
        val key get() = text + "\t" + code.replace(" ", "")
        fun line() = "$text\t$code\t$weight"
    }
    data class Result(val entries: List<Entry>, val invalid: Int, val duplicates: Int, val errors: List<Int>)
    fun parse(bytes: ByteArray): Result {
        require(bytes.size <= MAX_BYTES) { "文件超过 8 MB，请先拆分词库" }
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: Exception) { throw IllegalArgumentException("文件不是有效的 UTF-8 文本") }
        require(!text.contains('\u0000')) { "不支持二进制词库，请选择 TSV 或 dict.yaml 文本" }
        val lines = text.lineSequence().toList()
        val yamlStart = lines.indexOfFirst { it.trim().isNotEmpty() && !it.trimStart().startsWith('#') }
        var start = 0
        if (yamlStart >= 0 && lines[yamlStart].trim() == "---") {
            val end = (yamlStart + 1 until lines.size).firstOrNull { lines[it].trim() == "..." }
                ?: throw IllegalArgumentException("Rime 词库缺少正文分隔符 ...")
            val header = lines.subList(yamlStart + 1, end)
            val columnsIndex = header.indexOfFirst { it.trimStart().startsWith("columns:") }
            if (columnsIndex >= 0) {
                val inline = header[columnsIndex].substringAfter(':').trim()
                val columns = if (inline.startsWith('[') && inline.endsWith(']')) inline.drop(1).dropLast(1).split(',').map { it.trim() }
                    else header.drop(columnsIndex + 1).takeWhile { it.trimStart().startsWith('-') }.map { it.trim().removePrefix("-").trim() }
                require(columns == listOf("text", "code", "weight") || columns == listOf("text", "code")) { "仅支持 text、code、weight 顺序的 Rime 词库" }
            }
            start = end + 1
        }
        val entries = LinkedHashMap<String, Entry>()
        var invalid = 0; var duplicates = 0; val errors = mutableListOf<Int>()
        for (index in start until lines.size) {
            val line = lines[index].trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith('#')) continue
            val parts = line.split('\t')
            val entry = if (parts.size in 2..3) parseEntry(parts[0], parts[1], parts.getOrNull(2)) else null
            if (entry == null) { invalid++; if (errors.size < 8) errors.add(index + 1); continue }
            if (entry.key in entries) duplicates++ else entries[entry.key] = entry
            require(entries.size <= MAX_ENTRIES) { "词条超过 10 万条，请先拆分词库" }
        }
        require(entries.isNotEmpty()) { if (invalid == 0) "词库中没有词条" else "未找到有效词条；每行需用制表符分隔词语与拼音" }
        return Result(entries.values.toList(), invalid, duplicates, errors)
    }
    fun parseEntry(text: String, pinyin: String, weight: String?): Entry? {
        val word = text.trim()
        if (word.isEmpty() || word.length > 80 || word.any { it.isISOControl() }) return null
        val toned = pinyin.trim().lowercase(Locale.ROOT).replace("u:", "v")
            .replace('ü', 'v').replace('ǖ', 'v').replace('ǘ', 'v').replace('ǚ', 'v').replace('ǜ', 'v')
        val code = Normalizer.normalize(toned, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("[1-5](?=[ '\\s]|$)"), "").replace("'", " ").replace(Regex("\\s+"), " ")
        if (code.isBlank() || code.length > 160 || !code.matches(Regex("[a-z]+(?: [a-z]+)*"))) return null
        val number = weight?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: if (weight.isNullOrBlank()) 100 else return null
        if (number !in 0..1_000_000_000) return null
        return Entry(word, code, number)
    }
}

/** Only owns smart_imported.tsv. Replacing the file changes one complete revision at once. */
class LocalDictionaryStore(private val root: File, private val replace: (File, File) -> Unit = { from, to ->
    if (!from.renameTo(to)) throw IOException("无法原子保存词库")
}) {
    val file get() = File(root, "smart_imported.tsv")
    fun read(): List<LocalDictionaryParser.Entry> = synchronized(lock) {
        if (!file.exists()) return@synchronized emptyList()
        require(file.length() <= LocalDictionaryParser.MAX_BYTES) { "已存词库超出大小限制" }
        val bytes = file.readBytes()
        if (bytes.toString(Charsets.UTF_8).lineSequence().all { it.isBlank() || it.startsWith('#') }) emptyList()
        else LocalDictionaryParser.parse(bytes).entries
    }
    fun append(entries: List<LocalDictionaryParser.Entry>): Int = synchronized(lock) {
        val current = read().associateByTo(LinkedHashMap()) { it.key }
        val count = current.size
        entries.forEach { if (it.key !in current) current[it.key] = it }
        require(current.size <= LocalDictionaryParser.MAX_ENTRIES) { "合并后超过 10 万条，未修改已有词库" }
        if (current.size != count) save(current.values.toList())
        current.size - count
    }
    fun remove(key: String): Boolean = synchronized(lock) {
        val current = read(); val next = current.filterNot { it.key == key }
        if (current.size == next.size) false else { save(next); true }
    }
    private fun save(entries: List<LocalDictionaryParser.Entry>) {
        check(root.exists() || root.mkdirs()) { "无法创建本地词库目录" }
        val destination = file.canonicalFile
        require(destination.parentFile == root.canonicalFile) { "词库路径无效" }
        val bytes = ("# revision " + UUID.randomUUID() + "\n" + entries.joinToString("\n") { it.line() } + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= LocalDictionaryParser.MAX_BYTES) { "合并后超过 8 MB，未修改已有词库" }
        val temporary = File(root, "smart_imported-${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            replace(temporary, destination)
        } finally { if (temporary.exists()) temporary.delete() }
    }
    companion object { private val lock = Any() }
}
