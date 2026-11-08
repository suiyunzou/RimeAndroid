// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.system.Os
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.osfans.trime.core.data.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** SAF grants access only to the file chosen by the user; nothing is uploaded. */
class LocalDictionaryActivity : Activity() {
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { LocalDictionaryStore(DataManager.userDataDir) { from, to -> Os.rename(from.absolutePath, to.absolutePath) } }
    private var entries = emptyList<LocalDictionaryParser.Entry>()
    private var preview: LocalDictionaryParser.Result? = null
    private var filename = ""
    private var message = "导入的词条保存在此设备，返回键盘即可使用。"
    private var busy = false
    private var limit = 100
    private var wordPage = false
    private val ink = 0xff161619.toInt()
    private val muted = 0xff75757a.toInt()
    private val blue = 0xff1976d2.toInt()
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()
    private fun shape(color: Int, radius: Int = 20) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(value: String, size: Float = 16f, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
    private fun button(value: String, action: () -> Unit) = label(value, 17f, blue).apply {
        gravity = Gravity.CENTER; background = shape(Color.WHITE); contentDescription = value
        isClickable = true; isFocusable = true; alpha = if (busy) .5f else 1f
        setOnClickListener { if (!busy) action() }
    }
    override fun onCreate(state: Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        wordPage = state?.getBoolean("wordPage") ?: false
        render()
        runTask { entries = withContext(Dispatchers.IO) { store.read() } }
    }
    override fun onDestroy() { tasks.cancel(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putBoolean("wordPage", wordPage) }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (wordPage) { wordPage = false; render() } else finish() }
    private fun runTask(action: suspend () -> Unit) {
        if (busy) return
        busy = true; render()
        tasks.launch {
            try { action() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (error: Exception) { message = error.message ?: "操作失败，已有词库未改动" }
            finally { busy = false; if (!isFinishing && !isDestroyed) render() }
        }
    }
    @Suppress("DEPRECATION")
    private fun render() {
        val backgroundColor = 0xfff2f2f6.toInt()
        window.statusBarColor = backgroundColor; window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(backgroundColor); fitsSystemWindows = true }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        header.addView(button("‹ 返回") { onBackPressed() }.apply { alpha = 1f; setOnClickListener { onBackPressed() } }, LinearLayout.LayoutParams(dp(80), dp(42)))
        header.addView(label(if (wordPage) "我的词条" else "词库", 19f).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(View(this), LinearLayout.LayoutParams(dp(80), 1)); root.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        fun gap() { body.addView(View(this), LinearLayout.LayoutParams(1, dp(14))) }
        if (!wordPage) {
        gap()
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE); setPadding(0, dp(12), 0, dp(12))
            addView(label("✓  已就绪", 20f, 0xff239a55.toInt()).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(label("本地使用 · 无需联网", 15f, muted))
        })
        gap(); gap()
        val importCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE); clipToOutline = true }
        importCard.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; contentDescription = "我的词条"; isClickable = true
            addView(label("我的词条", 18f), LinearLayout.LayoutParams(0, -1, 1f))
            addView(label("${entries.size} 条  ›", 18f, muted), LinearLayout.LayoutParams(-2, -1))
            setOnClickListener { wordPage = true; render() }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        importCard.addView(button("从文件导入") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }, 18)
        }, LinearLayout.LayoutParams(-1, dp(52)))
        body.addView(importCard)
        gap()
        body.addView(label("TSV 或 Rime dict.yaml · UTF-8\n每行：词语 ⇥ 拼音 ⇥ 权重（可省略）\n最多 8 MB、10 万条。已有相同词条会保留。", 14f, muted).apply { background = shape(Color.WHITE) })
        gap(); body.addView(label(if (busy) "正在处理本地词库…" else message, 14f, muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE })
        preview?.let { pending ->
            gap()
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE) }
            card.addView(label(filename, 18f).apply { typeface = Typeface.DEFAULT_BOLD })
            val old = entries.map { it.key }.toHashSet()
            val newCount = pending.entries.count { it.key !in old }
            card.addView(label("有效 ${pending.entries.size} 条 · 新增 $newCount 条\n重复 ${pending.duplicates} 条 · 无效 ${pending.invalid} 行", 15f, muted))
            if (pending.errors.isNotEmpty()) card.addView(label("无效行：${pending.errors.joinToString("、")}", 13f, 0xffb4353c.toInt()))
            pending.entries.take(8).forEach { card.addView(label("${it.text}  ·  ${it.code}", 15f)) }
            card.addView(button("确认导入 $newCount 条") {
                runTask {
                    val added = withContext(Dispatchers.IO) { store.append(pending.entries) }
                    entries = withContext(Dispatchers.IO) { store.read() }
                    preview = null; message = "已导入 $added 条；返回键盘后立即生效。"; limit = 100
                }
            }, LinearLayout.LayoutParams(-1, dp(48)))
            card.addView(button("取消本次导入") { preview = null; render() }, LinearLayout.LayoutParams(-1, dp(48)))
            body.addView(card)
        }
        }
        if (wordPage) {
        gap(); body.addView(label("已导入 ${entries.size} 条", 17f).apply { typeface = Typeface.DEFAULT_BOLD })
        body.addView(label("长按词条可删除。删除只影响本页导入的词库。", 13f, muted))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE); clipToOutline = true }
        entries.take(limit).forEach { entry ->
            list.addView(label("${entry.text}\n${entry.code}", 16f).apply {
                contentDescription = "词条 ${entry.text} ${entry.code}"
                setOnLongClickListener {
                    if (!busy) AlertDialog.Builder(this@LocalDictionaryActivity).setTitle("删除词条")
                        .setMessage("${entry.text}  ${entry.code}")
                        .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                            runTask {
                                withContext(Dispatchers.IO) { store.remove(entry.key) }
                                entries = withContext(Dispatchers.IO) { store.read() }
                                message = "已删除“${entry.text}”"
                            }
                        }.show()
                    true
                }
            })
        }
        body.addView(list)
        if (limit < entries.size) { gap(); body.addView(button("显示更多词条") { limit = minOf(limit + 100, entries.size); render() }, LinearLayout.LayoutParams(-1, dp(48))) }
        }
        setContentView(root)
    }
    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 18 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        preview = null
        runTask {
            val pair = withContext(Dispatchers.IO) { readSelection(uri).let { it.first to LocalDictionaryParser.parse(it.second) } }
            filename = pair.first; preview = pair.second
            message = "请核对预览，再确认导入。"
        }
    }
    private fun readSelection(uri: Uri): Pair<String, ByteArray> {
        val name = runCatching { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).take(100) else null
        } }.getOrNull() ?: "所选词库"
        val output = ByteArrayOutputStream()
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                require(output.size() + count <= LocalDictionaryParser.MAX_BYTES) { "文件超过 8 MB，请先拆分词库" }
                output.write(buffer, 0, count)
            }
        } ?: error("无法读取所选文件")
        return name to output.toByteArray()
    }
}
