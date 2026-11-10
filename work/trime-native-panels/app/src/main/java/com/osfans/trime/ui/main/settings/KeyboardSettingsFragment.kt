/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.lifecycle.lifecycleScope
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ui.settingskit.DelegateSettingsPageFragment
import kotlinx.coroutines.launch

class KeyboardSettingsFragment : DelegateSettingsPageFragment(AppPrefs.defaultInstance().keyboard) {
    override fun sections() =
        listOf(
            Section(
                "布局与显示",
                listOf(
                    AppPrefs.Keyboard.LANDSCAPE_MODE,
                    AppPrefs.Keyboard.SPLIT_SPACE_PERCENT,
                    AppPrefs.Keyboard.USE_SOFT_CURSOR,
                    AppPrefs.Keyboard.HIDE_INPUT_BAR,
                    AppPrefs.Keyboard.HIDE_KEY_SYMBOL,
                    AppPrefs.Keyboard.HIDE_KEY_HINT,
                    AppPrefs.Keyboard.EXPAND_KEYPRESS_AREA,
                    AppPrefs.Keyboard.HORIZONTAL_CANDIDATE_MODE,
                    AppPrefs.Keyboard.MAX_SPAN_COUNT,
                    AppPrefs.Keyboard.MAX_SPAN_COUNT_LANDSCAPE,
                ),
            ),
            Section(
                "按键声音",
                listOf(
                    AppPrefs.Keyboard.SOUND_ON_KEYPRESS,
                    AppPrefs.Keyboard.KEY_SOUND_VOLUME,
                    AppPrefs.Keyboard.USE_CUSTOM_SOUND_EFFECT,
                    AppPrefs.Keyboard.CUSTOM_SOUND_EFFECT,
                ),
            ),
            Section(
                "按键振动",
                listOf(
                    AppPrefs.Keyboard.VIBRATE_ON_KEY_PRESS,
                    AppPrefs.Keyboard.VIBRATE_ON_KEY_RELEASE,
                    AppPrefs.Keyboard.VIBRATE_ON_KEY_REPEAT,
                    AppPrefs.Keyboard.VIBRATION_DURATION,
                    AppPrefs.Keyboard.VIBRATION_AMPLITUDE,
                ),
            ),
            Section(
                "按键行为",
                listOf(
                    AppPrefs.Keyboard.SPEAK_ON_KEYPRESS,
                    AppPrefs.Keyboard.SPEAK_ON_COMMIT,
                    AppPrefs.Keyboard.POPUP_ON_KEY_PRESS,
                    AppPrefs.Keyboard.SWIPE_TRAVEL,
                    AppPrefs.Keyboard.SWIPE_VELOCITY,
                    AppPrefs.Keyboard.LONG_PRESS_TIMEOUT,
                    AppPrefs.Keyboard.REPEAT_INTERVAL,
                    AppPrefs.Keyboard.DOUBLE_TAP_TIMEOUT,
                    AppPrefs.Keyboard.SLIDE_STEP_SIZE,
                ),
            ),
            Section(
                "快捷操作",
                listOf(
                    AppPrefs.Keyboard.HOOK_CTRL_A,
                    AppPrefs.Keyboard.HOOK_CTRL_CV,
                    AppPrefs.Keyboard.HOOK_CTRL_LR,
                    AppPrefs.Keyboard.HOOK_CTRL_ZY,
                    AppPrefs.Keyboard.HOOK_SHIFT_SPACE,
                    AppPrefs.Keyboard.HOOK_SHIFT_NUM,
                    AppPrefs.Keyboard.HOOK_SHIFT_SYMBOL,
                    AppPrefs.Keyboard.HOOK_SHIFT_ARROW,
                ),
            ),
        )

    override fun customClick(key: String): (() -> Unit)? =
        if (key == AppPrefs.Keyboard.CUSTOM_SOUND_EFFECT) {
            {
                lifecycleScope.launch {
                    SoundEffectPickerDialog.build(lifecycleScope, requireContext()).show()
                }
            }
        } else {
            null
        }
}
