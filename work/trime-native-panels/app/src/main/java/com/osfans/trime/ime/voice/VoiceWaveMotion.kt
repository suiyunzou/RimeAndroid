// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10

/** Visual envelope only: recognition and punctuation use the original PCM. */
internal class VoiceWaveMotion {
    var level = 0f
        private set
    var seconds = 0f
        private set
    private var target = 0f
    private var sampleAt = 0L
    private var frameAt: Long? = null

    fun reset() {
        level = 0f
        seconds = 0f
        target = 0f
        sampleAt = 0L
        frameAt = null
    }

    fun sample(rms: Float, now: Long) {
        target = if (rms.isFinite() && rms > 0f) {
            ((20f * log10(rms) + 49f) / 31f).coerceIn(0f, 1f)
        } else {
            0f
        }
        sampleAt = now
        if (frameAt == null) frameAt = now
    }

    fun stop() {
        target = 0f
    }

    /** Return whether another frame is needed; silence has no animation loop. */
    fun advance(now: Long, animate: Boolean, listening: Boolean): Boolean {
        val dt = (now - (frameAt ?: now)).coerceIn(0L, 64L)
        if (!listening || now - sampleAt >= SAMPLE_TIMEOUT_MS) target = 0f
        val responseMs = if (target > level) 38f else 84f
        level = if (animate) level + (target - level) * (1f - exp(-dt.toFloat() / responseMs)) else target
        if (abs(target - level) <= 0.008f) level = target
        if (animate && listening && level > 0f) {
            seconds = (seconds + dt / 1000f) % PHASE_PERIOD_S
        }
        val needsFrame = level > 0f || target > 0f
        frameAt = if (needsFrame) now else null
        // With animation disabled, update on samples and once more at their expiry.
        return animate && needsFrame
    }

    companion object {
        const val SAMPLE_TIMEOUT_MS = 160L
        const val PHASE_PERIOD_S = 8f
    }
}
