// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsDialogs
import com.osfans.trime.ui.settingskit.SettingsKitActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Download / import / delete page for the offline voice model. The keyboard
 * microphone key lands here with a hint when the model is not on the device.
 */
class VoiceModelActivity : SettingsKitActivity() {
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var busy = false
    private var message = ""
    private var status: TextView? = null

    override fun onDestroy() {
        tasks.cancel()
        super.onDestroy()
    }

    override fun pageTitle(): String = "语音模型"

    override fun buildPage(builder: SettingsBuilder) {
        status = null
        val miB = VoiceModelStore.TOTAL_BYTES / 1048576.0
        builder.space(7)
        builder.section("离线语音识别")
        val card = builder.group()
        when (VoiceModelStore.status(this)) {
            VoiceModelStore.Status.READY -> {
                builder.row(card, "语音识别模型", value = "已下载 ✓", summary = "%.1f MiB · 中文英文 · 完全离线".format(miB)) { }
                builder.row(card, "删除模型", value = "删除", summary = "删除后点麦克风会引导重新下载") {
                    if (!busy) {
                        SettingsDialogs.confirm(this, "删除语音模型？", "将释放 %.1f MiB 空间。".format(miB), "删除") {
                            VoiceModelStore.delete(this)
                            rerender()
                        }
                    }
                }
            }

            VoiceModelStore.Status.PARTIAL -> {
                message = "模型文件不完整，请重新下载或导入。"
                downloadRows(builder, card, miB)
            }

            VoiceModelStore.Status.MISSING -> downloadRows(builder, card, miB)
        }
        status =
            builder.note(message).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
            }
        builder.space(12)
        builder.section("说明")
        val license = builder.group()
        license.addView(
            TextView(this).apply {
                text =
                    "模型仅在本机运行，输入内容不出设备；下载动作只发送一次 GET 请求，不携带任何个人数据。\n\n" +
                        "来源：sherpa-onnx streaming zipformer 中英双语 int8（epoch-99 avg-1）\n" +
                        VoiceModelDownloader.MODEL_PAGE
                textSize = 14f
                setTextColor(palette.muted)
                setTypeface(Typeface.DEFAULT)
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun downloadRows(
        builder: SettingsBuilder,
        card: LinearLayout,
        miB: Double,
    ) {
        builder.row(card, "下载语音模型", value = "下载", summary = "%.1f MiB · 建议在 Wi-Fi 下进行".format(miB)) {
            if (!busy) {
                SettingsDialogs.confirm(this, "下载语音模型？", "下载约 %.0f MiB，需至少 %.0f MiB 可用空间。".format(miB, miB * 2), "下载") { download() }
            }
        }
        builder.row(card, "从本地文件导入", value = "导入", summary = "选择包含 5 个模型文件的 zip") {
            if (!busy) pickFile()
        }
    }

    private fun download() {
        busy = true
        message = "正在下载…"
        rerender()
        tasks.launch {
            try {
                withContext(Dispatchers.IO) {
                    VoiceModelDownloader.download(applicationContext) { progress ->
                        val text =
                            when {
                                progress.percent > 100 -> "正在校验安装… ${progress.percent - 100}%"
                                progress.percent == 100 -> "正在校验安装…"
                                progress.percent >= 0 -> "正在下载… ${progress.percent}%（%.1f MiB）".format(progress.downloadedBytes / 1048576.0)
                                else -> "正在下载… %.1f MiB".format(progress.downloadedBytes / 1048576.0)
                            }
                        runOnUiThread { if (!isDestroyed) status?.text = text }
                    }.getOrThrow()
                }
                message = ""
                Toast.makeText(this@VoiceModelActivity, "语音模型已就绪", Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "下载失败：${error.message}；可改用「从本地文件导入」。"
            } finally {
                busy = false
                if (!isDestroyed) rerender()
            }
        }
    }

    private fun pickFile() {
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
        startActivityForResult(Intent.createChooser(intent, "选择模型压缩包"), REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        busy = true
        message = "正在导入…"
        rerender()
        tasks.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
                    stream.use { VoiceModelStore.installZip(applicationContext, it) { }.getOrThrow() }
                }
                message = ""
                Toast.makeText(this@VoiceModelActivity, "语音模型已导入", Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                message = "导入失败：${error.message}"
            } finally {
                busy = false
                if (!isDestroyed) rerender()
            }
        }
    }

    private companion object {
        const val REQ_IMPORT = 41
    }
}
