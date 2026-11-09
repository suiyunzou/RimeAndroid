package com.osfans.trime.ime.smart

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.nio.file.Files

class DictionaryModelManagementTest : FunSpec({
    test("snapshot restores an unknown custom model exactly") {
        val root = Files.createTempDirectory("model-rollback-test").toFile()
        try {
            val store = ModelManagerStore(root) { from, to -> Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); Unit }
            val active = ModelManagerStore.active(root).apply { writeText("custom original model") }
            val snapshot = store.snapshotActive()
            active.writeText("replacement model")
            store.restoreActive(snapshot)
            active.readText() shouldBe "custom original model"
        } finally { root.deleteRecursively() }
    }
    test("snapshot restores the original absence of a model") {
        val root = Files.createTempDirectory("model-absent-test").toFile()
        try {
            val store = ModelManagerStore(root) { from, to -> Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); Unit }
            val snapshot = store.snapshotActive()
            ModelManagerStore.active(root).writeText("newly selected model")
            store.restoreActive(snapshot)
            ModelManagerStore.active(root).exists() shouldBe false
        } finally { root.deleteRecursively() }
    }
    test("corrupted rollback copy cannot overwrite the active model") {
        val root = Files.createTempDirectory("model-corrupt-backup-test").toFile()
        try {
            val store = ModelManagerStore(root) { from, to -> Files.move(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); Unit }
            val active = ModelManagerStore.active(root).apply { writeText("custom original model") }
            val snapshot = store.snapshotActive()
            root.resolve("smart_models").listFiles()!!.single().writeText("broken backup")
            active.writeText("replacement model")
            shouldThrow<IllegalArgumentException> { store.restoreActive(snapshot) }
            active.readText() shouldBe "replacement model"
        } finally { root.deleteRecursively() }
    }
    test("Wanxiang tones and annotation column become usable pinyin") {
        val result = OnlineDictionarySource.parseWanxiang("---\nname: sample\n...\n星河\txīng hé\t100\t注释\n".toByteArray())
        result.entries.single() shouldBe LocalDictionaryParser.Entry("星河", "xing he", 100)
    }
    test("HTML partial downloads and malformed records are rejected") {
        listOf("<html>error</html>", "---\n...\n坏词\t123\t100\n").forEach { value ->
            shouldThrow<IllegalArgumentException> { OnlineDictionarySource.parseWanxiang(value.toByteArray()) }
        }
    }
    test("translator patch preserves existing settings and is idempotent") {
        val before = "patch:\n  translator/enable_sentence: true\n  engine/translators:\n  - script_translator\n"
        val after = LocalDictionaryIntegration.addTranslator(before)
        after.contains("translator/enable_sentence: true") shouldBe true
        after.contains("  - script_translator") shouldBe true
        after.contains("  - lua_translator@*clean_local_dictionary") shouldBe true
        LocalDictionaryIntegration.addTranslator(after) shouldBe after
        LocalDictionaryIntegration.addTranslator("patch:\n").contains("engine/translators/+") shouldBe true
    }
    test("unknown inline translator config is left unchanged by rejecting migration") {
        shouldThrow<IllegalArgumentException> { LocalDictionaryIntegration.addTranslator("patch:\n  engine/translators: [script_translator]\n") }
    }
    test("invalid model download retains active and catalog models") {
        val root = Files.createTempDirectory("model-manager-test").toFile()
        try {
            val active = ModelManagerStore.active(root).apply { writeText("existing model") }
            val store = ModelManagerStore(root) { _, _ -> error("must not install") }
            shouldThrow<IllegalArgumentException> { store.install(ModelManagerStore.compact, "truncated".byteInputStream()) {} }
            active.readText() shouldBe "existing model"
            store.installed(ModelManagerStore.compact).exists() shouldBe false
            root.resolve("smart_models").listFiles()!!.isEmpty() shouldBe true
        } finally { root.deleteRecursively() }
    }
})
