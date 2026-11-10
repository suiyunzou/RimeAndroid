/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.theme

import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePrefs
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.ui.settingskit.DelegateSettingsPageFragment
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.util.startActivity
import kotlinx.coroutines.launch

class ThemeSettingsFragment : DelegateSettingsPageFragment(ThemeManager.prefs) {
    override fun sections() =
        listOf(
            Section(
                "主题",
                listOf(
                    ThemePrefs.SELECTED_THEME,
                    ThemePrefs.NORMAL_MODE_COLOR,
                    ThemePrefs.FOLLOW_SYSTEM_DAY_NIGHT,
                    ThemePrefs.NAVBAR_BACKGROUND,
                ),
            ),
        )

    override fun customClick(key: String): (() -> Unit)? =
        when (key) {
            ThemePrefs.SELECTED_THEME -> {
                {
                    lifecycleScope.launch {
                        ThemePickerDialog.build(lifecycleScope, requireContext()).show()
                    }
                }
            }

            ThemePrefs.NORMAL_MODE_COLOR -> {
                {
                    lifecycleScope.launch {
                        ColorPickerDialog.build(lifecycleScope, requireContext()).show()
                    }
                }
            }

            else -> null
        }

    override fun buildExtra(builder: SettingsBuilder) {
        builder.space(12)
        val card = builder.group()
        builder.row(card, "主题诊断", "检查主题与配色是否有缺失") {
            startActivity<ThemeDiagnosticsActivity>()
        }
    }

    override fun onStop() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        super.onStop()
    }
}
