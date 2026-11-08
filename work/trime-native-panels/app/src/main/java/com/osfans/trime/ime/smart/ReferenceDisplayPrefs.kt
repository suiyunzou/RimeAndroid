package com.osfans.trime.ime.smart

import android.content.Context

class ReferenceDisplayPrefs(context: Context) {
    private val store = context.getSharedPreferences("smart_input", Context.MODE_PRIVATE)
    var keyBold: Boolean
        get() = store.getBoolean("reference_key_bold", false)
        set(value) { store.edit().putBoolean("reference_key_bold", value).apply() }
    var keyTextSize: Int
        get() = store.getInt("reference_key_text_size", 24).let { if (it in listOf(20, 24, 28)) it else 24 }
        set(value) { store.edit().putInt("reference_key_text_size", value.coerceIn(20, 28)).apply() }
}
