// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.system.Os
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsKitActivity
import com.osfans.trime.ui.settingskit.SettingsTopBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** SAF grants access only to the file chosen by the user; nothing is uploaded. */
class LocalDictionaryActivity : SettingsKitActivity() {
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { LocalDictionaryStore(DataManager.userDataDir) { from, to -> Os.rename(from.absolutePath, to.absolutePath) } }
    private var entries = emptyList<LocalDictionaryParser.Entry>()
    private var preview: LocalDictionaryParser.Result? = null
    private var filename = ""
    private var message = ""
    private var busy = false
    private var progressText = ""
    private var limit = 100
    private var wordPage = false

    override fun onCreate(state: Bundle?) {
        wordPage = state?.getBoolean("wordPage") ?: false
        super.onCreate(state)
        refreshTopBar()
        runTask {
            withContext(Dispatchers.IO) { LocalDictionaryIntegration.ensureInstalled(assets) }
            entries = withContext(Dispatchers.IO) { store.read() }
        }
    }

    override fun onDestroy() {
        tasks.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean("wordPage", wordPage)
    }

    override fun pageTitle(): String = if (wordPage) "我的词条" else "词库"

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (wordPage) {
            wordPage = false
            rerenderPage(scrollToTop = true)
        } else {
            finish()
        }
    }

    override fun buildPage(builder: SettingsBuilder) {
        if (wordPage) wordPageContent(builder) else mainPage(builder)
    }

    /** 基类顶栏在 onCreate 时固定；切换子页后重建同一组件，返回胶囊改为页面内返回。 */
    @Suppress("DEPRECATION")
    private fun refreshTopBar() {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        val bar = SettingsTopBar.create(context = this, title = pageTitle(), palette = palette, onBack = { onBackPressed() })
        root.removeViewAt(0)
        root.addView(bar, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
    }

    private fun rerenderPage(scrollToTop: Boolean = false) {
        rerender()
        refreshTopBar()
        if (scrollToTop) {
            // 基类 rerender 会保留滚动位置，切换子页时回到页首。
            val scroll = builder.body.parent as? ScrollView ?: return
            scroll.post { scroll.scrollTo(0, 0) }
        }
    }

    private fun runTask(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        progressText = ""
        rerenderPage()
        tasks.launch {
            try {
                action()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                message = error.message ?: "操作失败，已有词库未改动"
            } finally {
                busy = false
                if (!isFinishing && !isDestroyed) rerenderPage()
            }
        }
    }

    private fun mainPage(builder: SettingsBuilder) {
        builder.space(7)
        val stateCard = builder.group()
        val stateRow = builder.row(stateCard, "✓  已就绪")
        stateRow.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

        builder.space(12)
        builder.section("词库")
        val card = builder.group()
        actionRow(card, "我的词条", "${entries.size} 条") {
            wordPage = true
            rerenderPage(scrollToTop = true)
        }
        actionRow(card, "从文件导入") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                18,
            )
        }
        actionRow(card, "手动添加词条") { addEntry() }

        builder.space(12)
        builder.section("公开词库")
        val online = builder.group()
        actionRow(online, "下载万象公开词库") { chooseDownload() }
        builder.row(online, "导入格式与词库来源") { showDictionaryInfo() }

        if (busy || message.isNotEmpty()) {
            builder.space(8)
            builder.note(if (busy) progressText.ifEmpty { "正在处理…" } else message)
        }
        preview?.let { pending -> previewCard(builder, pending) }
    }

    private fun previewCard(
        builder: SettingsBuilder,
        pending: LocalDictionaryParser.Result,
    ) {
        val known = entries.map { it.key }.toHashSet()
        val newCount = pending.entries.count { it.key !in known }
        builder.space(12)
        builder.section("待确认导入")
        val card = builder.group()
        builder.row(card, filename, summary = "共 ${pending.entries.size} 条 · 可新增 $newCount 条")
        if (pending.errors.isNotEmpty()) {
            builder.row(card, "无效行", summary = pending.errors.joinToString("、"))
        }
        pending.entries.take(3).forEach { builder.row(card, it.text, value = it.code) }
        actionRow(card, "确认导入 $newCount 条") {
            runTask {
                val added = withContext(Dispatchers.IO) {
                    LocalDictionaryIntegration.ensureInstalled(assets)
                    store.append(pending.entries)
                }
                entries = withContext(Dispatchers.IO) { store.read() }
                preview = null
                message = "已导入 $added 条"
                limit = 100
            }
        }
        builder.row(card, "取消本次导入") {
            preview = null
            rerenderPage()
        }
    }

    private fun wordPageContent(builder: SettingsBuilder) {
        builder.space(7)
        val head = builder.group()
        builder.row(head, "已导入", "${entries.size} 条")
        actionRow(head, "手动添加词条") { addEntry() }
        builder.space(8)
        builder.note("长按词条可删除")

        builder.space(12)
        builder.section("词条")
        val card = builder.group()
        if (entries.isEmpty()) builder.row(card, "暂无词条")
        entries.take(limit).forEach { entry ->
            val row = builder.row(card, entry.text, summary = entry.code)
            row.contentDescription = "词条 ${entry.text} ${entry.code}"
            row.setOnLongClickListener {
                if (!busy) {
                    AlertDialog
                        .Builder(this@LocalDictionaryActivity)
                        .setTitle("删除词条")
                        .setMessage("${entry.text}  ${entry.code}")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除") { _, _ ->
                            runTask {
                                withContext(Dispatchers.IO) { store.remove(entry.key) }
                                entries = withContext(Dispatchers.IO) { store.read() }
                                message = "已删除“${entry.text}”"
                            }
                        }.show()
                }
                true
            }
        }
        if (limit < entries.size) {
            builder.space(8)
            builder.button("显示更多词条") {
                limit = minOf(limit + 100, entries.size)
                rerenderPage()
            }
        }
    }

    /** 操作行：忙碌时变淡并忽略点击，与旧版按钮一致。 */
    private fun actionRow(
        card: LinearLayout,
        title: String,
        value: String = "",
        action: () -> Unit,
    ): LinearLayout {
        val row =
            builder.row(card, title, value) {
                if (!busy) action()
            }
        row.alpha = if (busy) 0.5f else 1f
        return row
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 18 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        preview = null
        runTask {
            val pair = withContext(Dispatchers.IO) { readSelection(uri).let { it.first to LocalDictionaryParser.parse(it.second) } }
            filename = pair.first
            preview = pair.second
            message = ""
        }
    }

    private fun addEntry() {
        val fields =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
            }
        val word = EditText(this).apply { hint = "词语，例如：星河工作室"; maxLines = 1 }
        val code = EditText(this).apply { hint = "拼音，例如：xing he gong zuo shi"; maxLines = 1 }
        fields.addView(word)
        fields.addView(code)
        val dialog =
            AlertDialog
                .Builder(this)
                .setTitle("添加我的词条")
                .setView(fields)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entry = LocalDictionaryParser.parseEntry(word.text.toString(), code.text.toString(), "1000")
                if (entry == null) {
                    code.error = "请输入词语和有效拼音，音节用空格分开"
                    return@setOnClickListener
                }
                dialog.dismiss()
                runTask {
                    val added = withContext(Dispatchers.IO) {
                        LocalDictionaryIntegration.ensureInstalled(assets)
                        store.append(listOf(entry))
                    }
                    entries = withContext(Dispatchers.IO) { store.read() }
                    message = if (added == 0) "该词条已存在" else "已添加“${entry.text}”"
                }
            }
        }
        dialog.show()
    }

    private fun showDictionaryInfo() {
        AlertDialog
            .Builder(this)
            .setTitle("导入与来源")
            .setMessage(
                "支持 UTF-8 的 TSV 或 Rime dict.yaml。\n每行：词语、拼音、权重（可省略），用制表符分隔。最多 8 MB、10 万条。\n\n" +
                    OnlineDictionarySource.attribution,
            ).setPositiveButton("完成", null)
            .show()
    }

    private fun chooseDownload() {
        AlertDialog
            .Builder(this)
            .setTitle("下载万象公开词库")
            .setItems(OnlineDictionarySource.sources.map { "${it.title}\n${it.description}" }.toTypedArray()) { _, index ->
                val source = OnlineDictionarySource.sources[index]
                AlertDialog
                    .Builder(this)
                    .setTitle(source.title)
                    .setMessage("${source.description}\n下载后可预览词条。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("下载并预览") { _, _ ->
                        runTask {
                            var last = 0L
                            preview = withContext(Dispatchers.IO) {
                                OnlineDictionarySource.download(source) { done, total ->
                                    val now = System.currentTimeMillis()
                                    if (now - last > 250) {
                                        last = now
                                        runOnUiThread {
                                            progressText = if (total > 0) "正在下载：${done * 100L / total}%" else "正在下载：${done / 1024} KB"
                                            if (!isDestroyed) rerenderPage()
                                        }
                                    }
                                }
                            }
                            filename = source.title
                            message = ""
                        }
                    }.show()
            }.setNegativeButton("取消", null)
            .show()
    }

    private fun readSelection(uri: Uri): Pair<String, ByteArray> {
        val name =
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0).take(100) else null
                }
            }.getOrNull() ?: "所选词库"
        val output = ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= LocalDictionaryParser.MAX_BYTES) { "文件超过 8 MB，请先拆分词库" }
                output.write(buffer, 0, count)
            }
        } ?: error("无法读取所选文件")
        return name to output.toByteArray()
    }
}
