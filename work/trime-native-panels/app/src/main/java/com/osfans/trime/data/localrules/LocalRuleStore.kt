package com.osfans.trime.data.localrules

import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The same user-owned files and limits as clean_local_rules.lua / clean_email_domains.lua. */
class LocalRuleStore(
    private val directory: File,
    private val replace: (File, File) -> Unit = { source, target ->
        check(source.renameTo(target)) { "保存失败，原文件未改动" }
    },
) {
    data class Rule(val kind: String, val trigger: String, val value: String)

    data class Snapshot<T>(val items: List<T>, internal val bytes: ByteArray?)

    fun loadRules(): Snapshot<Rule> = synchronized(lock) {
        val bytes = read(RULES_FILE, 65536)
        Snapshot(parseRules(bytes), bytes)
    }

    fun loadDomains(): Snapshot<String> = synchronized(lock) {
        val bytes = read(DOMAINS_FILE, 17000)
        Snapshot(parseDomains(bytes), bytes)
    }

    fun saveRules(rows: List<Rule>, previous: Snapshot<Rule>): Snapshot<Rule> = synchronized(lock) {
        val normalized = validateRules(rows)
        val bytes = lines(normalized.map { "${it.kind}\t${it.trigger}\t${it.value}" })
        save(RULES_FILE, bytes, previous.bytes, 65536)
        Snapshot(normalized, bytes)
    }

    fun saveDomains(domains: List<String>, previous: Snapshot<String>): Snapshot<String> = synchronized(lock) {
        val normalized = validateDomains(domains)
        val bytes = lines(normalized)
        save(DOMAINS_FILE, bytes, previous.bytes, 17000)
        Snapshot(normalized, bytes)
    }

    private fun read(name: String, limit: Int): ByteArray? {
        val file = File(directory, name)
        if (!file.exists()) return null
        require(file.isFile && file.length() <= limit) { "规则文件过大或无法读取" }
        return file.inputStream().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (output.size() <= limit) {
                val count = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= limit) { "规则文件过大" }
            bytes
        }
    }

    private fun save(name: String, bytes: ByteArray, expected: ByteArray?, limit: Int) {
        require(bytes.size <= limit) { "规则文件过大" }
        fun checkUnchanged() {
            val current = read(name, limit)
            check(if (expected == null) current == null else current != null && expected.contentEquals(current)) {
                "规则已在其他位置更改，请取消编辑并切换分类后重试"
            }
        }
        checkUnchanged()
        check(directory.isDirectory || directory.mkdirs()) { "无法打开规则目录" }
        val target = File(directory, name)
        val temporary = File.createTempFile("$name.", ".tmp", directory)
        try {
            FileOutputStream(temporary).use {
                it.write(bytes)
                it.fd.sync()
            }
            checkUnchanged()
            // Android's same-directory rename replaces the destination atomically, without deleting it first.
            replace(temporary, target)
        } finally {
            temporary.delete()
        }
    }

    companion object {
        const val RULES_FILE = "clean_local_rules.tsv"
        const val DOMAINS_FILE = "clean_email_domains.txt"
        private val lock = Any()
        val DEFAULT_DOMAINS = listOf("qq.com", "gmail.com", "proton.me", "163.com", "126.com", "outlook.com", "hotmail.com", "icloud.com")
        private val symbols = setOf("#", "$", "%", "&", "*", "+", "=")
        private fun asciiLower(value: String) = value.map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")

        private fun field(raw: String, limit: Int, label: String): String {
            require(raw.none { it.code < 32 || it.code == 127 }) { "$label 不能含换行、制表符或控制字符" }
            val value = raw.trim { it == ' ' }
            require(value.codePointCount(0, value.length) in 1..limit) { "$label 限 1 至 $limit 个字符" }
            require(value.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == value) { "$label 含无效字符" }
            return value
        }

        fun normalizeRule(row: Rule): Rule {
            require(row.kind == "shortcut" || row.kind == "next") { "规则类型无效" }
            var trigger = field(row.trigger, 32, "触发内容")
            if (row.kind == "shortcut") {
                trigger = asciiLower(trigger)
                require(trigger.matches(Regex("[a-z]+")) || trigger in symbols) { "请输入字母缩写或 # $ % & * + =" }
            }
            return Rule(row.kind, trigger, field(row.value, 80, "候选内容"))
        }

        fun validateRules(rows: List<Rule>): List<Rule> {
            require(rows.size <= 128) { "最多保存 128 条候选规则" }
            val normalized = rows.map(::normalizeRule)
            require(normalized.distinct().size == normalized.size) { "相同规则已存在" }
            return normalized
        }

        fun normalizeDomain(raw: String): String {
            val domain = asciiLower(raw.trim { it in " \t\r\n\u000b\u000c" }
                .removePrefix("@").removeSuffix("."))
            require(domain.length in 1..253 && '.' in domain && !domain.startsWith('.') && !domain.endsWith('.') && ".." !in domain) {
                "域名格式应为 example.com"
            }
            require(domain.split('.').all { it.length in 1..63 && it.matches(Regex("[a-z0-9-]+")) && !it.startsWith('-') && !it.endsWith('-') }) {
                "域名只能用字母、数字和内部连字符"
            }
            require(!domain.substringAfterLast('.').all { it in '0'..'9' }) { "域名末段不能全是数字" }
            return domain
        }

        fun validateDomains(domains: List<String>): List<String> {
            val normalized = domains.map(::normalizeDomain).distinct()
            require(normalized.size <= 64) { "最多保存 64 个域名" }
            return normalized
        }

        private fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

        fun parseRules(bytes: ByteArray?): List<Rule> {
            if (bytes == null) return emptyList()
            return validateRules(text(bytes).split(Regex("[\r\n]+")).filter { it.isNotEmpty() }.map { line ->
                val parts = line.split('\t')
                require(parts.size == 3) { "规则文件格式有误，原文件未改动" }
                Rule(parts[0], parts[1], parts[2])
            })
        }

        fun parseDomains(bytes: ByteArray?): List<String> = if (bytes == null) DEFAULT_DOMAINS.toList() else {
            validateDomains(text(bytes).split(Regex("[\r\n]+")).filter { line -> line.any { it !in " \t\r\n\u000b\u000c" } })
        }

        private fun lines(rows: List<String>): ByteArray = (rows.joinToString("\n") + if (rows.isEmpty()) "" else "\n").toByteArray(Charsets.UTF_8)
    }
}
