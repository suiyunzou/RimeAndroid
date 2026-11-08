// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context
import com.osfans.trime.data.prefs.AppPrefs

class FeedbackTuningPrefs(context: Context) {
    private val store = context.getSharedPreferences("feedback_tuning", Context.MODE_PRIVATE)
    private val legacy get() = AppPrefs.defaultInstance().keyboard
    val configured get() = store.getBoolean("configured", false)
    var soundEnabled: Boolean
        get() = store.getBoolean("sound_enabled", legacy.soundOnKeyPress.getValue())
        set(value) { store.edit().putBoolean("configured", true).putBoolean("sound_enabled", value).apply() }
    var hapticEnabled: Boolean
        get() = store.getBoolean("haptic_enabled", legacy.vibrateOnKeyPress.getValue())
        set(value) { store.edit().putBoolean("configured", true).putBoolean("haptic_enabled", value).apply() }
    var favorites: Set<String>
        get() = store.getStringSet("favorites", emptySet()).orEmpty().filter { it in FeedbackTuningProfile.TONES }.toSet()
        set(value) { store.edit().putStringSet("favorites", value.filter { it in FeedbackTuningProfile.TONES }.toSet()).apply() }
    fun profile(backspace: Boolean): FeedbackTuningProfile {
        val k = if (backspace) "delete_" else "normal_"
        return FeedbackTuningProfile(store.getString(k+"tone","system") ?: "system", store.getInt(k+"volume",65),
            store.getInt(k+"press_strength",128), store.getInt(k+"press_duration",8),
            store.getInt(k+"release_strength",0), store.getInt(k+"release_duration",6), store.getBoolean(k+"release_sound",false)).bounded()
    }
    fun save(backspace: Boolean, value: FeedbackTuningProfile) {
        val k = if (backspace) "delete_" else "normal_"; val p = value.bounded()
        store.edit().putBoolean("configured",true).putString(k+"tone",p.tone).putInt(k+"volume",p.volume)
            .putInt(k+"press_strength",p.pressStrength).putInt(k+"press_duration",p.pressDuration)
            .putInt(k+"release_strength",p.releaseStrength).putInt(k+"release_duration",p.releaseDuration)
            .putBoolean(k+"release_sound",p.releaseSound).apply()
    }
}
