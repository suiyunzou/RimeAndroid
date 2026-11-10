// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsKitActivity

class SmsCodeSettingsActivity : SettingsKitActivity() {
    private val prefs by lazy { SmartInputPrefs(this) }
    private var denied = false
    private var firstResume = true

    override fun onCreate(state: Bundle?) {
        denied = state?.getBoolean("denied") ?: false
        super.onCreate(state)
    }

    /** 授权结果可能在设置页外改变，回到页面时重建开关状态。 */
    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
        } else {
            rerender()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("denied", denied)
    }

    override fun pageTitle(): String = "短信验证码"

    override fun buildPage(builder: SettingsBuilder) {
        builder.space(7)
        val switches = builder.group()
        builder.toggle(switches, "自动识别验证码", SmsCodeInbox.isAvailable(this)) { enabled -> onToggle(enabled) }
        builder.space(8)
        builder.note("开启后，收到短信验证码时，键盘顶部会显示“填入”按钮。点一下填入当前输入框。")
        builder.space(12)
        val details = builder.group()
        details.addView(
            bodyText(
                "需要允许接收短信。只识别新收到的短信，不读取历史短信。验证码仅在本机内存保留 5 分钟；填入、关闭提示或关闭此功能后即清除。短信正文不会保存或上传。",
                palette.ink,
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (denied || (prefs.smsCodeSuggestion && !SmsCodeInbox.isAvailable(this))) {
            builder.space(12)
            builder.section("权限")
            val permission = builder.group()
            permission.addView(
                bodyText(
                    "系统未授予短信权限，此功能尚未开启。你可以在应用权限中检查；系统不允许时仍可手动输入。",
                    palette.muted,
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            builder.row(permission, "打开应用权限") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
    }

    /** 开关变化沿用旧逻辑：关闭时清空内存验证码，开启时确认短信权限。 */
    private fun onToggle(enabled: Boolean) {
        if (!enabled) {
            prefs.smsCodeSuggestion = false
            SmsCodeInbox.clear()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) {
            prefs.smsCodeSuggestion = true
            denied = false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), 232)
        }
    }

    private fun bodyText(
        value: String,
        color: Int,
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = 15f
            setTextColor(color)
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 232) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        prefs.smsCodeSuggestion = granted
        denied = !granted
        if (!granted) SmsCodeInbox.clear()
        rerender()
    }
}
