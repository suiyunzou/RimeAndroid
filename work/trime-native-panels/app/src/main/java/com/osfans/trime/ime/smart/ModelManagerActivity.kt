// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import android.system.Os
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL

class ModelManagerActivity : Activity() {
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { ModelManagerStore(DataManager.userDataDir) { a, b -> Os.rename(a.absolutePath, b.absolutePath) } }
    private var busy = false
    @Volatile private var switching = false
    private var current: ModelManagerStore.Model? = null
    private var message = "正在读取…"
    private var information = false
    private lateinit var status: TextView
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    override fun onCreate(state: Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar); super.onCreate(state); busy = true; render()
        tasks.launch {
            try { current = withContext(Dispatchers.IO) { store.identify() }; message = "" }
            catch (error: Exception) { message = "无法校验当前模型：${error.message}" }
            finally { busy = false; if (!isDestroyed) render() }
        }
    }
    override fun onDestroy() { tasks.cancel(); super.onDestroy() }
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (switching) return
        if (information) { information = false; render() } else finish()
    }
    private fun text(value: String, size: Float = 16f, color: Int = 0xff202025.toInt()) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }
    @Suppress("DEPRECATION")
    private fun render() {
        val background = 0xfff2f2f6.toInt()
        val muted = 0xff818186.toInt()
        val blue = 0xff1976d2.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        window.statusBarColor = background
        window.navigationBarColor = background
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true; setBackgroundColor(background) }
        val header = FrameLayout(this).apply { setPadding(dp(16), dp(6), dp(16), dp(6)) }
        header.addView(text("‹ 返回", 18f).apply { contentDescription = "返回"; setOnClickListener { onBackPressed() } }, FrameLayout.LayoutParams(dp(76), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(text(if(information)"关于模型" else "整句模型",19f).apply { gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD },FrameLayout.LayoutParams(dp(180),dp(44),Gravity.CENTER))
        if (!information) header.addView(text("ⓘ",23f,muted).apply { gravity=Gravity.CENTER;contentDescription="模型说明";setOnClickListener{information=true;render()} },FrameLayout.LayoutParams(dp(44),dp(44),Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(header,LinearLayout.LayoutParams(-1,dp(58)))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24)) }
        root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        if (information) {
            body.addView(text("精简版省空间；完整版保留更多词语搭配。两者均在本机运行。",16f).apply{setPadding(0,0,0,dp(24))})
            body.addView(text("来源与许可",17f).apply{typeface=android.graphics.Typeface.DEFAULT_BOLD;setPadding(0,0,0,dp(12))})
            body.addView(text("amzxyz 与 Wanxiang / RIME-LMDG 贡献者\nCC BY 4.0\n\nhttps://github.com/amzxyz/rime-wanxiang-data/releases/tag/LTS\nhttps://creativecommons.org/licenses/by/4.0/\n\n精简 v2 为本项目裁剪版本，保留原始权重及四字搭配。",14f,muted).apply{autoLinkMask=android.text.util.Linkify.WEB_URLS;linksClickable=true})
        } else {
            val group=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;clipToOutline=true;this.background=GradientDrawable().apply{setColor(Color.WHITE);cornerRadius=dp(22).toFloat()} }
            val active=ModelManagerStore.active(DataManager.userDataDir)
            if (!busy && current == null && active.isFile) {
                group.addView(text("自定义模型 · ${"%.1f".format(active.length()/1048576.0)} MiB  ✓",16f).apply{setPadding(dp(16),dp(14),dp(16),dp(14))})
            }
            ModelManagerStore.models.forEachIndexed { index, model ->
                if (index>0) group.addView(View(this).apply{setBackgroundColor(0xffededf1.toInt())},LinearLayout.LayoutParams(-1,dp(1)).apply{leftMargin=dp(16)})
                val installed=store.installed(model).isFile || current==model
                val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(13),dp(16),dp(13));isClickable=true;isFocusable=true;alpha=if(busy).5f else 1f}
                val title=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                title.addView(text(model.title,17f))
                title.addView(text("${"%.1f".format(model.bytes/1048576.0)} MiB",13f,muted).apply{setPadding(0,dp(3),0,0)})
                row.addView(title,LinearLayout.LayoutParams(0,-2,1f))
                val action=if(current==model)"✓" else if(installed || model==ModelManagerStore.compact)"使用" else "下载"
                row.addView(text(action,if(current==model)24f else 16f,blue).apply{gravity=Gravity.END},LinearLayout.LayoutParams(dp(50),dp(42)))
                row.contentDescription="${model.title}，${if(current==model)"当前使用" else action}"
                row.setOnClickListener { if(!busy) {
                    if(!installed && model==ModelManagerStore.full) AlertDialog.Builder(this).setTitle("下载完整版？")
                        .setMessage("下载约 399 MiB，需至少 850 MiB 可用空间。")
                        .setNegativeButton("取消",null).setPositiveButton("下载"){_,_->select(model)}.show()
                    else select(model)
                } }
                group.addView(row,LinearLayout.LayoutParams(-1,-2))
            }
            body.addView(group)
            status=text(message,14f,muted).apply{setPadding(dp(14),dp(14),dp(14),0);accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE;visibility=if(message.isEmpty())View.GONE else View.VISIBLE}
            body.addView(status)
        }
        setContentView(root)
    }
    private fun select(model: ModelManagerStore.Model) {
        busy = true; message = "准备模型…"; render()
        tasks.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    if (!store.installed(model).isFile) {
                        var last = 0L
                        val progress: (Long) -> Unit = { n ->
                            context.ensureActive()
                            val now = System.currentTimeMillis()
                            if (now - last > 250) { last = now; runOnUiThread { if (!isDestroyed) status.text = "正在安装：${n * 100 / model.bytes}%" } }
                        }
                        if (current == model) store.install(model, ModelManagerStore.active(DataManager.userDataDir).inputStream(), progress)
                        else if (model == ModelManagerStore.compact) store.install(model, assets.open("local-models/wanxiang-compact-v2.gram"), progress)
                        else {
                            val connection = URL("https://github.com/amzxyz/rime-wanxiang-data/releases/download/LTS/wanxiang-lts-zh-hans.gram").openConnection() as HttpURLConnection
                            connection.connectTimeout = 20000; connection.readTimeout = 30000
                            try { require(connection.responseCode == 200) { "下载失败 HTTP ${connection.responseCode}" }; store.install(model, connection.inputStream, progress) }
                            finally { connection.disconnect() }
                        }
                    }
                    ensureActive()
                    require(store.installed(model).length() == model.bytes && ModelManagerStore.hash(store.installed(model)) == model.sha) { "模型校验失败，请检查安装文件；原模型与方案未改动" }
                    switching = true
                    runOnUiThread { if (!isDestroyed) status.text = "正在切换…" }
                    // Once replacement begins, finish or restore it even if Android destroys this Activity.
                    withContext(NonCancellable) {
                        val snapshot = store.snapshotActive()
                        try {
                            ModelSchemaIntegration.ensureConfigured()
                            store.select(model)
                            RimeDaemon.restartRimeAndAwaitReady()
                        } catch (error: Exception) {
                            val recovery = runCatching {
                                store.restoreActive(snapshot)
                                RimeDaemon.restartRimeAndAwaitReady()
                            }
                            current = store.identify()
                            if (recovery.isFailure) throw IllegalStateException("切换未完成，恢复失败：${recovery.exceptionOrNull()?.message}", error)
                            throw IllegalStateException("切换未完成，已恢复原模型。请重试。", error)
                        }
                        current = store.identify()
                    }
                }
                message = ""
                Toast.makeText(this@ModelManagerActivity,"已切换",Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                current = withContext(Dispatchers.IO) { runCatching { store.identify() }.getOrNull() }
                message = "操作失败：${error.message}"
            }
            finally { switching = false; busy = false; if (!isDestroyed) render() }
        }
    }
}
