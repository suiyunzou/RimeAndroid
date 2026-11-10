/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.screenshot

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 监听 MediaStore 图片库，识别系统刚刚截下的图。
 *
 * 判定条件：`DATE_ADDED` 距当前不超过 [LOOKBACK_SECONDS] 秒，且 `DISPLAY_NAME`
 * 或 `RELATIVE_PATH`/`DATA` 含 “screenshot”（忽略大小写）。同一个 [Uri] 只会
 * 回调一次，回调固定在主线程执行。
 *
 * 调用 [start] 前必须已获得读取图片权限（见 [ScreenshotPermission]），否则
 * 查询会失败、监听也不会收到通知。
 */
class ScreenshotWatcher(
    context: Context,
    private val onScreenshot: (uri: Uri, addedAtMillis: Long) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queryExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "screenshot-watcher").apply { isDaemon = true }
        }
    private val seenUris = LinkedHashSet<String>()

    @Volatile
    private var started = false

    private val observer =
        object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (started) scan()
            }
        }

    /** 开始监听，并补查最近一张截图（键盘弹出前用户可能已经截过图）。 */
    fun start() {
        if (started) return
        started =
            runCatching {
                appContext.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer,
                )
            }
                .onFailure { Timber.w(it, "register screenshot observer failed") }
                .isSuccess
        if (started) scan()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
            .onFailure { Timber.w(it, "unregister screenshot observer failed") }
    }

    /** 重新查询一次，例如刚拿到读取图片权限后。 */
    fun rescan() {
        if (started) scan()
    }

    /** 彻底释放：停止监听并结束后台线程。之后不能再 [start]。 */
    fun release() {
        stop()
        mainHandler.removeCallbacksAndMessages(null)
        queryExecutor.shutdown()
    }

    private fun scan() {
        if (queryExecutor.isShutdown) return
        runCatching {
            queryExecutor.execute {
                val candidate =
                    runCatching { queryLatestScreenshot() }
                        .onFailure { Timber.w(it, "query latest screenshot failed") }
                        .getOrNull() ?: return@execute
                mainHandler.post {
                    if (started) deliver(candidate.first, candidate.second)
                }
            }
        }
            .onFailure { Timber.w(it, "schedule screenshot query failed") }
    }

    private fun queryLatestScreenshot(): Pair<Uri, Long>? {
        val resolver = appContext.contentResolver
        val nowSeconds = System.currentTimeMillis() / 1000L
        val cursor =
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                screenshotProjection(),
                "${MediaStore.MediaColumns.DATE_ADDED} >= ?",
                arrayOf((nowSeconds - LOOKBACK_SECONDS).toString()),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val addedAtSeconds =
                it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))
            if (addedAtSeconds <= 0L) return null
            val age = nowSeconds - addedAtSeconds
            if (age > LOOKBACK_SECONDS || age < -FUTURE_SKEW_SECONDS) return null
            val name = readColumn(it, MediaStore.MediaColumns.DISPLAY_NAME)
            val path = readColumn(it, screenshotPathColumn())
            if (!looksLikeScreenshot(name, path)) return null
            val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            return uri to addedAtSeconds * 1000L
        }
        return null
    }

    private fun readColumn(cursor: Cursor, column: String): String {
        val index = cursor.getColumnIndex(column)
        return if (index >= 0) cursor.getString(index).orEmpty() else ""
    }

    @Suppress("DEPRECATION")
    private fun screenshotProjection(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH,
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
            )
        }

    @Suppress("DEPRECATION")
    private fun screenshotPathColumn(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.MediaColumns.RELATIVE_PATH
        } else {
            MediaStore.MediaColumns.DATA
        }

    private fun looksLikeScreenshot(name: String, path: String): Boolean =
        name.contains(SCREENSHOT_KEYWORD, ignoreCase = true) ||
            path.contains(SCREENSHOT_KEYWORD, ignoreCase = true)

    private fun deliver(uri: Uri, addedAtMillis: Long) {
        if (!seenUris.add(uri.toString())) return
        if (seenUris.size > MAX_SEEN_URIS) {
            val oldest = seenUris.iterator().next()
            seenUris.remove(oldest)
        }
        runCatching { onScreenshot(uri, addedAtMillis) }
            .onFailure { Timber.w(it, "screenshot callback failed") }
    }

    companion object {
        private const val LOOKBACK_SECONDS = 20L
        private const val FUTURE_SKEW_SECONDS = 5L
        private const val MAX_SEEN_URIS = 64
        private const val SCREENSHOT_KEYWORD = "screenshot"
    }
}
