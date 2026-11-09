// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Build-specific facts, not a privacy claim about another input method. */
class OfflinePrivacyActivity : Activity() {
    private fun dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()
    private fun shape(color: Int, radius: Int = 22) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(value: String, size: Float = 17f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(0xff202124.toInt()); setLineSpacing(dp(5).toFloat(), 1f)
    }
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xfff2f2f6.toInt(); window.navigationBarColor = 0xfff2f2f6.toInt()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xfff2f2f6.toInt()); fitsSystemWindows = true }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        header.addView(label("‹ 返回", 19f).apply { gravity = Gravity.CENTER; background = shape(0xffe4e4e9.toInt()); contentDescription = "返回"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(82), dp(40)))
        header.addView(label("离线与隐私", 19f).apply { gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(View(this), LinearLayout.LayoutParams(dp(82), 1)); root.addView(header)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(28)) }
        fun card(title: String, description: String) {
            val group = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE); setPadding(dp(18), dp(16), dp(18), dp(17)) }
            group.addView(label(title, 19f).apply { typeface = Typeface.DEFAULT_BOLD })
            group.addView(label(description, 15f).apply { setTextColor(0xff63666c.toInt()); setPadding(0, dp(9), 0, 0) })
            body.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        }
        val network = packageManager.checkPermission("android.permission.INTERNET", packageName) == PackageManager.PERMISSION_GRANTED
        card("本机输入", if (network) "拼音、词库、整句模型、滑行与文本工具在本机运行。网络权限用于你主动选择的公开词库或模型下载；下载请求不携带输入内容、个人词库或剪贴板。" else "此安装包没有网络权限。拼音、词库、整句模型、滑行与文本工具都在本机运行。没有内置广告或使用统计上传。")
        card("公开资源下载", "仅在你点击下载后通过 HTTPS 连接 GitHub。下载服务可看到网络连接信息（例如 IP 地址）；不会收到你的输入内容。网络失败时保留已有词库和模型；下载后可断网使用。")
        card("本地保存", "设置、导入词条、学习词库和启用后的剪贴板历史保存在本机。此版本关闭 Android 自动云备份和设备迁移；手动导出文件由你管理。")
        card("短信验证码", "此功能默认关闭。开启并授权后，只从新收到的短信中识别验证码，不读取短信历史。验证码在内存保留最多 5 分钟，填入或关闭后清除；短信正文不会保存、上传或进入剪贴板。点“填入”才写入当前输入框。")
        card("敏感输入", "密码输入框和应用标记为“不用于个性化学习”的输入框会关闭整句预测与学习。普通输入恢复原有本地词库功能。")
        card("文件与外部服务", "导入只读取你在系统文件选择器中选择的文件。麦克风按钮会切换到系统安装的语音输入法；该服务的识别位置和隐私规则由其提供方决定。此应用不提供云端语音识别。")
        card("这份构建", "这是一份本地参考实现，不是 ReRime 官方应用。公开模型、词库和公版经文的来源与许可随资源提供；没有订阅或付费入口。")
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
}
