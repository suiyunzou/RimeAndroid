// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.View
import com.osfans.trime.data.prefs.AppPrefs

/** Direction is encoded in timing, including devices without amplitude control. */
object CursorDirectionFeedback {
    @Suppress("DEPRECATION")
    fun perform(view: View, direction: SpatialCursorScrubber.Direction) {
        runCatching {
            val preferences = AppPrefs.defaultInstance().keyboard
            val tuned = FeedbackTuningPrefs(view.context)
            val enabled = if (tuned.configured) tuned.hapticEnabled else preferences.vibrateOnKeyPress.getValue()
            val strength = if (tuned.configured) tuned.profile(false).pressStrength else preferences.vibrationAmplitude.getValue()
            if (!view.isHapticFeedbackEnabled || !enabled || tuned.configured && strength == 0) return
            val audio = view.context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audio?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
            if (Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
            val vibrator = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            val timings = when (direction) {
                SpatialCursorScrubber.Direction.LEFT -> longArrayOf(0, 8)
                SpatialCursorScrubber.Direction.RIGHT -> longArrayOf(0, 14)
                SpatialCursorScrubber.Direction.UP -> longArrayOf(0, 5, 8, 5)
                SpatialCursorScrubber.Direction.DOWN -> longArrayOf(0, 9, 8, 9)
            }
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val configured = strength
                val amplitude = if (configured > 0 && vibrator.hasAmplitudeControl()) configured.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
                val amplitudes = IntArray(timings.size) { if (it % 2 == 0) 0 else amplitude }
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), attributes)
            } else {
                // API 21–25 preserve all four rhythms with the legacy timing overload.
                vibrator.vibrate(timings, -1, attributes)
            }
        }
        // Missing hardware, restricted services, or rejected feedback never block cursor edits.
    }
}
