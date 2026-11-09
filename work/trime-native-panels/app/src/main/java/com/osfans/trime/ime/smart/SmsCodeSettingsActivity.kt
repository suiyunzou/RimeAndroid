// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

class SmsCodeSettingsActivity : Activity() {
    private val prefs by lazy { SmartInputPrefs(this) }
    private var denied = false
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = if (dark) 0xfff2f2f7.toInt() else 0xff202124.toInt()
    private fun dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()
    private fun label(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(ink); setLineSpacing(dp(4).toFloat(), 1f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        denied = savedInstanceState?.getBoolean("denied") ?: false
    }

    override fun onResume() { super.onResume(); render() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putBoolean("denied", denied) }

    private fun render() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(28))
        }
        body.addView(label("‹ 返回", 19f).apply {
            contentDescription = "返回"; setPadding(0, dp(12), 0, dp(20)); setOnClickListener { finish() }
        })
        body.addView(label("短信验证码", 25f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(24)) })
        body.addView(Switch(this).apply {
            text = "自动识别验证码"; textSize = 18f; setTextColor(ink)
            isChecked = SmsCodeInbox.isAvailable(this@SmsCodeSettingsActivity)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(if (dark) 0xff29292c.toInt() else 0xffffffff.toInt()); cornerRadius = dp(18).toFloat() }
            setOnCheckedChangeListener { _, enabled ->
                if (!enabled) {
                    prefs.smsCodeSuggestion = false
                    SmsCodeInbox.clear()
                } else if (ContextCompat.checkSelfPermission(this@SmsCodeSettingsActivity, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
                    prefs.smsCodeSuggestion = true
                    denied = false
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 232)
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(64)))
        body.addView(label("开启后，收到短信验证码时，键盘顶部会显示“填入”按钮。点一下填入当前输入框。", 16f).apply { setPadding(0, dp(22), 0, dp(14)) })
        body.addView(label("需要允许接收短信。只识别新收到的短信，不读取历史短信。验证码仅在本机内存保留 5 分钟；填入、关闭提示或关闭此功能后即清除。短信正文不会保存或上传。", 15f))
        if (denied || (prefs.smsCodeSuggestion && !SmsCodeInbox.isAvailable(this))) {
            body.addView(label("系统未授予短信权限，此功能尚未开启。你可以在应用权限中检查；系统不允许时仍可手动输入。", 15f).apply { setPadding(0, dp(20), 0, dp(10)) })
            body.addView(label("打开应用权限", 17f).apply {
                setTextColor(0xff1677ff.toInt()); setPadding(0, dp(14), 0, dp(14))
                setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            })
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(if (dark) 0xff141416.toInt() else 0xfff2f2f6.toInt())
            addView(body)
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 232) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        prefs.smsCodeSuggestion = granted
        denied = !granted
        if (!granted) SmsCodeInbox.clear()
        render()
    }
}
