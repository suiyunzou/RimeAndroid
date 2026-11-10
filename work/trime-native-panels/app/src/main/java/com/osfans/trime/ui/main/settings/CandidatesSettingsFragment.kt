/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.settingskit.DelegateSettingsPageFragment

class CandidatesSettingsFragment : DelegateSettingsPageFragment(AppPrefs.defaultInstance().candidates) {
    override fun sections() =
        listOf(
            Section("候选窗口", listOf(AppPrefs.Candidates.MODE)),
            Section(
                "布局与位置",
                listOf(AppPrefs.Candidates.LAYOUT, AppPrefs.Candidates.POSITION),
            ),
        )
}
