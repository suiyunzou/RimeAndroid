package com.osfans.trime.ime.smart

import android.content.Context

/** Small, app-private preferences shared by the live keyboard and its settings panel. */
class SmartInputPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("smart_input", Context.MODE_PRIVATE)
    var wheelSlots: List<String>
        get() = prefs.getString("wheel_slots", null)?.split(',')?.filter { it in ACTIONS }?.distinct()?.take(6)
            ?: RadialMenuGeometry.DEFAULT_SLOTS
        set(value) { prefs.edit().putString("wheel_slots", value.filter { it in ACTIONS }.distinct().take(6).joinToString(",")).apply() }
    var wheelStyle: String
        get() = prefs.getString("wheel_style", "rainbow") ?: "rainbow"
        set(value) { prefs.edit().putString("wheel_style", value).apply() }
    var originGlow: Boolean
        get() = prefs.getBoolean("origin_glow", true)
        set(value) { prefs.edit().putBoolean("origin_glow", value).apply() }
    var followFinger: Boolean
        get() = prefs.getBoolean("follow_finger", true)
        set(value) { prefs.edit().putBoolean("follow_finger", value).apply() }
    var wheelIconVisibility: String
        get() = prefs.getString("wheel_icon_visibility", "all") ?: "all"
        set(value) { prefs.edit().putString("wheel_icon_visibility", value).apply() }
    var wheelFocusStyle: String
        get() = prefs.getString("wheel_focus_style", "lens") ?: "lens"
        set(value) { prefs.edit().putString("wheel_focus_style", value).apply() }
    var wheelCursorPlacement: String
        get() = prefs.getString("wheel_cursor_placement", "finger") ?: "finger"
        set(value) { prefs.edit().putString("wheel_cursor_placement", value).apply() }
    var wheelMotionStyle: String
        get() = prefs.getString("wheel_motion_style", "snappy") ?: "snappy"
        set(value) { prefs.edit().putString("wheel_motion_style", value).apply() }
    var wheelOriginPlacement: String
        get() = prefs.getString("wheel_origin_placement", "finger") ?: "finger"
        set(value) { prefs.edit().putString("wheel_origin_placement", if (value == "space_center") value else "finger").apply() }
    var uppercaseLabels: Boolean
        get() = prefs.getBoolean("uppercase_labels", false)
        set(value) { prefs.edit().putBoolean("uppercase_labels", value).apply() }
    var candidateBold: Boolean
        get() = prefs.getBoolean("candidate_bold", false)
        set(value) { prefs.edit().putBoolean("candidate_bold", value).apply() }
    var candidateSize: Int
        get() = prefs.getInt("candidate_size", 0)
        set(value) { prefs.edit().putInt("candidate_size", value.coerceIn(0, 40)).apply() }
    var candidateEmoji: Boolean
        get() = prefs.getBoolean("candidate_emoji", true)
        set(value) { prefs.edit().putBoolean("candidate_emoji", value).apply() }
    var layout: String
        get() = prefs.getString("layout", "qwerty") ?: "qwerty"
        set(value) { prefs.edit().putString("layout", value).apply() }
    var correction: Boolean
        get() = prefs.getBoolean("correction", true)
        set(value) { prefs.edit().putBoolean("correction", value).apply() }
    var flickDirection: FlickDirection
        get() = FlickDirection.fromId(prefs.getString("flick_direction", "down"))
        set(value) { prefs.edit().putString("flick_direction", value.id).apply() }
    fun allowsFlick(up: Boolean): Boolean = flickDirection.allows(up)
    var referenceKeyboardHeight: Int
        get() = prefs.getInt("reference_keyboard_height", 212).coerceIn(144, 320)
        set(value) { prefs.edit().putInt("reference_keyboard_height", value.coerceIn(144, 320)).apply() }
    fun binding(key: String, up: Boolean): String? = prefs.getString("key_${key.lowercase()}_${if (up) "up" else "down"}", null)
    fun setBinding(key: String, up: Boolean, value: String?) {
        val name = "key_${key.lowercase()}_${if (up) "up" else "down"}"
        prefs.edit().apply { if (value == null) remove(name) else putString(name, value.take(256)) }.apply()
    }
    fun resetBindings(up: Boolean) {
        val suffix = if (up) "_up" else "_down"
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("key_") && it.endsWith(suffix) }.forEach { remove(it) }
        }.apply()
    }
    companion object {
        val ACTIONS = listOf("settings", "time", "traditional", "symbols", "calculator", "clipboard", "quotes")
        val LABELS = mapOf("settings" to "设置", "time" to "时间", "traditional" to "简繁", "symbols" to "符号", "calculator" to "计算", "clipboard" to "剪贴板", "quotes" to "引用经文")
    }
}
