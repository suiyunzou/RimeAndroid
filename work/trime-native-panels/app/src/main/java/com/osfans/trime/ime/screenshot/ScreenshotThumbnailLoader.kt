/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.screenshot

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.annotation.RequiresApi
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * 后台加载截图缩略图，结果回到主线程。
 *
 * Android 10（API 29）起直接用 [ContentResolver.loadThumbnail]，低版本用
 * [BitmapFactory] 降采样，避免把整张截图读进内存。
 */
object ScreenshotThumbnailLoader {
    private const val THUMBNAIL_SIZE_PX = 96

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "screenshot-thumbnail").apply { isDaemon = true }
        }

    fun load(
        context: Context,
        uri: Uri,
        onLoaded: (Bitmap?) -> Unit,
    ) {
        val resolver = context.applicationContext.contentResolver
        executor.execute {
            val bitmap =
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        loadThumbnail(resolver, uri)
                    } else {
                        decodeSampled(resolver, uri)
                    }
                }
                    .onFailure { Timber.w(it, "load screenshot thumbnail failed: %s", uri) }
                    .getOrNull()
            mainHandler.post { onLoaded(bitmap) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun loadThumbnail(resolver: ContentResolver, uri: Uri): Bitmap =
        resolver.loadThumbnail(uri, Size(THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX), null)

    private fun decodeSampled(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options =
            BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
            }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= THUMBNAIL_SIZE_PX &&
            height / (sampleSize * 2) >= THUMBNAIL_SIZE_PX
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
