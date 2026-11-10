// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 已安装公开词包的版本记录，落盘为 `smart_packs.json`。
 *
 * 隔离约定：本类**只**读写自己的 JSON 文件，绝不触碰 smart_imported.tsv
 * （用户个人词条），也不迁移/删除 clean_personal_dictionary.tsv、
 * custom_phrase.txt、clean_local_rules.tsv 等旧混合词条。
 */
class PackStore(
    /** 用户数据目录，通常为 DataManager.userDataDir。 */
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * 可注入的原子替换实现：from 已经写完并 sync，替换必须整体生效或完全不变。
     * 放在最后以便沿用 LocalDictionaryStore 的尾随 lambda 写法：
     * `PackStore(root) { from, to -> Os.rename(from.absolutePath, to.absolutePath) }`
     */
    private val replace: (File, File) -> Unit = { from, to ->
        if (!from.renameTo(to)) throw IOException("无法原子保存词包清单")
    },
) {
    /** 单个包的安装记录。[checkedAt] 是最近一次联网检查到该包的时间（0 表示从未检查）。 */
    @Serializable
    data class InstalledPack(
        val version: Int,
        val sha256: String,
        val bytes: Long,
        val installedAt: Long,
        val checkedAt: Long = 0L,
        val title: String = "",
    )

    /** 整份持久化状态。字段都有默认值，损坏时能尽量保留可用部分。 */
    @Serializable
    data class State(
        val schema: Int = 1,
        val packs: Map<String, InstalledPack> = emptyMap(),
        val lastCheckTime: Long = 0L,
        val lastFailure: String? = null,
        val lastFailurePackId: String? = null,
    )

    val file: File get() = File(root, FILE_NAME)

    /** 读取状态；文件不存在或内容损坏都返回空状态而不抛异常（下次写入会自我修复）。 */
    fun read(): State = synchronized(lock) {
        val target = file
        if (!target.isFile) return@synchronized State()
        if (target.length() > MAX_FILE_BYTES) return@synchronized State(lastFailure = "词包记录文件异常，已重置")
        val text = try {
            target.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return@synchronized State()
        }
        try {
            json.decodeFromString(State.serializer(), text)
        } catch (_: Exception) {
            State(lastFailure = "词包记录损坏，已重置")
        }
    }

    fun installed(id: String): InstalledPack? = read().packs[id]

    /** 磁盘上这个包的正式路径。文件名由 id 推导，清单无法指定任意路径。 */
    fun targetFile(id: String): File = File(directory, "$id.tsv")

    /** 公开词包专用目录 `smart_packs/`，禁止指向数据目录之外。 */
    val directory: File get() = File(root, DIR_NAME)

    fun ensureDirectory(): File {
        val dir = directory
        require(dir.canonicalFile.parentFile == root.canonicalFile) { "词包目录不能指向数据目录之外" }
        check(dir.isDirectory || dir.mkdirs()) { "无法创建词包目录" }
        return dir
    }

    /** 记录一个包安装成功。旧版本被整体覆盖，不保留多版本副本。 */
    fun recordInstalled(pack: PackManifest.PackEntry, at: Long = now()): State {
        val previous = installed(pack.id)
        val next = previous?.copy(
            version = pack.version,
            sha256 = pack.sha256.lowercase(),
            bytes = pack.bytes,
            installedAt = at,
            checkedAt = at,
            title = pack.title,
        ) ?: InstalledPack(
            version = pack.version,
            sha256 = pack.sha256.lowercase(),
            bytes = pack.bytes,
            installedAt = at,
            checkedAt = at,
            title = pack.title,
        )
        return update { it.copy(packs = it.packs + (pack.id to next), lastFailure = null, lastFailurePackId = null) }
    }

    /** 记录一次成功的联网检查（无论是否有新版本）。 */
    fun recordChecked(at: Long = now(), checkedIds: Collection<String> = emptyList()): State = update { state ->
        val ids = checkedIds.toSet()
        state.copy(
            lastCheckTime = at,
            packs = state.packs.mapValues { (id, pack) -> if (id in ids) pack.copy(checkedAt = at) else pack },
            lastFailure = null,
            lastFailurePackId = null,
        )
    }

    /** 记录一次失败原因，供 UI 在其后展示。不改变任何已安装版本。 */
    fun recordFailure(reason: String, packId: String? = null, at: Long = now()): State = update {
        it.copy(lastCheckTime = at, lastFailure = reason.take(300), lastFailurePackId = packId)
    }

    /** 删除一个包（用户手动卸载）。不存在时返回 false。 */
    fun forget(id: String): Boolean = synchronized(lock) {
        val state = read()
        if (id !in state.packs) return@synchronized false
        val target = targetFile(id)
        // 只删 smart_packs/ 下自己写的文件，绝不触碰其他路径。
        if (target.canonicalFile.parentFile == directory.canonicalFile && target.isFile) target.delete()
        write(state.copy(packs = state.packs - id))
        true
    }

    private fun update(transform: (State) -> State): State = synchronized(lock) {
        val next = transform(read())
        write(next)
        next
    }

    private fun write(state: State) {
        val destination = file
        require(destination.canonicalFile.parentFile == root.canonicalFile) { "词包记录路径无效" }
        check(root.isDirectory || root.mkdirs()) { "无法创建数据目录" }
        val bytes = json.encodeToString(State.serializer(), state).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "词包记录过大" }
        val temporary = File(root, "$FILE_NAME-${UUID.randomUUID()}.tmp")
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            replace(temporary, destination)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        const val FILE_NAME = "smart_packs.json"
        const val DIR_NAME = "smart_packs"
        const val MAX_FILE_BYTES = 256 * 1024
        private val lock = Any()
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            explicitNulls = false
        }
    }
}
