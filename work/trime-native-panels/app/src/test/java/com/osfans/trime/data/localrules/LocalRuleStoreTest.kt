package com.osfans.trime.data.localrules

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory

class LocalRuleStoreTest : StringSpec({
    "legacy TSV, BOM and candidate order are preserved" {
        LocalRuleStore.parseRules("\uFEFFshortcut\tDZ\t地址\r\nshortcut\tdz\t公司\r\nnext\t地址\t收到\n".toByteArray()) shouldBe listOf(
            LocalRuleStore.Rule("shortcut", "dz", "地址"),
            LocalRuleStore.Rule("shortcut", "dz", "公司"),
            LocalRuleStore.Rule("next", "地址", "收到"),
        )
    }

    "duplicate rules and tab injection are rejected" {
        val rule = LocalRuleStore.Rule("shortcut", "dz", "地址")
        shouldThrow<IllegalArgumentException> { LocalRuleStore.validateRules(listOf(rule, rule)) }
        shouldThrow<IllegalArgumentException> { LocalRuleStore.normalizeRule(rule.copy(value = "地址\t公司")) }
        shouldThrow<IllegalArgumentException> { LocalRuleStore.normalizeRule(rule.copy(trigger = "邮箱")) }
        shouldThrow<IllegalArgumentException> { LocalRuleStore.normalizeRule(rule.copy(trigger = "K")) }
        LocalRuleStore.normalizeRule(rule.copy(trigger = "#")).trigger shouldBe "#"
    }

    "character limits count supplementary Unicode as one character" {
        val value = "😀".repeat(80)
        LocalRuleStore.normalizeRule(LocalRuleStore.Rule("next", "你好", value)).value shouldBe value
        shouldThrow<IllegalArgumentException> { LocalRuleStore.normalizeRule(LocalRuleStore.Rule("next", "你好", value + "字")) }
        shouldThrow<IllegalArgumentException> { LocalRuleStore.normalizeRule(LocalRuleStore.Rule("next", "你好", "\uD800")) }
    }

    "rule limit and malformed UTF8 are rejected" {
        shouldThrow<IllegalArgumentException> {
            LocalRuleStore.validateRules((0..128).map { LocalRuleStore.Rule("next", "词$it", "结果") })
        }
        shouldThrow<java.nio.charset.CharacterCodingException> { LocalRuleStore.parseRules(byteArrayOf(0xFF.toByte())) }
        shouldThrow<IllegalArgumentException> { LocalRuleStore.parseRules("shortcut\tdz\t地址\t注入".toByteArray()) }
    }

    "missing email file uses defaults while existing empty file stays empty" {
        LocalRuleStore.parseDomains(null) shouldBe LocalRuleStore.DEFAULT_DOMAINS
        LocalRuleStore.parseDomains(byteArrayOf()) shouldBe emptyList()
        LocalRuleStore.parseDomains("@Gmail.COM.\r\ngmail.com\nexample.org\n".toByteArray()) shouldBe listOf("gmail.com", "example.org")
        shouldThrow<IllegalArgumentException> { LocalRuleStore.parseDomains("\u3000".toByteArray()) }
    }

    "invalid domain labels, numeric TLD and domain count fail" {
        listOf("example.123", "-mail.com", "a..com", "邮箱.com", "x".repeat(64) + ".com").forEach {
            shouldThrow<IllegalArgumentException> { LocalRuleStore.normalizeDomain(it) }
        }
        shouldThrow<IllegalArgumentException> { LocalRuleStore.validateDomains((0..64).map { "mail$it.com" }) }
    }

    "atomic edit replaces file without changing candidate order" {
        withDirectory { directory ->
            val store = storeFor(directory)
            val first = store.saveRules(listOf(LocalRuleStore.Rule("shortcut", "dz", "地址")), store.loadRules())
            store.saveRules(first.items + LocalRuleStore.Rule("shortcut", "dz", "公司"), first)
            store.loadRules().items.map { it.value } shouldBe listOf("地址", "公司")
            File(directory, LocalRuleStore.RULES_FILE).readText() shouldBe "shortcut\tdz\t地址\nshortcut\tdz\t公司\n"
            directory.listFiles()!!.size shouldBe 1
        }
    }

    "external changes are never overwritten" {
        withDirectory { directory ->
            val store = storeFor(directory)
            val snapshot = store.loadRules()
            val target = File(directory, LocalRuleStore.RULES_FILE)
            target.writeText("next\t外部\t变更\n")
            shouldThrow<IllegalStateException> { store.saveRules(emptyList(), snapshot) }
            target.readText() shouldBe "next\t外部\t变更\n"
        }
    }

    "failed rename leaves original bytes intact and removes staging file" {
        withDirectory { directory ->
            val target = File(directory, LocalRuleStore.DOMAINS_FILE)
            target.writeText("gmail.com\n")
            val store = LocalRuleStore(directory) { _, _ -> error("simulated rename failure") }
            shouldThrow<IllegalStateException> { store.saveDomains(listOf("example.com"), store.loadDomains()) }
            target.readText() shouldBe "gmail.com\n"
            directory.listFiles()!!.map { it.name } shouldBe listOf(LocalRuleStore.DOMAINS_FILE)
        }
    }

    "invalid existing file cannot silently become an empty list" {
        withDirectory { directory ->
            File(directory, LocalRuleStore.RULES_FILE).writeText("corrupt")
            shouldThrow<IllegalArgumentException> { storeFor(directory).loadRules() }
        }
    }
})

private fun storeFor(directory: File) = LocalRuleStore(directory) { source, target ->
    Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    Unit
}

private fun withDirectory(block: (File) -> Unit) {
    val project = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { File(it, "work/trime-theme").isDirectory }
    val scratch = File(project, "scratch/local-rule-tests").also { check(it.isDirectory || it.mkdirs()) }
    val directory = createTempDirectory(scratch.toPath(), "trime-local-rules-").toFile()
    try {
        block(directory)
    } finally {
        // This test owns only the direct files in its newly-created directory.
        directory.listFiles()?.forEach { check(it.isFile && it.delete()) }
        check(directory.delete())
    }
}
