// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 一次"检查/更新公开词包"的结果。每个分支都能直接落到中文 UI 文案：
 * [message] 是一句结论，[detail] 是可选细节。
 */
sealed class UpdateResult {
    abstract val message: String
    abstract val detail: String?
    abstract val isFailure: Boolean

    /** Snackbar/Toast 一行文案：有细节时附在括号里。 */
    fun display(): String = detail?.takeIf { it.isNotBlank() }?.let { "$message（$it）" } ?: message

    /** 已安装完成。[packs] 是本次真正更新（版本号变化）的包。 */
    data class Success(
        val packs: List<PackManifest.PackEntry>,
        val totalBytes: Long = packs.sumOf { it.bytes },
    ) : UpdateResult() {
        override val message get() = "已更新 ${packs.size} 个词包（${formatBytes(totalBytes)}）"
        override val detail: String? get() = packs.joinToString("、") { p -> "${p.title} v${p.version}" }.ifEmpty { null }
        override val isFailure get() = false
    }

    /** 检查过清单，所有词包都已是最新。 */
    data class NoUpdate(
        val checked: List<PackManifest.PackEntry> = emptyList(),
    ) : UpdateResult() {
        override val message get() = if (checked.isEmpty()) "没有可用的公开词包" else "词包已是最新"
        override val detail: String? get() = null
        override val isFailure get() = false
    }

    /** 联网失败：断网、超时、HTTP 非 200 等。旧词包必定保持原样。 */
    data class NetworkError(
        val packId: String? = null,
        val httpCode: Int? = null,
        override val detail: String? = null,
    ) : UpdateResult() {
        override val message: String
            get() = if (httpCode != null) "下载失败（HTTP $httpCode），已有词包未改动" else "无法联网检查词包，请检查网络后重试"
        override val isFailure get() = true
    }

    /** 校验失败：包体大小或 sha256 不符、清单本身无效。旧版本绝不会被覆盖。 */
    data class BadPack(
        val packId: String,
        val reason: String,
        override val detail: String? = null,
    ) : UpdateResult() {
        override val message get() = "词包校验失败：$reason"
        override val isFailure get() = true
    }

