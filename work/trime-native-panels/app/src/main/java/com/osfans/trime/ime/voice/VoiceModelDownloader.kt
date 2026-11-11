// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import android.content.Context
import android.os.SystemClock
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the offline voice model as one zip from the project's GitHub
 * Releases and installs it through [VoiceModelStore].
 *
 * The request is a plain GET that carries no user data; recognition itself
 * stays offline afterwards. Downloads resume from scratch on failure, which
 * keeps the code simple for a one-time 175 MiB fetch.
 */
internal object VoiceModelDownloader {
    const val MODEL_URL =
        "https://github.com/suiyunzou/RimeAndroid/releases/download/asr-model/asr-model-zipformer-zh-en-int8.zip"

    const val MODEL_PAGE = "https://github.com/suiyunzou/RimeAndroid/releases/tag/asr-model"

    class Progress(val percent: Int, val downloadedBytes: Long, val totalBytes: Long)

    fun download(
        context: Context,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> =
        runCatching {
            val tmp = File(context.cacheDir, "asr-model.zip.tmp")
            Timber.i("voice: model download started")
            var read = 0L
            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            try {
                require(connection.responseCode == 200) { "下载失败 HTTP ${connection.responseCode}" }
                val total = connection.contentLengthLong
                var lastTick = 0L
                connection.inputStream.use { input ->
                    tmp.outputStream().buffered(1 shl 20).use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            val now = SystemClock.uptimeMillis()
                            if (now - lastTick > 250) {
                                lastTick = now
                                onProgress(Progress(if (total > 0) (read * 100 / total).toInt() else -1, read, total))
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            onProgress(Progress(100, -1, -1))
            Timber.i("voice: model downloaded in %s bytes, verifying", read)
            VoiceModelStore.installZip(context, tmp.inputStream().buffered(1 shl 20)) { percent ->
                onProgress(Progress(100 + percent, -1, -1))
            }.getOrThrow()
            tmp.delete()
            Timber.i("voice: model download finished")
        }.onFailure {
            Timber.e(it, "voice: model download failed")
            File(context.cacheDir, "asr-model.zip.tmp").delete()
        }
}
