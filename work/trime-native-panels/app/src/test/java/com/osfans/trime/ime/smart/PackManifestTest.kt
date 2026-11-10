// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class PackManifestTest : FunSpec({
    fun pack(
        id: String = "wanxiang-mingren",
        title: String = "万象 · 名人",
        version: Int = 3,
        url: String = "https://example.com/$id.tsv",
        bytes: Long = 12,
        sha256: String = PackManifest.sha256("你好\tni hao\t100\n".toByteArray()),
        minAppVersion: Int = 1,
    ) = """
        {"id":"$id","title":"$title","version":$version,"url":"$url","bytes":$bytes,"sha256":"$sha256","minAppVersion":$minAppVersion}
    """.trimIndent()

    fun manifest(vararg packs: String, version: Int = 2) =
        """{"version":$version,"packs":[${packs.joinToString(",")}]}"""

    test("unknown fields are ignored at manifest and pack level") {
        val text = """
            {
              "version": 2,
              "generatedAt": "2026-01-01T00:00:00Z",
              "cdn": {"fallback": "https://x"},
              "packs": [
                ${pack().dropLast(1)},"futureField":[1,2,3],"nested":{"a":{"b":true}}}
              ]
            }
        """.trimIndent()
        val parsed = PackManifest.parse(text)
        parsed.version shouldBe 2
        parsed.packs.single().id shouldBe "wanxiang-mingren"
        parsed.packs.single().version shouldBe 3
    }

    test("missing required fields are a parse error, never defaulted") {
        // manifest.version 缺失
        shouldThrow<IllegalArgumentException> {
            PackManifest.parse("""{"packs":[]}""")
        }
        // pack.version 缺失
        shouldThrow<IllegalArgumentException> {
            PackManifest.parse(
                """{"version":1,"packs":[{"id":"a","title":"A","url":"https://e.com/a.tsv","bytes":1,"sha256":"${"0".repeat(64)}","minAppVersion":1}]}""",
            )
        }
        // pack.sha256 缺失
        shouldThrow<IllegalArgumentException> {
            PackManifest.parse(
                """{"version":1,"packs":[{"id":"a","title":"A","version":1,"url":"https://e.com/a.tsv","bytes":1,"minAppVersion":1}]}""",
            )
        }
        // pack.url 缺失
        shouldThrow<IllegalArgumentException> {
            PackManifest.parse(
                """{"version":1,"packs":[{"id":"a","title":"A","version":1,"bytes":1,"sha256":"${"0".repeat(64)}","minAppVersion":1}]}""",
            )
        }
        // packs 整体缺失
        shouldThrow<IllegalArgumentException> { PackManifest.parse("""{"version":1}""") }
    }

    test("missing field message names the field so the UI can explain it") {
        val error = shouldThrow<IllegalArgumentException> { PackManifest.parse("""{"packs":[]}""") }
        error.message shouldContain "version"
    }

    test("malformed json, wrong types and empty input are rejected") {
        listOf("", "{}", "not json", "[]", """{"version":"二","packs":[]}""", """{"version":1,"packs":{}}""").forEach {
            val rejected = runCatching { PackManifest.parse(it) }.exceptionOrNull()
            rejected.shouldBeInstanceOf<IllegalArgumentException>()
        }
    }

    test("structurally invalid packs are rejected") {
        val good = pack()
        // 非 https
        shouldThrow<IllegalArgumentException> { PackManifest.parse(manifest(good.replace("https://", "http://"))) }
        // id 不能用作文件名
        shouldThrow<IllegalArgumentException> { PackManifest.parse(manifest(pack(id = "../../etc/passwd"))) }
        // sha256 长度/字符非法
        shouldThrow<IllegalArgumentException> { PackManifest.parse(manifest(pack(sha256 = "abc"))) }
        shouldThrow<IllegalArgumentException> { PackManifest.parse(manifest(pack(sha256 = "z".repeat(64)))) }
        // 超过 8 MB
        shouldThrow<IllegalArgumentException> {
            PackManifest.parse(manifest(pack(bytes = (PackManifest.MAX_PACK_BYTES + 1).toLong())))
        }
        // 重复 id
        shouldThrow<IllegalArgumentException> { PackManifest.parse(manifest(good, good)) }
        // 版本号必须为正
        shouldThrow<IllegalArgumentException> { PackManifest.parse(manifest(pack(version = 0))) }
    }

    test("manifest larger than the cap is rejected") {
        shouldThrow<IllegalArgumentException> {
            PackManifest.parse(ByteArray(PackManifest.MAX_MANIFEST_BYTES + 1))
        }
    }

    test("invalid utf8 is rejected") {
        shouldThrow<IllegalArgumentException> {
            PackManifest.parse(byteArrayOf(0x7b, 0xc0.toByte(), 0xaf.toByte(), 0x7d))
        }
    }

    test("valid manifest parses and supports lookup") {
        val parsed = PackManifest.parse(manifest(pack(), pack(id = "yiren", title = "万象 · 艺人")))
        parsed.packs.size shouldBe 2
        parsed.pick("yiren")?.title shouldBe "万象 · 艺人"
        parsed.pick("absent") shouldBe null
        PackManifest.isValidId("wanxiang-mingren") shouldBe true
        PackManifest.isValidId("Good_1") shouldBe false // 大写不允许，避免大小写不敏感文件系统冲突
        PackManifest.isValidSha256(PackManifest.sha256("abc".toByteArray())) shouldBe true
        PackManifest.isValidSha256("短") shouldBe false
    }

    test("round trip encode/parse keeps every field") {
        val original = PackManifest.parse(manifest(pack()))
        PackManifest.parse(PackManifest.encode(original)) shouldBe original
    }

    test("auto update is off by default and enforces unmetered + connected when on") {
        val default = AutoUpdatePolicy.DEFAULT
        default.enabled shouldBe false
        default.shouldSchedule shouldBe false

        // 即便有人强行构造 enabled=true + 关闭约束，normalized() 也必须把它们拉回 true。
        val forced = AutoUpdatePolicy(enabled = true, unmeteredOnly = false, requiresNetwork = false).normalized()
        forced.enabled shouldBe true
        forced.unmeteredOnly shouldBe true
        forced.requiresNetwork shouldBe true
        forced.constraintsLabel shouldContain "仅 Wi-Fi"

        // 关闭时一律不允许联网更新，无论网络状况多好。
        AutoUpdatePolicy.permitsNetwork(default, unmetered = true, connected = true) shouldBe false
        // 开启后：非 Wi-Fi 不行、断网不行。
        val on = AutoUpdatePolicy.enabled()
        AutoUpdatePolicy.permitsNetwork(on, unmetered = true, connected = true) shouldBe true
        AutoUpdatePolicy.permitsNetwork(on, unmetered = false, connected = true) shouldBe false
        AutoUpdatePolicy.permitsNetwork(on, unmetered = true, connected = false) shouldBe false
        AutoUpdatePolicy.permitsNetwork(on, unmetered = true, connected = true, roaming = true) shouldBe false
    }

    test("interval is clamped to a sane range") {
        AutoUpdatePolicy(enabled = true, intervalHours = 1).normalized().intervalHours shouldBe AutoUpdatePolicy.MIN_INTERVAL_HOURS
        AutoUpdatePolicy(enabled = true, intervalHours = 10_000).normalized().intervalHours shouldBe AutoUpdatePolicy.MAX_INTERVAL_HOURS
    }

    test("update results carry renderable chinese messages") {
        val pack = PackManifest.parse(manifest(pack())).packs.single()
        UpdateResult.Success(listOf(pack)).let {
            it.isFailure shouldBe false
            it.message shouldContain "已更新"
            it.display() shouldContain "万象"
        }
        UpdateResult.NoUpdate(listOf(pack)).message shouldContain "最新"
        UpdateResult.NetworkError(httpCode = 503).let {
            it.isFailure shouldBe true
            it.message shouldContain "503"
        }
        UpdateResult.NetworkError().message shouldContain "网络"
        UpdateResult.BadPack("x", "校验值不一致").let {
            it.isFailure shouldBe true
            it.message shouldContain "校验值不一致"
        }
        UpdateResult.Cancelled().let {
            it.isFailure shouldBe false
            it.message shouldContain "取消"
        }
        // 每个分支都能转成一行文案
        listOf(
            UpdateResult.Success(listOf(pack)),
            UpdateResult.NoUpdate(),
            UpdateResult.NetworkError(),
            UpdateResult.BadPack("x", "坏"),
            UpdateResult.Cancelled(),
        ).forEach { it.display().isNotBlank() shouldBe true }
        UpdateResult.Success(listOf(pack)).shouldBeInstanceOf<UpdateResult.Success>()
    }

    test("formatBytes is human readable") {
        formatBytes(512) shouldBe "512 B"
        formatBytes(2048) shouldBe "2 KiB"
        formatBytes(3 * 1024 * 1024) shouldBe "3.0 MiB"
    }
})