    /** 用户或系统取消（离开页面、WorkManager 被取消）。旧版本保持不变。 */
    data class Cancelled(
        val packId: String? = null,
    ) : UpdateResult() {
        override val message get() = "已取消本次更新，已有词包未改动"
        override val detail: String? get() = null
        override val isFailure get() = false
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / 1048576.0)
    bytes >= 1024L -> "%.0f KiB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 公开词包的原子安装引擎。
 *
 * 流程：下载到同目录 tmp → 校验字节数与 sha256 → 原子替换正式文件 → 记录版本。
 * 任何一步失败都抛异常或返回失败结果，**旧版本文件保持逐字节不变**。
 *
 * 隔离：只写入 `smart_packs/` 目录与 `smart_packs.json`；绝不写 smart_imported.tsv。
 * 网络：与 OnlineDictionarySource 一致，只发 GET，不发送任何用户数据/标识。
 * 本类不依赖 Android 运行时，网络通过可注入的 [Fetcher] 抽象，可在 JVM 单测里跑。
 */
class PackInstaller(
    private val root: File,
    private val store: PackStore = PackStore(root),
    private val fetcher: Fetcher = HttpFetcher(),
    private val now: () -> Long = System::currentTimeMillis,
    /** 可注入的原子替换（Android 上传入 Os.rename）；默认同目录 renameTo。 */
    private val replace: (File, File) -> Unit = { from, to ->
        if (!from.renameTo(to)) throw IOException("无法写入词包文件，已保留旧版本")
    },
) {
    /** 一次下载的数据来源。实现方只需把字节按块交给 sink，并报告进度。 */
    fun interface Fetcher {
        /**
         * 打开 [url]，把内容按块写入 [sink]。
         * @return 收到的总字节数
         * @throws NetworkFailure 网络层错误（UI 会映射成 NetworkError）
         * @throws CancelledFailure 用户/系统取消
         */
        fun fetch(url: String, expectedBytes: Long, progress: (Long, Long) -> Unit, sink: (ByteArray, Int) -> Unit): Long
    }

    /** 网络层失败。[httpCode] 为 null 表示连不上。 */
    class NetworkFailure(message: String, val httpCode: Int? = null) : IOException(message)

    /** 清单下载到的字节数与流报告的不一致（不是格式问题，是真的没下全）。 */
    class IncompleteDownload(message: String) : RuntimeException(message)

    /** 下载被取消。 */
    class CancelledFailure : RuntimeException("已取消")

    /** 本机 app 版本号，用于拒绝 minAppVersion 过高的包。 */
    var appVersionCode: Int = Int.MAX_VALUE

    /**
     * 检查清单并安装所有比本地新的包。检查失败不会改动任何已安装文件。
     *
     * @param manifestUrl 远端清单地址（https）
     * @param appVersion 当前 app 的 versionCode；低于包的 minAppVersion 时跳过该包
     * @return 汇总结果；任一包失败即返回该失败，且此前已成功安装的包保持有效
     */
    fun checkAndUpdate(
        manifestUrl: String,
        appVersion: Int = appVersionCode,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): UpdateResult {
        val manifest = try {
            val buffer = ByteArrayOutputStream()
            val total = fetcher.fetch(manifestUrl, PackManifest.MAX_MANIFEST_BYTES.toLong(), { _, _ -> }) { chunk, count ->
                require(buffer.size() + count <= PackManifest.MAX_MANIFEST_BYTES) { "清单文件超过 256 KB，已忽略" }
                buffer.write(chunk, 0, count)
            }
            // 用独立异常而不是 require/check：require 抛 IllegalArgumentException 会被
            // 下面归类成"清单格式无效"，而这里其实是下载内容不完整。
            if (total != buffer.size().toLong()) throw IncompleteDownload("清单下载不完整")
            PackManifest.parse(buffer.toByteArray())
        } catch (cancelled: CancelledFailure) {
            return UpdateResult.Cancelled()
        } catch (incomplete: IncompleteDownload) {
            return UpdateResult.NetworkError(detail = incomplete.message)
        } catch (bad: IllegalArgumentException) {
            return UpdateResult.BadPack(MANIFEST_ID, bad.message ?: "清单格式无效")
        } catch (network: NetworkFailure) {
            return UpdateResult.NetworkError(httpCode = network.httpCode, detail = network.message)
        } catch (io: IOException) {
            return UpdateResult.NetworkError(detail = io.message?.take(120))
        }

        val applicable = manifest.packs.filter { it.minAppVersion <= appVersion }
        val outdated = applicable.filter { isNewer(it, store.installed(it.id)) }
        if (outdated.isEmpty()) {
            store.recordChecked(now(), applicable.map { it.id })
            return UpdateResult.NoUpdate(applicable)
        }

        val installed = mutableListOf<PackManifest.PackEntry>()
        for (pack in outdated) {
            when (val single = install(pack, progress)) {
                is UpdateResult.Success -> installed += single.packs
                is UpdateResult.NoUpdate -> Unit // 并发下已被其他流程装好
                else -> {
                    // 失败：记录原因，返回该失败；已装好的包保留（各自都是完整的一版）。
                    store.recordFailure(single.message, pack.id, now())
                    return single
                }
            }
        }
        store.recordChecked(now(), applicable.map { it.id })
        return UpdateResult.Success(installed)
    }

    /**
     * 安装单个词包：先下载到 tmp，校验尺寸与 sha256，全部通过才原子替换。
     * 失败时不修改 [PackStore.targetFile] 指向的既有文件。
     */
    fun install(pack: PackManifest.PackEntry, progress: (Long, Long) -> Unit = { _, _ -> }): UpdateResult {
        val directory = store.ensureDirectory()
        val target = store.targetFile(pack.id)
        require(target.canonicalFile.parentFile == directory.canonicalFile) { "词包路径无效" }
        val expected = pack.sha256.lowercase()
        val temporary = File(directory, "download-${UUID.randomUUID()}.tmp")
        try {
            var received = 0L
            // 先写 tmp 文件并 fsync，校验通过后才替换正式文件。
            temporary.outputStream().use { out ->
                fetcher.fetch(pack.url, pack.bytes, progress) { chunk, count ->
                    received += count
                    require(received <= pack.bytes) { "词包超过清单声明的大小，未安装" }
                    out.write(chunk, 0, count)
                }
                out.flush()
                out.fd.sync()
            }
            if (received != pack.bytes) {
                return fail(pack, "大小不符（应为 ${formatBytes(pack.bytes)}，实际 ${formatBytes(received)}）")
            }
            if (temporary.length() != pack.bytes) {
                return fail(pack, "大小不符（应为 ${formatBytes(pack.bytes)}，实际 ${formatBytes(temporary.length())}）")
            }
            val actual = hash(temporary)
            if (actual != expected) {
                return fail(pack, "校验值与清单不一致", "期望 ${expected.take(12)}…，实际 ${actual.take(12)}…")
            }
            // 走到这里才调用可注入的替换；之前磁盘上的旧版本从未被打开写过。
            replace(temporary, target)
            store.recordInstalled(pack, now())
            return UpdateResult.Success(listOf(pack), pack.bytes)
        } catch (cancelled: CancelledFailure) {
            return UpdateResult.Cancelled(pack.id)
        } catch (network: NetworkFailure) {
            return recordFailure(pack.id, UpdateResult.NetworkError(pack.id, network.httpCode, network.message?.take(120)))
        } catch (io: IOException) {
            return recordFailure(pack.id, UpdateResult.NetworkError(pack.id, detail = io.message?.take(120)))
        } catch (bad: IllegalArgumentException) {
            return recordFailure(pack.id, fail(pack, bad.message ?: "词包内容无效"))
        } finally {
            // 无论成功失败，tmp 都不留下。
            if (temporary.exists()) temporary.delete()
        }
    }

    /** 记录失败原因，让 UI 在下次打开页面时也能看到"上次为什么没装成"。 */
    private fun recordFailure(packId: String, result: UpdateResult): UpdateResult {
        store.recordFailure(result.message, packId, now())
        return result
    }

    private fun fail(pack: PackManifest.PackEntry, reason: String, detail: String? = null): UpdateResult {
        // 坏包绝不能写入记录，旧版本记录也保持原样。
        return UpdateResult.BadPack(pack.id, reason, detail)
    }

    /** 版本比较：远程比本地新才更新。本地没有记录视为需要安装。 */
    private fun isNewer(remote: PackManifest.PackEntry, local: PackStore.InstalledPack?): Boolean =
        local == null || remote.version > local.version

    private fun hash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val b = ByteArray(65536)
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                digest.update(b, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 清单整体无效时使用的占位 id（UI 应据此提示"清单"而不是某个具体词包）。 */
    private companion object {
        const val MANIFEST_ID = "manifest"
    }

    /** 原生 HttpURLConnection 实现：只发 GET，不跟随重定向，不发送任何用户数据。 */
    class HttpFetcher(private val connectTimeoutMs: Int = 15_000, private val readTimeoutMs: Int = 20_000) : Fetcher {
        override fun fetch(url: String, expectedBytes: Long, progress: (Long, Long) -> Unit, sink: (ByteArray, Int) -> Unit): Long {
            require(url.startsWith("https://")) { "下载地址必须是 https" }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            // 不设置 Cookie、不设置任何标识头：与在线词库一致，绝不发送用户数据。
            connection.useCaches = false
            try {
                try {
                    val code = connection.responseCode
                    if (code != 200) throw NetworkFailure("HTTP $code", code)
                    var total = 0L
                    connection.inputStream.use { input: InputStream ->
                        val buffer = ByteArray(16384)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw CancelledFailure()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > expectedBytes) throw IllegalArgumentException("内容超过清单声明的大小")
                            sink(buffer, count)
                            progress(total, expectedBytes)
                        }
                    }
                    return total
                } catch (cancelled: CancelledFailure) {
                    throw cancelled
                } catch (network: NetworkFailure) {
                    throw network
                } catch (bad: IllegalArgumentException) {
                    throw bad
                } catch (error: IOException) {
                    throw NetworkFailure(error.message ?: "网络错误")
                }
            } finally {
                connection.disconnect()
            }
        }
    }
}
