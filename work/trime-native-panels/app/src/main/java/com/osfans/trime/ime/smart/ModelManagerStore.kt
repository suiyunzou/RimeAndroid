// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Keeps immutable catalog copies. Only the active model is atomically replaced. */
class ModelManagerStore(private val root: File, private val replace: (File, File) -> Unit) {
    data class Model(val id: String, val title: String, val bytes: Long, val sha: String, val purpose: String)
    data class ActiveSnapshot internal constructor(internal val backup: File?, internal val sha: String?, internal val bytes: Long)
    companion object {
        val compact = Model("compact-v2", "万象 LTS · 精简 v2", 48419884,
            "c297a1a851efcd587d2651564881e83fe7527e6083cb18a0a735b8aa32bcdab4", "本地整句搭配排序；四字项优先裁剪，节省空间，部分长句可能退化。")
        val full = Model("full-lts", "万象 LTS · 完整版", 418080812,
            "769854c83f6c914a05a1e1abb67c31c00f1ae622a41878e05957bcdee3dbda9a", "本地整句搭配排序；保留完整搭配，内存占用较高。")
        val models = listOf(compact, full)
        @Volatile private var verifiedLabel: Pair<String, Model>? = null
        private fun identityKey(file: File) = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        fun active(root: File) = File(root, "wanxiang-lts-zh-hans.gram")
        fun currentLabel(root: File): String {
            val file = active(root)
            if (!file.isFile) return "未安装"
            val known = verifiedLabel?.takeIf { it.first == identityKey(file) }?.second
            return "${known?.title ?: "本地模型"} · ${"%.1f".format(file.length() / 1048576.0)} MiB"
        }
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    private val catalog get() = File(root, "smart_models").apply {
        require(canonicalFile.parentFile == root.canonicalFile) { "模型目录不能指向数据目录之外" }
        check(isDirectory || mkdirs())
    }
    fun installed(model: Model) = File(catalog, "${model.id}.gram")
    fun identify(): Model? = active(root).takeIf { it.isFile }?.let { file ->
        models.firstOrNull { it.bytes == file.length() && it.sha == hash(file) }.also { model ->
            verifiedLabel = model?.let { identityKey(file) to it }
        }
    }
    /** Capture before configuring/selecting a new model. Unknown custom files are supported. */
    fun snapshotActive(): ActiveSnapshot {
        val current = active(root)
        require(current.canonicalFile.parentFile == root.canonicalFile) { "当前模型路径无效" }
        if (!current.exists()) return ActiveSnapshot(null, null, 0)
        require(current.isFile) { "当前模型不是普通文件" }
        val digest = hash(current)
        val known = models.firstOrNull { it.sha == digest }
        val saved = File(catalog, if (known != null) "${known.id}.gram" else "previous-$digest.gram")
        require(saved.canonicalFile.parentFile == catalog.canonicalFile) { "备份模型路径无效" }
        if (!saved.isFile || saved.length() != current.length() || hash(saved) != digest) atomicCopy(current, saved)
        return ActiveSnapshot(saved, digest, current.length())
    }
    /** Restore the active file only; preserve installed models so failed switches are retryable. */
    fun restoreActive(snapshot: ActiveSnapshot) {
        val current = active(root)
        require(current.canonicalFile.parentFile == root.canonicalFile) { "当前模型路径无效" }
        val backup = snapshot.backup
        if (backup == null) {
            check(!current.exists() || current.delete()) { "无法恢复未安装模型状态" }
            verifiedLabel = null
            return
        }
        require(backup.canonicalFile.parentFile == catalog.canonicalFile && backup.isFile &&
            backup.length() == snapshot.bytes && hash(backup) == snapshot.sha) { "原模型备份校验失败，未覆盖当前模型" }
        atomicCopy(backup, current)
        verifiedLabel = null
    }
    fun install(model: Model, input: InputStream, progress: (Long) -> Unit) {
        require(model in models)
        val target = installed(model)
        require(target.canonicalFile.parentFile == catalog.canonicalFile) { "模型文件路径无效" }
        val temp = File(catalog, "download-${UUID.randomUUID()}.tmp")
        try {
            var done = 0L
            input.use { source -> temp.outputStream().use { out ->
                val b = ByteArray(65536)
                while (true) { val n = source.read(b); if (n < 0) break; done += n
                    require(done <= model.bytes) { "模型超过预期大小，旧模型未改动" }; out.write(b, 0, n); progress(done) }
                out.fd.sync()
            } }
            require(temp.length() == model.bytes && hash(temp) == model.sha) { "模型校验失败，旧模型未改动" }
            replace(temp, target)
        } finally { temp.delete() }
    }
    fun select(model: Model) {
        val source = installed(model)
        require(source.isFile && source.length() == model.bytes && hash(source) == model.sha) { "模型尚未安装或校验失败" }
        val current = active(root)
        // Preserve any existing model, including unknown custom ones, before switching.
        if (current.isFile) {
            val digest = hash(current)
            val known = models.firstOrNull { it.sha == digest }
            val saved = File(catalog, if (known != null) "${known.id}.gram" else "previous-$digest.gram")
            if (!saved.exists()) atomicCopy(current, saved)
        }
        atomicCopy(source, current)
    }
    private fun atomicCopy(source: File, target: File) {
        require(target.canonicalFile.parentFile == target.parentFile.canonicalFile) { "模型路径无效" }
        val temp = File(target.parentFile, "model-${UUID.randomUUID()}.tmp")
        try {
            source.inputStream().use { input -> temp.outputStream().use { out -> input.copyTo(out); out.fd.sync() } }
            require(hash(source) == hash(temp)) { "模型复制校验失败" }
            replace(temp, target)
        } finally { temp.delete() }
    }
}
