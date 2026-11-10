/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.activityViewModels
import com.osfans.trime.R
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsPageFragment
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.Logcat

/**
 * 「开发者」页面：实时日志与清空日志。
 *
 * 行为与旧页面一致：第一行打开 LogActivity，第二行弹确认框，确认后清空 logcat。
 * 页面顶栏由 MainActivity 提供。
 */
class DeveloperFragment : SettingsPageFragment() {
    private val viewModel: MainViewModel by activityViewModels()

    override fun buildPage(builder: SettingsBuilder) {
        val ctx = requireContext()
        builder.space(12)
        builder.section("日志")
        builder.group().let { card ->
            builder.row(card, ctx.getString(R.string.real_time_logs)) {
                AppUtils.launchLogActivity(ctx)
            }
            builder.row(card, ctx.getString(R.string.real_time_logs_clear)) {
                AlertDialog
                    .Builder(ctx)
                    .setMessage(R.string.real_time_logs_confirm)
                    .setPositiveButton(R.string.ok) { _, _ ->
                        Logcat.default.clearLog()
                    }.setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        builder.space(8)
        builder.note("日志只写入本机 logcat，用于排查输入法问题；清空后不可撤销。")
    }

    override fun onResume() {
        super.onResume()
        // 与旧页面一致：开发者页不显示顶栏的面板操作菜单。
        viewModel.disableTopOptionsMenu()
    }
}
