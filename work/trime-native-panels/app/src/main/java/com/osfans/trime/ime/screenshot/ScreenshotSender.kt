/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.screenshot

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.osfans.trime.ime.core.TrimeInputMethodService
import timber.log.Timber

/**
 * 把截图送进当前聊天输入框。
 *
 * 发送顺序：
 * 1. `InputConnectionCompat.commitContent`（Gboard / 微信等富媒体输入的标准通道）；
 * 2. 编辑器不支持时打开系统分享面板（`ACTION_SEND` + 读 URI 授权）；
 * 3. 仍不可用时把图片放进剪贴板，并提示用户长按粘贴。
 */
object ScreenshotSender {
    enum class Result {
        /** 已通过 commitContent 插入当前输入框。 */
        COMMITTED,

        /** 已打开系统分享面板。 */
        SHARED,

        /** 已复制到剪贴板。 */
        COPIED,

        /** 三条路径都失败。 */
        FAILED,
    }

    fun send(service: TrimeInputMethodService, uri: Uri): Result {
        val mimeType = resolveMimeType(service, uri)
        if (commitContent(service, uri, mimeType)) return Result.COMMITTED
        if (share(service, uri, mimeType)) return Result.SHARED
        return if (copyToClipboard(service, uri)) Result.COPIED else Result.FAILED
    }

    /** 直接打开系统分享面板，返回是否成功启动。 */
    fun share(service: TrimeInputMethodService, uri: Uri): Boolean =
        share(service, uri, resolveMimeType(service, uri))

    /** 复制到剪贴板并给出中文提示，返回是否成功。 */
    fun copyToClipboard(service: TrimeInputMethodService, uri: Uri): Boolean {
        val clipboard =
            service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            val clip = ClipData.newUri(service.contentResolver, "截图", uri)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(service, "已复制截图，长按输入框可粘贴", Toast.LENGTH_LONG).show()
            true
        }.onFailure { Timber.w(it, "copy screenshot to clipboard failed: %s", uri) }
            .getOrDefault(false)
    }

    private fun commitContent(
        service: TrimeInputMethodService,
        uri: Uri,
        mimeType: String,
    ): Boolean {
        val connection = service.currentInputConnection ?: return false
        val editorInfo = service.currentInputEditorInfo ?: return false
        if (!editorAcceptsImage(editorInfo, mimeType)) return false
        return runCatching {
            val description = ClipDescription("screenshot", arrayOf(mimeType))
            val contentInfo = InputContentInfoCompat(uri, description, null)
            InputConnectionCompat.commitContent(
                connection,
                editorInfo,
                contentInfo,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null,
            )
        }
            .onFailure { Timber.w(it, "commitContent screenshot failed: %s", uri) }
            .getOrDefault(false)
    }

    private fun editorAcceptsImage(editorInfo: EditorInfo, mimeType: String): Boolean {
        // contentMimeTypes 是 API 24 才有的字段，低版本编辑器不可能支持富媒体输入。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val acceptedTypes = editorInfo.contentMimeTypes ?: return true
        if (acceptedTypes.isEmpty()) return false
        return acceptedTypes.any { type ->
            type == "*/*" || type == "image/*" || type.equals(mimeType, ignoreCase = true)
        }
    }

    private fun share(
        service: TrimeInputMethodService,
        uri: Uri,
        mimeType: String,
    ): Boolean {
        val sendIntent =
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(service.contentResolver, "截图", uri)
            }
        val chooser =
            Intent.createChooser(sendIntent, "发送图片").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runCatching { service.startActivity(chooser) }
            .onFailure { Timber.w(it, "share screenshot failed: %s", uri) }
            .isSuccess
    }

    private fun resolveMimeType(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            ?.takeIf { it.startsWith("image/") }
            ?: "image/*"
}
