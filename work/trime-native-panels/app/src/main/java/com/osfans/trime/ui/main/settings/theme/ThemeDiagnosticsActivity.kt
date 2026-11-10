/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import android.content.ClipData
import android.content.Intent
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.R
import com.osfans.trime.data.theme.ThemeDiagnostics
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsKitActivity
import com.osfans.trime.util.DeviceInfo
import com.osfans.trime.util.toast
import splitties.systemservices.clipboardManager

/**
 * Shows what the static checks of [ThemeDiagnostics] found in the theme in
 * use, and copies or shares them as a report.
 */
class ThemeDiagnosticsActivity : SettingsKitActivity() {
    override fun pageTitle(): String = getString(R.string.theme_diagnostics)

    override fun buildPage(builder: SettingsBuilder) {
        val theme = ThemeManager.activeTheme
        val findings = ThemeManager.activeFindings
        val themeId = ThemeManager.prefs.selectedTheme.getValue()
        val report = DeviceInfo.get(this) + ThemeDiagnostics.format(themeId, theme.name, findings)

        builder.space(7)
        val summary = builder.group()
        builder.row(summary, "主题", value = theme.name)
        builder.row(summary, "配置标识", value = themeId)
        builder.row(
            summary,
            "检查结果",
            value = when {
                findings == null -> "未运行"
                findings.isEmpty() -> "没有问题"
                else -> "${findings.size} 项"
            },
        )
        builder.space(12)
        builder.section("发现")
        val card = builder.group()
        if (findings.isNullOrEmpty()) {
            builder.row(
                card,
                getString(
                    if (findings == null) {
                        R.string.theme_diagnostics_unchecked
                    } else {
                        R.string.theme_diagnostics_no_findings
                    },
                ),
            )
        } else {
            findings.forEach { finding -> findingRow(card, finding) }
        }
        builder.space(12)
        builder.button("复制报告") {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("theme-diagnostics", report))
            if (clipboardManager.hasPrimaryClip()) {
                toast(R.string.copy_done)
            }
        }
        builder.button("分享报告") {
            val target =
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.theme_diagnostics))
                    putExtra(Intent.EXTRA_TEXT, report)
                }
            startActivity(Intent.createChooser(target, getString(R.string.share)))
        }
    }

    /** 一条诊断：消息在上，主题内路径以等宽小字在下；警告用正文色，提示淡一些。 */
    private fun findingRow(
        parent: LinearLayout,
        finding: ThemeDiagnostics.Finding,
    ) {
        val warning = finding.severity == ThemeDiagnostics.Severity.WARNING
        val title = if (warning) "⚠  ${finding.message}" else finding.message
        val row = builder.row(parent, title, summary = finding.path)
        val texts = row.getChildAt(0) as? LinearLayout ?: return
        val message = texts.getChildAt(0) as? TextView ?: return
        message.setTextColor(if (warning) palette.ink else palette.muted)
        (texts.getChildAt(1) as? TextView)?.typeface = Typeface.MONOSPACE
    }
}
