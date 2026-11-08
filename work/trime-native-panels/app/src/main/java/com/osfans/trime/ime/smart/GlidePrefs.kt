// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context

class GlidePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("smart_input", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("glide_enabled", false)
        set(value) { prefs.edit().putBoolean("glide_enabled", value).apply() }
}
