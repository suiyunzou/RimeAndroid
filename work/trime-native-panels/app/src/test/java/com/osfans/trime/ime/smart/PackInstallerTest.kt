// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PackInstallerTest : FunSpec({
    /** 内存里的假"互联网"：url -> 字节。可配置为断网或返回错误。 */
    class FakeWeb {
        val files = mutableMapOf<String, ByteArray>()
        var offline = false
        var httpCode: Int? = null
        /** 只送出前 N 个字节就断开，模拟被截断的下载。 */
        var truncateTo: Long? = null
        var onFetch: (() -> Unit)? = null
        /** 清单可以下载成功，但任何 .tsv 包体下载都断网。 */
        var dropBodyFetches = false
        val fetched = mutableListOf<String>()

        fun fetcher() = PackInstaller.Fetcher { url, _, _, sink ->
            onFetch?.invoke()
            fetched += url
            if (offline) throw IOException("断网")
            if (dropBodyFetches && url.endsWith(".tsv")) throw PackInstaller.NetworkFailure("下载中途断网")
            httpCode?.let { throw PackInstaller.NetworkFailure("HTTP $it", it) }
            val bytes = files[url] ?: throw PackInstaller.NetworkFailure("HTTP 404", 404)
            val limit = truncateTo ?: bytes.size.toLong()
            var sent = 0L
            var offset = 0
            while (offset < bytes.size && sent < limit) {
                val n = minOf(4096, bytes.size - offset, (limit - sent).toInt())
                sink(bytes.copyOfRange(offset, offset + n), n)
                offset += n
                sent += n
            }
            sent
        }
    }

    fun text(name: String) = "词包-$name\tci bao\n".repeat(50).toByteArray(Charsets.UTF_8).let {
        // 给每个包唯一的字节内容，确保哈希不同
        (String(it, Charsets.UTF_8) + "# $name\n").toByteArray(Charsets.UTF_8)
    }

    /** 某个 (id, version) 对应的唯一词包内容；entry() 与 FakeWeb 都从这里取，保证声明与实发一致。 */
    fun contentFor(id: String, version: Int) = text("v$version-$id")

    fun entry(
        id: String = "wanxiang-mingren",
        version: Int = 2,
        bytes: ByteArray = contentFor(id, version),
        url: String = "https://example.com/$id-$version.tsv",
        minAppVersion: Int = 1,
        declaredBytes: Long = bytes.size.toLong(),
        declaredSha: String = PackManifest.sha256(bytes),
    ) = PackManifest.PackEntry(
        id = id,
        title = "万象 · 名人",
        version = version,
        url = url,
        bytes = declaredBytes,
        sha256 = declaredSha,
        minAppVersion = minAppVersion,
    )

    fun atomicReplace(): (File, File) -> Unit = { from, to ->
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        Unit
    }

    fun withRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("pack-installer-test").toFile()
        try {
            block(root)
        } finally {
            root.walkBottomUp().forEach { if (it.isFile) it.delete() else it.delete() }
        }
    }

    fun listAll(root: File): List<String> = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

    test("installs a pack into smart_packs/ and records its version") {
        withRoot { root ->
            val web = FakeWeb()
            val pack = entry(version = 2)
            web.files[pack.url] = contentFor(pack.id, 2)
            val store = PackStore(root) { from, to -> atomicReplace()(from, to) }
            val installer = PackInstaller(root, store, web.fetcher(), now = { 1000L }) { from, to -> atomicReplace()(from, to) }

            val result = installer.install(pack)
            result.shouldBeInstanceOf<UpdateResult.Success>()
            result.isFailure shouldBe false

            // 只写到 smart_packs/ 和 smart_packs.json
            listAll(root) shouldBe listOf("smart_packs.json", "smart_packs/wanxiang-mingren.tsv")
            store.targetFile(pack.id).readBytes() shouldBe web.files[pack.url]

            val record = store.installed(pack.id)!!
            record.version shouldBe 2
            record.sha256 shouldBe pack.sha256.lowercase()
            record.bytes shouldBe pack.bytes
            record.installedAt shouldBe 1000L
            // 不残留 tmp 文件
            File(root, "smart_packs").list()!!.none { it.endsWith(".tmp") } shouldBe true
        }
    }

    test("a bad pack never overwrites the previously installed version") {
        withRoot { root ->
            val web = FakeWeb()
            val store = PackStore(root) { from, to -> atomicReplace()(from, to) }
            val installer = PackInstaller(root, store, web.fetcher()) { from, to -> atomicReplace()(from, to) }

            val good = entry(version = 1)
            web.files[good.url] = contentFor(good.id, good.version)
            installer.install(good).shouldBeInstanceOf<UpdateResult.Success>()
            val installedBytes = store.targetFile(good.id).readBytes()
            val before = store.installed(good.id)!!

            // v2 包声明 sha256 与真实内容不符 —— 典型的中毒/损坏包
            val corruptBytes = "被篡改的内容".toByteArray()
            val badSha = entry(version = 2, bytes = corruptBytes, declaredSha = "0".repeat(64))
            web.files[badSha.url] = corruptBytes
            val badShaResult = installer.install(badSha)
            badShaResult.shouldBeInstanceOf<UpdateResult.BadPack>()
            badShaResult.isFailure shouldBe true
            badShaResult.message shouldContain "校验"
            store.targetFile(good.id).readBytes() shouldBe installedBytes
            store.installed(good.id) shouldBe before

            // 尺寸不符（清单写 100 字节，实际更短）
            val shortBytes = "x".toByteArray()
            val badSize = entry(version = 3, bytes = shortBytes, declaredBytes = 100)
            web.files[badSize.url] = shortBytes
            val badSizeResult = installer.install(badSize)
            badSizeResult.shouldBeInstanceOf<UpdateResult.BadPack>()
            badSizeResult.message shouldContain "大小"
            store.targetFile(good.id).readBytes() shouldBe installedBytes
            store.installed(good.id) shouldBe before

            // 下载被截断
            val truncated = entry(version = 4, bytes = text("v4"))
            web.files[truncated.url] = contentFor(truncated.id, 4)
            web.truncateTo = 3L
            installer.install(truncated).shouldBeInstanceOf<UpdateResult.BadPack>()
            web.truncateTo = null
            store.targetFile(good.id).readBytes() shouldBe installedBytes
            store.installed(good.id) shouldBe before
            // 没有产生任何其他数据文件
            listAll(root) shouldBe listOf("smart_packs.json", "smart_packs/wanxiang-mingren.tsv")
        }
    }

    test("network error returns NetworkError and leaves the old version untouched") {
        withRoot { root ->
            val web = FakeWeb()
            val store = PackStore(root) { from, to -> atomicReplace()(from, to) }
            val installer = PackInstaller(root, store, web.fetcher()) { from, to -> atomicReplace()(from, to) }

            val v1 = entry(version = 1)
            web.files[v1.url] = contentFor(v1.id, 1)
            installer.install(v1).shouldBeInstanceOf<UpdateResult.Success>()
            val installedBytes = store.targetFile(v1.id).readBytes()

            val v2 = entry(version = 2)
            web.files[v2.url] = contentFor(v2.id, 2)

            // 断网
            web.offline = true
            val offline = installer.checkAndUpdate("https://example.com/manifest.json")
            offline.shouldBeInstanceOf<UpdateResult.NetworkError>()
            offline.isFailure shouldBe true
            offline.message shouldContain "网络"

            // HTTP 503
            web.offline = false
            web.httpCode = 503
            val http = installer.checkAndUpdate("https://example.com/manifest.json")
            http.shouldBeInstanceOf<UpdateResult.NetworkError>()
            http.httpCode shouldBe 503
            http.message shouldContain "503"
            web.httpCode = null

            // 单个包下载失败：用一个从未注册过的 URL，保证真的 404
            val missing = entry(version = 3, url = "https://example.com/not-registered-3.tsv")
            val single = installer.install(missing)
            single.shouldBeInstanceOf<UpdateResult.NetworkError>()
            single.httpCode shouldBe 404
            // 失败原因被记录，可用于 UI 展示（紧随 404 用例，避免被后续用例覆盖）
            store.read().lastFailure shouldContain "404"

            // 最容易出事的路径：清单拿到了、但包体下载中途断网。
            // 此时绝不允许动到已安装的 v1。
            val manifest = """{"version":1,"packs":[{"id":"wanxiang-mingren","title":"万象 · 名人","version":2,
                "url":"${v2.url}","bytes":${contentFor(v2.id, 2).size},"sha256":"${PackManifest.sha256(contentFor(v2.id, 2))}","minAppVersion":1}]}""".trimIndent()
            web.files["https://example.com/manifest.json"] = manifest.toByteArray()
            web.dropBodyFetches = true
            val midDownload = installer.checkAndUpdate("https://example.com/manifest.json")
            midDownload.shouldBeInstanceOf<UpdateResult.NetworkError>()
            store.targetFile(v1.id).readBytes() shouldBe installedBytes
            store.installed(v1.id)!!.version shouldBe 1
            web.dropBodyFetches = false

            store.targetFile(v1.id).readBytes() shouldBe installedBytes
            store.installed(v1.id)!!.version shouldBe 1
        }
    }

    test("version comparison: only a strictly newer remote pack is installed") {
        withRoot { root ->
            val web = FakeWeb()
            val store = PackStore(root) { from, to -> atomicReplace()(from, to) }
            val installer = PackInstaller(root, store, web.fetcher()) { from, to -> atomicReplace()(from, to) }

            fun serveRemote(version: Int): String {
                // 每个版本用固定 3 字节内容，清单声明的 bytes 与 sha256 必须与之一致。
                val content = byteArrayOf(version.toByte(), version.toByte(), version.toByte())
                val url = "https://example.com/wanxiang-mingren-$version.tsv"
                web.files[url] = content
                val sha = PackManifest.sha256(content)
                val manifest = """{"version":1,"packs":[{"id":"wanxiang-mingren","title":"万象 · 名人","version":$version,
                    "url":"$url","bytes":3,"sha256":"$sha","minAppVersion":1}]}""".trimIndent()
                web.files["https://example.com/manifest.json"] = manifest.toByteArray()
                return "https://example.com/manifest.json"
            }

            // 首次安装 local=2
            serveRemote(2)
            val first = installer.checkAndUpdate("https://example.com/manifest.json")
            first.shouldBeInstanceOf<UpdateResult.Success>()
            val installedBytes = store.targetFile("wanxiang-mingren").readBytes()

            // 远端相同版本 → NoUpdate，文件不变
            serveRemote(2)
            val same = installer.checkAndUpdate("https://example.com/manifest.json")
            same.shouldBeInstanceOf<UpdateResult.NoUpdate>()
            store.targetFile("wanxiang-mingren").readBytes() shouldBe installedBytes
            store.read().lastCheckTime shouldNotBe 0L

            // 远端更旧 → NoUpdate，文件不变
            serveRemote(1)
            installer.checkAndUpdate("https://example.com/manifest.json").shouldBeInstanceOf<UpdateResult.NoUpdate>()
            store.targetFile("wanxiang-mingren").readBytes() shouldBe installedBytes
            store.installed("wanxiang-mingren")!!.version shouldBe 2

            // 远端更新 → Success，内容真正替换
            serveRemote(3)
            val newer = installer.checkAndUpdate("https://example.com/manifest.json")
            newer.shouldBeInstanceOf<UpdateResult.Success>()
            newer.packs.single().version shouldBe 3
            store.installed("wanxiang-mingren")!!.version shouldBe 3
            store.targetFile("wanxiang-mingren").readBytes() shouldBe byteArrayOf(3, 3, 3)
            store.read().lastFailure shouldBe null
        }
    }

    test("pack requiring a newer app is skipped without error") {
        withRoot { root ->
            val web = FakeWeb()
            val store = PackStore(root) { from, to -> atomicReplace()(from, to) }
            val installer = PackInstaller(root, store, web.fetcher()) { from, to -> atomicReplace()(from, to) }
            val pack = entry(version = 5, minAppVersion = 99)
            web.files["https://example.com/manifest.json"] =
                """{"version":1,"packs":[{"id":"wanxiang-mingren","title":"万象 · 名人","version":5,
                    "url":"${pack.url}","bytes":${pack.bytes},"sha256":"${pack.sha256}","minAppVersion":99}]}"""
                    .toByteArray()
            installer.checkAndUpdate("https://example.com/manifest.json", appVersion = 10)
                .shouldBeInstanceOf<UpdateResult.NoUpdate>()
            store.installed("wanxiang-mingren") shouldBe null
            listAll(root) shouldBe listOf("smart_packs.json")
        }
    }

    test("invalid manifest is reported as BadPack and installs nothing") {
        withRoot { root ->
            val web = FakeWeb()
            val store = PackStore(root) { from, to -> atomicReplace()(from, to) }
            val installer = PackInstaller(root, store, web.fetcher()) { from, to -> atomicReplace()(from, to) }
            web.files["https://example.com/manifest.json"] = """{"version":1}""".toByteArray()
            val result = installer.checkAndUpdate("https://example.com/manifest.json")
            result.shouldBeInstanceOf<UpdateResult.BadPack>()
            result.message shouldContain "缺少必填字段"
            result.message shouldContain "packs"
            listAll(root).none { it.startsWith("smart_packs/") } shouldBe true
        }
    }

    test("a throwing replace leaves the old pack byte-identical and reports failure") {
        withRoot { root ->
            val web = FakeWeb()
            val pack = entry(version = 1)
            web.files[pack.url] = contentFor(pack.id, 1)
            PackInstaller(root, PackStore(root) { from, to -> atomicReplace()(from, to) }, web.fetcher()) { from, to ->
                atomicReplace()(from, to)
            }.install(pack).shouldBeInstanceOf<UpdateResult.Success>()
            val installedBytes = PackStore(root).targetFile(pack.id).readBytes()

            web.files[pack.url] = contentFor(pack.id, 2)
            val v2 = entry(version = 2, url = pack.url)
            val failing = PackInstaller(root, PackStore(root) { from, to -> atomicReplace()(from, to) }, web.fetcher(), replace = { _, _ ->
                throw IOException("模拟替换失败")
            })
            val result = failing.install(v2)
            result.shouldBeInstanceOf<UpdateResult.NetworkError>()
            result.isFailure shouldBe true
            PackStore(root).targetFile(pack.id).readBytes() shouldBe installedBytes
            PackStore(root).installed(pack.id)!!.version shouldBe 1
            // tmp 清理干净
            File(root, "smart_packs").list()!!.none { it.endsWith(".tmp") } shouldBe true
        }
    }

    test("cancellation is reported and changes nothing") {
        withRoot { root ->
            val web = FakeWeb()
            val pack = entry(version = 1)
            web.files[pack.url] = contentFor(pack.id, 1)
            val installer = PackInstaller(root, PackStore(root) { from, to -> atomicReplace()(from, to) }, web.fetcher()) { from, to ->
                atomicReplace()(from, to)
            }
            web.onFetch = { throw PackInstaller.CancelledFailure() }
            installer.install(pack).shouldBeInstanceOf<UpdateResult.Cancelled>()
            installer.checkAndUpdate("https://example.com/manifest.json").shouldBeInstanceOf<UpdateResult.Cancelled>()
            listAll(root).none { it.startsWith("smart_packs/") } shouldBe true
        }
    }

    test("http fetcher rejects non-https urls") {
        shouldThrow<IllegalArgumentException> {
            PackInstaller.HttpFetcher().fetch("http://example.com/a.tsv", 10L, { _, _ -> }) { _, _ -> }
        }
    }

    test("public packs never write personal word files and leave legacy files alone") {
        withRoot { root ->
            val web = FakeWeb()
            // 假装用户已有个人词条与旧混合词条
            val personal = File(root, "smart_imported.tsv").apply { writeText("我的词\two de ci\n") }
            val legacy = listOf("clean_personal_dictionary.tsv", "custom_phrase.txt", "clean_local_rules.tsv")
                .associateWith { File(root, it).apply { writeText("legacy $it\n") } }
            val personalSnapshot = personal.readBytes()
            val legacySnapshot = legacy.mapValues { it.value.readBytes() }

            val store = PackStore(root) { from, to -> atomicReplace()(from, to) }
            val installer = PackInstaller(root, store, web.fetcher()) { from, to -> atomicReplace()(from, to) }
            val pack = entry(version = 1)
            web.files["https://example.com/manifest.json"] =
                """{"version":1,"packs":[{"id":"wanxiang-mingren","title":"万象 · 名人","version":1,
                    "url":"${pack.url}","bytes":${pack.bytes},"sha256":"${pack.sha256}","minAppVersion":1}]}"""
                    .toByteArray()
            web.files[pack.url] = contentFor(pack.id, 1)
            installer.checkAndUpdate("https://example.com/manifest.json").shouldBeInstanceOf<UpdateResult.Success>()

            personal.readBytes() shouldBe personalSnapshot
            legacy.forEach { (name, file) -> file.readBytes() shouldBe legacySnapshot[name] }

            // 卸载也只删自己目录下的文件，个人词条不受影响
            store.forget("wanxiang-mingren") shouldBe true
            personal.readBytes() shouldBe personalSnapshot
            legacy.forEach { (name, file) -> file.readBytes() shouldBe legacySnapshot[name] }
        }
    }
})
