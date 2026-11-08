// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context

class PinyinGesturePrefs(context: Context) {
    private val store = context.getSharedPreferences("smart_input", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = store.getBoolean("pinyin_scrub_enabled", true)
        set(value) { store.edit().putBoolean("pinyin_scrub_enabled", value).apply() }
}
