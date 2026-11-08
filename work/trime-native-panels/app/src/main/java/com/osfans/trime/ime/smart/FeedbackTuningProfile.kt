// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

/** Independent settings for ordinary keys and Backspace. Zero strength means no vibration. */
data class FeedbackTuningProfile(
    val tone: String = "system", val volume: Int = 65,
    val pressStrength: Int = 128, val pressDuration: Int = 8,
    val releaseStrength: Int = 0, val releaseDuration: Int = 6,
    val releaseSound: Boolean = false,
) {
    fun bounded() = copy(tone = tone.takeIf { it in TONES } ?: "system", volume = volume.coerceIn(0,100),
        pressStrength = pressStrength.coerceIn(0,255), pressDuration = pressDuration.coerceIn(1,40),
        releaseStrength = releaseStrength.coerceIn(0,255), releaseDuration = releaseDuration.coerceIn(1,40))
    companion object {
        val TONES = linkedMapOf("system" to "系统", "crisp" to "机械·清脆", "soft" to "机械·柔和", "deep" to "机械·低沉")
        fun shouldPlay(enabled: Boolean, normalRinger: Boolean, systemVolume: Int, volume: Int, release: Boolean, releaseSound: Boolean) =
            enabled && normalRinger && systemVolume > 0 && volume > 0 && (!release || releaseSound)
        fun shouldVibrate(enabled: Boolean, systemEnabled: Boolean, silent: Boolean, strength: Int, viewEnabled: Boolean) =
            enabled && systemEnabled && !silent && strength > 0 && viewEnabled
    }
}
