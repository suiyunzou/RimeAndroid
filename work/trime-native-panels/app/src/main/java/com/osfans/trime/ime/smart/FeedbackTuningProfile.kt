// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** Independent settings for ordinary keys and Backspace. Zero strength means no vibration. */
data class FeedbackTuningProfile(
    val tone: String = "system", val volume: Int = 65,
    val pressStrength: Int = 128, val pressDuration: Int = 8,
    val releaseStrength: Int = 0, val releaseDuration: Int = 6,
    val releaseSound: Boolean = false,
    val hapticStyle: String = "crisp",
) {
    fun bounded() = copy(tone = tone.takeIf { it in TONES } ?: "system", volume = volume.coerceIn(0,100),
        pressStrength = pressStrength.coerceIn(0,255), pressDuration = pressDuration.coerceIn(1,40),
        releaseStrength = releaseStrength.coerceIn(0,255), releaseDuration = releaseDuration.coerceIn(1,40),
        hapticStyle = hapticStyle.takeIf { it in HAPTICS } ?: "crisp")
    companion object {
        val RECORDINGS = linkedMapOf("mx_blue" to "Cherry MX 青轴", "mx_brown" to "Cherry MX 茶轴",
            "mx_red" to "Cherry MX 红轴", "mx_black" to "Cherry MX 黑轴", "topre" to "Topre 静电容键盘",
            "oreo" to "Everglide 奥利奥轴", "crystal" to "Everglide 水晶紫轴")
        val TONES = linkedMapOf("system" to "系统").apply {
            putAll(RECORDINGS)
            putAll(mapOf("crisp" to "合成·清脆", "soft" to "合成·柔和", "deep" to "合成·低沉"))
        }
        val HAPTICS = linkedMapOf("crisp" to "清脆", "soft" to "柔和", "double" to "双击", "solid" to "厚实")
        fun shouldPlay(enabled: Boolean, normalRinger: Boolean, systemVolume: Int, volume: Int, release: Boolean, releaseSound: Boolean) =
            enabled && normalRinger && systemVolume > 0 && volume > 0 && (!release || releaseSound)
        fun shouldVibrate(enabled: Boolean, systemEnabled: Boolean, silent: Boolean, strength: Int, viewEnabled: Boolean) =
            enabled && systemEnabled && !silent && strength > 0 && viewEnabled
    }
}
