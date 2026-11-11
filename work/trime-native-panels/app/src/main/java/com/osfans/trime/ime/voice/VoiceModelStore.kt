// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Locates and validates the on-device sherpa-onnx transducer model.
 *
 * The model is no longer bundled in the APK: it arrives either through
 * [VoiceModelDownloader] (GitHub Release zip) or a user-imported zip. Both
 * land in `files/asr_model/` and are verified by size and SHA-256 here, so
 * recognition stays fully offline and a broken archive can never half-replace
 * a working model.
 */
internal object VoiceModelStore {
    private const val MODEL_DIR = "asr_model"

    class FileSpec(val name: String, val bytes: Long, val sha256: String)

    val FILES = listOf(
        FileSpec("encoder-epoch-99-avg-1.int8.onnx", 181_895_032L, "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b"),
        FileSpec("decoder-epoch-99-avg-1.int8.onnx", 13_091_040L, "1a70c593d71e53f023f5f55b0b4cfff5055abb786ee3992e5f63dc2e273cc4fa"),
        FileSpec("joiner-epoch-99-avg-1.int8.onnx", 3_228_404L, "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0"),
        FileSpec("tokens.txt", 56_317L, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"),
        FileSpec("bpe.model", 244_836L, "bcae393dbc5611be5ffa4c7ae0841558978a5a4f484008cb9dff3a2cc97ebe01"),
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.bytes }

    enum class Status { READY, MISSING, PARTIAL }

    @Volatile
    private var readyDir: File? = null

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, MODEL_DIR)
    }

    fun status(context: Context): Status {
        val dir = dir(context)
        val present = FILES.count { File(dir, it.name).length() == it.bytes }
        return when (present) {
            FILES.size -> Status.READY
            0 -> Status.MISSING
            else -> Status.PARTIAL
        }
    }

    /** Returns the validated model directory, or null when the model is absent or damaged. */
    fun prepare(context: Context, onProgress: (percent: Int) -> Unit): File? {
        readyDir?.let { if (isComplete(it)) return it }
        val dir = dir(context)
        if (!isComplete(dir)) {
            readyDir = null
            return null
        }
        readyDir = dir
        onProgress(100)
        return dir
    }

    fun file(
        dir: File,
        name: String,
    ): String = File(dir, name).absolutePath

    fun delete(context: Context) {
        readyDir = null
        dir(context).deleteRecursively()
        Timber.i("voice: model files deleted")
    }

    fun isComplete(dir: File): Boolean = FILES.all { File(dir, it.name).length() == it.bytes }

    /**
     * Installs a zip holding the five model files. Each entry is extracted to a
     * temp file, verified against the manifest and only then renamed into place.
     */
    fun installZip(
        context: Context,
        input: InputStream,
        onProgress: (percent: Int) -> Unit,
    ): Result<File> =
        runCatching {
            val dir = dir(context).apply { mkdirs() }
            val seen = mutableSetOf<String>()
            var done = 0
            ZipInputStream(input.buffered(1 shl 20)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val spec = FILES.firstOrNull { it.name == entry.name.substringAfterLast('/') }
                    if (spec != null) {
                        val tmp = File(dir, "${spec.name}.tmp")
                        val digest = MessageDigest.getInstance("SHA-256")
                        tmp.outputStream().buffered(1 shl 20).use { out ->
                            val buf = ByteArray(1 shl 20)
                            while (true) {
                                val n = zip.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                            }
                        }
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        check(hash == spec.sha256 && tmp.length() == spec.bytes) { "${spec.name} 校验失败，安装已中止" }
                        val target = File(dir, spec.name)
                        if (target.exists()) target.delete()
                        check(tmp.renameTo(target)) { "${spec.name} 写入失败" }
                        seen += spec.name
                        done++
                        onProgress(done * 100 / FILES.size)
                    }
                    zip.closeEntry()
                }
            }
            check(seen.size == FILES.size) { "压缩包缺少模型文件：${FILES.map { it.name }.filter { it !in seen }}" }
            readyDir = dir
            Timber.i("voice: model installed (%d files)", seen.size)
            dir
        }
}
