/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.settingskit.DelegateSettingsPageFragment

class GeneralSettingsFragment : DelegateSettingsPageFragment(AppPrefs.defaultInstance().general) {
    override fun sections() =
        listOf(
            Section(
                "输入",
                listOf(
                    AppPrefs.General.INLINE_PREEDIT_MODE,
                    AppPrefs.General.INLINE_SUGGESTIONS,
                ),
            ),
            Section(
                "提示与语音",
                listOf(
                    AppPrefs.General.ASCII_SWITCH_TIPS,
                    AppPrefs.General.PREFERRED_VOICE_INPUT,
                ),
            ),
        )
}
