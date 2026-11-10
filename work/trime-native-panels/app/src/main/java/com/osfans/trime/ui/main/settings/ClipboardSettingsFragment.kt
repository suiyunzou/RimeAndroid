/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.settingskit.DelegateSettingsPageFragment

class ClipboardSettingsFragment : DelegateSettingsPageFragment(AppPrefs.defaultInstance().clipboard) {
    override fun sections() =
        listOf(
            Section(
                "剪贴板",
                listOf(
                    AppPrefs.Clipboard.CLIPBOARD_LISTENING,
                    AppPrefs.Clipboard.CLIPBOARD_LIMIT,
                    AppPrefs.Clipboard.CLIPBOARD_SUGGESTION,
                    AppPrefs.Clipboard.CLIPBOARD_SUGGESTION_TIMEOUT,
                    AppPrefs.Clipboard.CLIPBOARD_RETURN_AFTER_PASTE,
                ),
            ),
            Section(
                "规则",
                listOf(
                    AppPrefs.Clipboard.CLIPBOARD_COMPARE_RULES,
                    AppPrefs.Clipboard.CLIPBOARD_OUTPUT_RULES,
                ),
            ),
        )
}
