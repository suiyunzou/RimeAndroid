// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Copies the bundled sherpa-onnx transducer model out of `assets/` once.
 *
 * The encoder alone is ~181 MB. Loading it straight from a compressed APK asset
 * makes every recognizer start read and inflate the whole file into native
 * memory on the caller's thread, which is what made the microphone key feel
 * frozen. Reading ordinary files from app storage instead keeps the start-up
 * cost to one disk read after the first launch.
 */
internal object VoiceModelStore {
    private const val ASSET_DIR = "asr_model"

    private val expected = linkedMapOf(
        "encoder-epoch-99-avg-1.int8.onnx" to 181_895_032L,
        "decoder-epoch-99-avg-1.int8.onnx" to 13_091_040L,
        "joiner-epoch-99-avg-1.int8.onnx" to 3_228_404L,
        "tokens.txt" to 56_317L,
        "bpe.model" to 244_836L,
    )

    @Volatile
    private var readyDir: File? = null

    /** Returns the extracted model directory, copying from assets when needed. */
    fun prepare(context: Context, onProgress: (percent: Int) -> Unit): File? {
        readyDir?.let { if (isComplete(it)) return it }
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, ASSET_DIR)
        if (isComplete(dir)) {
            readyDir = dir
            return dir
        }
        dir.mkdirs()
        val total = expected.values.sum()
        var copied = 0L
        onProgress(0)
        for ((name, size) in expected) {
            val target = File(dir, name)
            if (target.length() == size) {
                copied += size
                continue
            }
            val tmp = File(dir, "$name.tmp")
            try {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    tmp.outputStream().buffered(1 shl 20).use { output ->
                        input.copyTo(output, 1 shl 20)
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            } catch (e: Throwable) {
                Timber.e(e, "Failed to extract voice model %s", name)
                tmp.delete()
                return null
            }
            copied += size
            onProgress(((copied * 100) / total).toInt())
        }
        return if (isComplete(dir)) {
            readyDir = dir
            dir
        } else {
            null
        }
    }

    fun file(
        dir: File,
        name: String,
    ): String = File(dir, name).absolutePath

    private fun isComplete(dir: File): Boolean = expected.all { (name, size) -> File(dir, name).length() == size }
}
