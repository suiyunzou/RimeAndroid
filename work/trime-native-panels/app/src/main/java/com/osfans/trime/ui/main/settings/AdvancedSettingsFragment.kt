/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.activityViewModels
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.ime.smart.WordLibraryActivity
import com.osfans.trime.ui.main.MainViewModel
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.ui.settingskit.DelegateSettingsPageFragment
import com.osfans.trime.ui.settingskit.SettingsBuilder
import timber.log.Timber

/**
 * 「高级」 page: the delegate-owned interface settings plus the single home for scheme, deploy,
 * backup and dictionary updates.
 */
class AdvancedSettingsFragment : DelegateSettingsPageFragment(AppPrefs.defaultInstance().advanced) {
    private val viewModel: MainViewModel by activityViewModels()

    private val uiMode = AppPrefs.defaultInstance().advanced.uiMode

    private val showAppIcon = AppPrefs.defaultInstance().advanced.showAppIcon

    @Keep
    private val onUiModeChange = PreferenceDelegate.OnChangeListener<AppPrefs.Advanced.UiMode> { _, v ->
        val mode = when (v) {
            AppPrefs.Advanced.UiMode.AUTO -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppPrefs.Advanced.UiMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            AppPrefs.Advanced.UiMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    @Keep
    private val onShowAppIconChange = PreferenceDelegate.OnChangeListener<Boolean> { _, v ->
        showAppIcon(requireContext(), v)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiMode.registerOnChangeListener(onUiModeChange)
        showAppIcon.registerOnChangeListener(onShowAppIconChange)
    }

    override fun onDestroy() {
        uiMode.unregisterOnChangeListener(onUiModeChange)
        showAppIcon.unregisterOnChangeListener(onShowAppIconChange)
        super.onDestroy()
    }

    override fun sections() =
        listOf(
            Section(
                "界面",
                listOf(
                    AppPrefs.Advanced.UI_MODE,
                    AppPrefs.Advanced.SHOW_APP_ICON,
                    AppPrefs.Advanced.IGNORE_SYSTEM_GESTURE_INSETS,
                ),
            ),
        )

    override fun buildExtra(builder: SettingsBuilder) {
        builder.space(12)
        builder.section("方案、部署与备份")
        val card = builder.group()
        builder.row(card, "输入方案", "选择并启用 Rime 输入方案") {
            navigate(NavigationRoute.SchemaList)
        }
        builder.row(card, "部署", "重新部署配置并同步词库") {
            viewModel.deploy()
        }
        builder.row(card, "数据目录与备份", "导入、导出与恢复用户数据") {
            navigate(NavigationRoute.Profile)
        }
        builder.row(card, "词库更新数据", "公开词包、版本与自动更新") {
            runCatching { startActivity(Intent(requireContext(), WordLibraryActivity::class.java)) }
                .onFailure { Timber.w(it, "无法打开词库页") }
        }
        builder.space(8)
        builder.note("部署与备份只影响本机 Rime 数据，不会上传网络。")
    }

    companion object {
        private const val SETTINGS_ACTIVITY_NAME = "com.osfans.trime.MainLauncherAlias"

        fun showAppIcon(context: Context, enable: Boolean) {
            if (enable) {
                com.osfans.trime.ime.smart.SmartIconSwitcher.select(context, "default")
                return
            }
            val state = if (enable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, SETTINGS_ACTIVITY_NAME),
                state,
                PackageManager.DONT_KILL_APP,
            )
            for (name in listOf("com.osfans.trime.BlueLauncherAlias", "com.osfans.trime.GreenLauncherAlias")) {
                context.packageManager.setComponentEnabledSetting(ComponentName(context.packageName, name), state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
