// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.graphics.Typeface
import android.os.Bundle
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.system.Os
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsDialogs
import com.osfans.trime.ui.settingskit.SettingsKitActivity
import com.osfans.trime.ui.settingskit.SettingsTopBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class ModelManagerActivity : SettingsKitActivity() {
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { ModelManagerStore(DataManager.userDataDir) { a, b -> Os.rename(a.absolutePath, b.absolutePath) } }
    private var busy = false

    @Volatile
    private var switching = false
    private var current: ModelManagerStore.Model? = null
    private var message = "正在读取…"
    private var information = false
    private var status: TextView? = null

    override fun onCreate(state: Bundle?) {
        busy = true
        super.onCreate(state)
        refreshTopBar()
        tasks.launch {
            try {
                current = withContext(Dispatchers.IO) { store.identify() }
                message = ""
            } catch (error: Exception) {
                message = "无法校验当前模型：${error.message}"
            } finally {
                busy = false
                if (!isDestroyed) rerenderPage()
            }
        }
    }

    override fun onDestroy() {
        tasks.cancel()
        super.onDestroy()
    }

    override fun pageTitle(): String = if (information) "关于模型" else "整句模型"

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (switching) return
        if (information) {
            information = false
            rerenderPage(scrollToTop = true)
        } else {
            finish()
        }
    }

    override fun buildPage(builder: SettingsBuilder) {
        status = null
        if (information) informationPage(builder) else modelsPage(builder)
    }

    /** 基类顶栏在 onCreate 时固定；切换子页后重建同一组件，带上右侧说明按钮。 */
    @Suppress("DEPRECATION")
    private fun refreshTopBar() {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) as? LinearLayout ?: return
        val info =
            if (information) {
                null
            } else {
                TextView(this).apply {
                    text = "ⓘ"
                    textSize = 23f
                    setTextColor(palette.muted)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(2), 0)
                    contentDescription = "模型说明"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        information = true
                        rerenderPage(scrollToTop = true)
                    }
                }
            }
        val bar = SettingsTopBar.create(context = this, title = pageTitle(), palette = palette, onBack = { onBackPressed() }, rightView = info)
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

    private fun modelsPage(builder: SettingsBuilder) {
        builder.space(7)
        val card = builder.group()
        val active = ModelManagerStore.active(DataManager.userDataDir)
        if (!busy && current == null && active.isFile) {
            builder.row(card, "自定义模型", value = "${"%.1f".format(active.length() / 1048576.0)} MiB ✓")
        }
        ModelManagerStore.models.forEach { model ->
            val installed = store.installed(model).isFile || current == model
            val action = if (current == model) "✓" else if (installed || model == ModelManagerStore.compact) "使用" else "下载"
            val row =
                builder.row(card, model.title, value = action, summary = "${"%.1f".format(model.bytes / 1048576.0)} MiB") {
                    if (!busy) {
                        if (!installed && model == ModelManagerStore.full) {
                            SettingsDialogs.confirm(
                                this,
                                "下载完整版？",
                                "下载约 399 MiB，需至少 850 MiB 可用空间。",
                                "下载",
                            ) { select(model) }
                        } else {
                            select(model)
                        }
                    }
                }
            row.contentDescription = "${model.title}，${if (current == model) "当前使用" else action}"
            row.alpha = if (busy) 0.5f else 1f
        }
        status =
            builder.note(message).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
            }
    }

    private fun informationPage(builder: SettingsBuilder) {
        builder.space(7)
        builder.section("版本")
        val card = builder.group()
        card.addView(
            bodyText("精简版省空间；完整版保留更多词语搭配。两者均在本机运行。", 16f, palette.ink),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        builder.space(12)
        builder.section("来源与许可")
        val license = builder.group()
        license.addView(
            TextView(this).apply {
                text =
                    "amzxyz 与 Wanxiang / RIME-LMDG 贡献者\nCC BY 4.0\n\n" +
                        "https://github.com/amzxyz/rime-wanxiang-data/releases/tag/LTS\n" +
                        "https://creativecommons.org/licenses/by/4.0/\n\n" +
                        "精简 v2 为本项目裁剪版本，保留原始权重及四字搭配。"
                textSize = 14f
                setTextColor(palette.muted)
                autoLinkMask = Linkify.WEB_URLS
                linksClickable = true
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun bodyText(
        value: String,
        size: Float,
        color: Int,
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setTypeface(Typeface.DEFAULT)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

    private fun select(model: ModelManagerStore.Model) {
        busy = true
        message = "准备模型…"
        rerenderPage()
        tasks.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    if (!store.installed(model).isFile) {
                        var last = 0L
                        val progress: (Long) -> Unit = { n ->
                            context.ensureActive()
                            val now = System.currentTimeMillis()
                            if (now - last > 250) {
                                last = now
                                runOnUiThread { if (!isDestroyed) status?.text = "正在安装：${n * 100 / model.bytes}%" }
                            }
                        }
                        if (current == model) {
                            store.install(model, ModelManagerStore.active(DataManager.userDataDir).inputStream(), progress)
                        } else if (model == ModelManagerStore.compact) {
                            store.install(model, assets.open("local-models/wanxiang-compact-v2.gram"), progress)
                        } else {
                            val connection = URL("https://github.com/amzxyz/rime-wanxiang-data/releases/download/LTS/wanxiang-lts-zh-hans.gram").openConnection() as HttpURLConnection
                            connection.connectTimeout = 20000
                            connection.readTimeout = 30000
                            try {
                                require(connection.responseCode == 200) { "下载失败 HTTP ${connection.responseCode}" }
                                store.install(model, connection.inputStream, progress)
                            } finally {
                                connection.disconnect()
                            }
                        }
                    }
                    ensureActive()
                    require(store.installed(model).length() == model.bytes && ModelManagerStore.hash(store.installed(model)) == model.sha) {
                        "模型校验失败，请检查安装文件；原模型与方案未改动"
                    }
                    switching = true
                    runOnUiThread { if (!isDestroyed) status?.text = "正在切换…" }
                    // Once replacement begins, finish or restore it even if Android destroys this Activity.
                    withContext(NonCancellable) {
                        val snapshot = store.snapshotActive()
                        try {
                            ModelSchemaIntegration.ensureConfigured()
                            store.select(model)
                            RimeDaemon.restartRimeAndAwaitReady()
                        } catch (error: Exception) {
                            val recovery =
                                runCatching {
                                    store.restoreActive(snapshot)
                                    RimeDaemon.restartRimeAndAwaitReady()
                                }
                            current = store.identify()
                            if (recovery.isFailure) {
                                throw IllegalStateException("切换未完成，恢复失败：${recovery.exceptionOrNull()?.message}", error)
                            }
                            throw IllegalStateException("切换未完成，已恢复原模型。请重试。", error)
                        }
                        current = store.identify()
                    }
                }
                message = ""
                Toast.makeText(this@ModelManagerActivity, "已切换", Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                current = withContext(Dispatchers.IO) { runCatching { store.identify() }.getOrNull() }
                message = "操作失败：${error.message}"
            } finally {
                switching = false
                busy = false
                if (!isDestroyed) rerenderPage()
            }
        }
    }
}
