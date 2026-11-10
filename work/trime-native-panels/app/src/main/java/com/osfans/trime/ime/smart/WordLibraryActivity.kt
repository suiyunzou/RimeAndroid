// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.system.Os
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.data.localrules.LocalRuleStore
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.osfans.trime.worker.PackAutoUpdateWork
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * 词库页：公开词包（版本 / 上次检查 / 进度 / 失败原因 / 重试 / 自动更新）、
 * 我的词条（导入、手动添加、长按删除）、旧版词条（只读）与学习隐私入口。
 *
 * 数据隔离：
 * - 公开词包只经 [PackStore] / [PackInstaller] 写入 `smart_packs/` 与 `smart_packs.json`。
 * - 我的词条只经 [LocalDictionaryStore] 写 `smart_imported.tsv`。
 * - 旧版词条只读展示，绝不迁移、改写或删除。
 *
 * 深浅色跟随 [AppPrefs.Advanced.uiMode]（AUTO 时再看系统夜间模式），
 * 所有颜色取自 [SettingsPalette]，本文件不写任何固定浅色。
 */
class WordLibraryActivity : Activity() {
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val store by lazy {
        LocalDictionaryStore(DataManager.userDataDir) { from, to -> Os.rename(from.absolutePath, to.absolutePath) }
    }
    private val packs by lazy {
        PackStore(DataManager.userDataDir) { from, to -> Os.rename(from.absolutePath, to.absolutePath) }
    }

    private var entries = emptyList<LocalDictionaryParser.Entry>()
    private var legacy = emptyList<LocalRuleStore.Rule>()
    private var packState = PackStore.State()
    private var preview: LocalDictionaryParser.Result? = null
    private var filename = ""

    private var busy = false
    /** 非空表示有进行中的任务文案，显示在「公开词包」卡片里。 */
    private var progressText = ""
    private var progressPercent = -1
    private var resultLine = ""
    private var resultIsFailure = false
    private var limit = 50
    private var dark = false

    private fun dp(value: Int) = SettingsPalette.dp(this, value)

    /** AUTO 跟随系统，LIGHT / DARK 强制；绝不硬编码。 */
    private fun resolveDark(): Boolean {
        val mode = AppPrefs.defaultInstance().advanced.uiMode.getValue()
        return when (mode) {
            AppPrefs.Advanced.UiMode.DARK -> true
            AppPrefs.Advanced.UiMode.LIGHT -> false
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
    }

    /** 自动更新开关：默认关闭，仅 Wi-Fi。 */
    private val autoPrefs by lazy { getSharedPreferences("smart_input", MODE_PRIVATE) }
    private fun autoUpdateEnabled() = autoPrefs.getBoolean(KEY_AUTO_UPDATE, false)
    private fun setAutoUpdate(enabled: Boolean) = autoPrefs.edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()

    @Suppress("DEPRECATION")
    override fun onCreate(state: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(state)
        render()
        refreshLocal()
    }

    override fun onDestroy() {
        tasks.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // 从隐私页返回时要跟随可能已改变的主题设置。
        if (!busy && resolveDark() != dark) render()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (busy) return
        finish()
    }

    private fun runTask(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        progressText = "正在处理…"
        progressPercent = -1
        render()
        tasks.launch {
            try {
                action()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                resultLine = error.message ?: "操作失败，已有词库未改动"
                resultIsFailure = true
            } finally {
                busy = false
                progressText = ""
                progressPercent = -1
                if (!isFinishing && !isDestroyed) render()
            }
        }
    }

    private fun refreshLocal() {
        tasks.launch {
            try {
                withContext(Dispatchers.IO) {
                    LocalDictionaryIntegration.ensureInstalled(assets)
                    entries = store.read()
                    legacy = runCatching { LocalRuleStore(DataManager.userDataDir).loadRules().items }.getOrDefault(emptyList())
                    packState = packs.read()
                }
            } catch (error: Exception) {
                resultLine = error.message ?: "无法读取本地词库"
                resultIsFailure = true
            }
            if (!isDestroyed) render()
        }
    }

    @Suppress("DEPRECATION")
    private fun render() {
        dark = resolveDark()
        val palette = SettingsPalette.paletteOf(dark)
        window.statusBarColor = palette.background
        window.navigationBarColor = palette.background
        window.decorView.systemUiVisibility =
            if (!dark && Build.VERSION.SDK_INT >= 26) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                0
            }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            fitsSystemWindows = true
        }

