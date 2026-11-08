// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context

class CursorGesturePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("smart_input", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("letter_cursor_enabled", true)
        set(value) { prefs.edit().putBoolean("letter_cursor_enabled", value).apply() }
}