        root.addView(buildHeader(palette), LinearLayout.LayoutParams(-1, dp(58)))

        val builder = SettingsBuilder(this, dark)
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), dp(7), dp(16), dp(24))
            addView(builder.body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        publicPackSection(builder, palette)
        myEntriesSection(builder)
        legacySection(builder)
        privacySection(builder)

        setContentView(root)
    }

    private fun buildHeader(palette: com.osfans.trime.ui.settingskit.Palette): FrameLayout {
        val header = FrameLayout(this).apply { setPadding(dp(16), dp(7), dp(16), dp(7)) }
        val back = TextView(this).apply {
            text = "‹ 返回"
            textSize = 19f
            setTextColor(palette.ink)
            gravity = Gravity.CENTER
            background = rounded(palette.track, 24)
            contentDescription = "返回"
            isClickable = true
            alpha = if (busy) .4f else 1f
            setOnClickListener { if (!busy) onBackPressed() }
        }
        header.addView(back, FrameLayout.LayoutParams(dp(83), dp(40), Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(
            TextView(this).apply {
                text = "词库"
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.ink)
                gravity = Gravity.CENTER
            },
            FrameLayout.LayoutParams(dp(160), dp(44), Gravity.CENTER),
        )
        return header
    }

    private fun rounded(color: Int, radiusDp: Int = 22) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    // ---------------------------------------------------------------- 分组 1：公开词包

    private fun publicPackSection(builder: SettingsBuilder, palette: com.osfans.trime.ui.settingskit.Palette) {
        builder.section("公开词包")
        val card = builder.group()

        val installed = packState.packs.values.sortedBy { it.title }
        if (installed.isEmpty()) {
            builder.row(card, "已装版本", "尚未安装")
        } else {
            installed.forEach { pack ->
                builder.row(card, pack.title.ifEmpty { "公开词包" }, "v${pack.version}")
            }
        }

        builder.row(card, "上次检查", formatTime(packState.lastCheckTime))

        // 进度条：仅在真正下载时出现，放在卡片内部，颜色取自 palette。
        if (busy) {
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(4), dp(16), dp(12))
            }
            holder.addView(
                TextView(this).apply {
                    text = progressText.ifEmpty { "正在处理…" }
                    textSize = 14f
                    setTextColor(palette.muted)
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                },
            )
            holder.addView(
                ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = progressPercent < 0
                    if (progressPercent >= 0) {
                        max = 100
                        progress = progressPercent
                    }
                    contentDescription = progressText.ifEmpty { "正在处理" }
                },
                LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(8) },
            )
            card.addView(holder)
        }

        // 失败 / 成功原因：失败时附带「重试」。
        val failure = packState.lastFailure
        val line = when {
            resultIsFailure && resultLine.isNotEmpty() -> resultLine
            !resultIsFailure && resultLine.isNotEmpty() -> resultLine
            !failure.isNullOrBlank() -> failure
            else -> ""
        }
        if (line.isNotEmpty()) {
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(6), dp(16), dp(12))
            }
            val isBad = if (resultLine.isNotEmpty()) resultIsFailure else !failure.isNullOrBlank()
            holder.addView(
                TextView(this).apply {
                    text = line
                    textSize = 14f
                    setTextColor(palette.muted)
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                },
            )
            if (isBad && !busy) {
                holder.addView(
                    TextView(this).apply {
                        text = "重试"
                        textSize = 16f
                        setTextColor(ACCENT)
                        gravity = Gravity.CENTER
                        background = rounded(palette.track, 20)
                        contentDescription = "重试词包更新"
                        isClickable = true
                        setOnClickListener { checkForUpdates() }
                    },
                    LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) },
                )
            }
            card.addView(holder)
        }

        // 检查更新按钮（busy 时禁用）。
        val check = TextView(this).apply {
            text = if (installed.isEmpty()) "下载公开词包" else "检查更新"
            textSize = 17f
            setTextColor(palette.ink)
            gravity = Gravity.CENTER
            background = rounded(palette.track, 22)
            contentDescription = text
            isClickable = true
            alpha = if (busy) .4f else 1f
            setOnClickListener { if (!busy) checkForUpdates() }
        }
        card.addView(check, LinearLayout.LayoutParams(-1, dp(52)).apply {
            leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(6)
        })

        // 自动更新：默认关闭，仅 Wi-Fi。
        builder.toggle(card, "自动更新", autoUpdateEnabled()) { enabled ->
            setAutoUpdate(enabled)
            // 把开关状态同步给 WorkManager：开启才真正入队周期任务，关闭则取消。
            runCatching { PackAutoUpdateWork.sync(this) }
                .onFailure { Timber.w(it, "同步后台更新任务失败") }
            resultLine = if (enabled) "已开启自动更新：仅在 Wi-Fi 且已联网时检查" else "已关闭自动更新"
            resultIsFailure = false
            render()
        }
        val note = builder.note("自动更新默认关闭。开启后仅在 Wi-Fi（不计流量）、已联网且电量充足时检查；下载只发 GET 请求，不上传任何输入内容。")
        note.setPadding(dp(14), dp(4), dp(14), dp(12))

        builder.space(16)
    }

    /** 检查并更新公开词包。任何失败都只更新文案，绝不动已装文件与个人词条。 */
    private fun checkForUpdates() {
        if (busy) return
        busy = true
        progressText = "正在检查词包…"
        progressPercent = -1
        resultLine = ""
        resultIsFailure = false
        render()
        tasks.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val installer = PackInstaller(
                        DataManager.userDataDir,
                        packs,
                        PackInstaller.HttpFetcher(),
                        { System.currentTimeMillis() },
                        { from, to -> Os.rename(from.absolutePath, to.absolutePath) },
                    )
                    var last = 0L
                    installer.checkAndUpdate(MANIFEST_URL, appVersionCode(), { done, total ->
                        val now = System.currentTimeMillis()
                        if (now - last > 200) {
                            last = now
                            runOnUiThread {
                                if (isDestroyed) return@runOnUiThread
                                progressText = if (total > 0) {
                                    "正在下载：${done * 100L / total}%（${done / 1024} / ${total / 1024} KB）"
                                } else {
                                    "正在下载：${done / 1024} KB"
                                }
                                progressPercent = if (total > 0) (done * 100L / total).toInt().coerceIn(0, 100) else -1
                                render()
                            }
                        }
                    })
                }
                resultLine = result.display()
                resultIsFailure = result.isFailure
                // 远端清单不可达时，用随包内置清单兜底展示，并明确告知原因，
                // 而不是只给一句"网络错误"让人以为功能坏了。
                if (result is UpdateResult.NetworkError) {
                    val bundled = readBundledManifest(this@WordLibraryActivity)
                    if (bundled != null) {
                        resultLine = "${result.message}；已改用随包内置清单，已有词包未改动"
                    }
                }
                packState = withContext(Dispatchers.IO) { packs.read() }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                resultLine = "检查失败：${error.message ?: "未知错误"}，已有词包与词条未改动"
                resultIsFailure = true
            } finally {
                busy = false
                progressText = ""
                progressPercent = -1
                if (!isFinishing && !isDestroyed) render()
            }
        }
    }

    private fun appVersionCode(): Int = try {
        packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (_: Exception) {
        Int.MAX_VALUE
    }

    private fun formatTime(millis: Long): String =
        if (millis <= 0L) "从未检查" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    // ---------------------------------------------------------------- 分组 2：我的词条

    private fun myEntriesSection(builder: SettingsBuilder) {
        builder.section("我的词条")
        val card = builder.group()
        builder.row(card, "已导入", "${entries.size} 条")
        builder.row(card, "从文件导入") { importFromFile() }
        builder.row(card, "手动添加词条") { addEntry() }
        if (entries.isNotEmpty() && !busy) {
            builder.row(card, "长按下方词条可删除")
        }

        // 导入预览
        preview?.let { pending ->
            val old = entries.map { it.key }.toHashSet()
            val newCount = pending.entries.count { it.key !in old }
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(12))
            }
            val palette = SettingsPalette.paletteOf(dark)
            holder.addView(TextView(this).apply {
                text = filename.ifEmpty { "所选词库" }
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.ink)
            })
            holder.addView(TextView(this).apply {
                text = "共 ${pending.entries.size} 条 · 可新增 $newCount 条"
                textSize = 14f
                setTextColor(palette.muted)
                setPadding(0, dp(4), 0, 0)
            })
            if (pending.errors.isNotEmpty()) {
                holder.addView(TextView(this).apply {
                    text = "无效行：${pending.errors.joinToString("、")}"
                    textSize = 13f
                    setTextColor(palette.muted)
                })
            }
            holder.addView(TextView(this).apply {
                text = "确认导入 $newCount 条"
                textSize = 16f
                setTextColor(ACCENT)
                gravity = Gravity.CENTER
                background = rounded(palette.track, 20)
                contentDescription = "确认导入 $newCount 条"
                isClickable = true
                setOnClickListener {
                    runTask {
                        val added = withContext(Dispatchers.IO) {
                            LocalDictionaryIntegration.ensureInstalled(assets)
                            store.append(pending.entries)
                        }
                        entries = withContext(Dispatchers.IO) { store.read() }
                        preview = null
                        resultLine = "已导入 $added 条"
                        resultIsFailure = false
                    }
                }
            }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
            holder.addView(TextView(this).apply {
                text = "取消本次导入"
                textSize = 15f
                setTextColor(palette.muted)
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { preview = null; render() }
            }, LinearLayout.LayoutParams(-1, dp(40)))
            card.addView(holder)
        }

        // 词条列表：长按删除，只动 smart_imported.tsv。
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        entries.take(limit).forEach { entry ->
            val palette = SettingsPalette.paletteOf(dark)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                contentDescription = "词条 ${entry.text} ${entry.code}"
                isClickable = true
                alpha = if (busy) .5f else 1f
                setOnLongClickListener {
                    if (!busy) confirmDelete(entry)
                    true
                }
            }
            row.addView(TextView(this).apply {
                text = entry.text
                textSize = 17f
                setTextColor(palette.ink)
            })
            row.addView(TextView(this).apply {
                text = entry.code
                textSize = 14f
                setTextColor(palette.muted)
                setPadding(0, dp(2), 0, 0)
            })
            list.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        if (list.childCount > 0) card.addView(list)
        if (limit < entries.size) {
            builder.row(card, "显示更多词条", "${entries.size - limit} 条") {
                limit = minOf(limit + 50, entries.size)
                render()
            }
        }
        builder.note("词条只保存在本机 smart_imported.tsv；导入不会覆盖已有词条。")
        builder.space(16)
    }

    private fun confirmDelete(entry: LocalDictionaryParser.Entry) {
        AlertDialog.Builder(this)
            .setTitle("删除词条")
            .setMessage("${entry.text}  ${entry.code}")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                runTask {
                    withContext(Dispatchers.IO) { store.remove(entry.key) }
                    entries = withContext(Dispatchers.IO) { store.read() }
                    resultLine = "已删除“${entry.text}”"
                    resultIsFailure = false
                }
            }
            .show()
    }

    private fun addEntry() {
        val palette = SettingsPalette.paletteOf(dark)
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val word = EditText(this).apply {
            hint = "词语，例如：星河工作室"
            maxLines = 1
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
        }
        val code = EditText(this).apply {
            hint = "拼音，例如：xing he gong zuo shi"
            maxLines = 1
            setTextColor(palette.ink)
            setHintTextColor(palette.muted)
        }
        fields.addView(word)
        fields.addView(code)
        val dialog = AlertDialog.Builder(this)
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
                    resultLine = if (added == 0) "该词条已存在" else "已添加“${entry.text}”"
                    resultIsFailure = false
                }
            }
        }
        dialog.show()
    }

    private fun importFromFile() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            REQUEST_IMPORT,
        )
    }

    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        preview = null
        runTask {
            val pair = withContext(Dispatchers.IO) {
                readSelection(uri).let { it.first to LocalDictionaryParser.parse(it.second) }
            }
            filename = pair.first
            preview = pair.second
            resultLine = ""
        }
    }

    private fun readSelection(uri: Uri): Pair<String, ByteArray> {
        val name = runCatching {
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

    // ---------------------------------------------------------------- 分组 3：旧版词条

    private fun legacySection(builder: SettingsBuilder) {
        builder.section("旧版词条")
        val card = builder.group()
        if (legacy.isEmpty()) {
            builder.row(card, "旧版词条", "无")
        } else {
            builder.row(card, "条目数", "${legacy.size} 条")
            legacy.take(LEGACY_PREVIEW).forEach { rule ->
                builder.row(card, rule.trigger, rule.value)
            }
            if (legacy.size > LEGACY_PREVIEW) {
                builder.row(card, "其余条目", "${legacy.size - LEGACY_PREVIEW} 条未显示")
            }
        }
        val note = builder.note("已保留，不参与自动更新；此页只读，不会修改或删除这些条目。")
        note.setPadding(dp(14), dp(4), dp(14), dp(12))
        builder.space(16)
    }

    // ---------------------------------------------------------------- 分组 4：学习与隐私

    private fun privacySection(builder: SettingsBuilder) {
        builder.section("学习与隐私")
        val card = builder.group()
        builder.row(card, "离线与隐私", "查看") {
            startActivity(Intent(this, OfflinePrivacyActivity::class.java))
        }
        builder.note("上述数据全部保存在本机；下载公开词包时不上传任何输入内容。")
        builder.space(24)
    }

    companion object {
        private const val KEY_AUTO_UPDATE = "word_library_auto_update"
        private const val REQUEST_IMPORT = 18
        private const val LEGACY_PREVIEW = 20
        private const val ACCENT_LIGHT = 0xFF1976D2.toInt()

        /**
         * 词包清单地址。与本仓库其他网络请求一致：只发 GET，不携带任何用户数据。
         *
         * 该地址由本仓库自行发布（`packs/manifest.json`），离线时由随包内置的
         * `assets/local-dictionary/manifest.json` 兜底，见 [readBundledManifest]。
         */
        const val MANIFEST_URL: String =
            "https://raw.githubusercontent.com/suiyunzou/RimeAndroid/main/packs/manifest.json"

        /** 随包内置的清单，作为远端不可达时的兜底，保证词库页永远有内容可展示。 */
        private const val BUNDLED_MANIFEST_ASSET = "local-dictionary/manifest.json"

        /**
         * 读取内置清单文本。仅用于远端不可达时的降级展示与「已装版本」比对，
         * 不覆盖任何用户数据；失败返回 null。
         */
        fun readBundledManifest(context: android.content.Context): String? = runCatching {
            context.assets.open(BUNDLED_MANIFEST_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()

        private val ACCENT get() = ACCENT_LIGHT
    }
}
