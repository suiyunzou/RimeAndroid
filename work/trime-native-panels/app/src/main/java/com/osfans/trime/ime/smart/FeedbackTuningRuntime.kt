// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

/** Bundled recordings and synthesized tones. Playback never needs network access. */
object FeedbackTuningRuntime {
    private var pool: SoundPool? = null
    private val ids = ConcurrentHashMap<String,Int>()
    private val ready = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    @Synchronized fun prepare(context: Context) {
        if (pool != null) return
        runCatching {
            val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            pool = p
            p.setOnLoadCompleteListener { source,id,status -> if (source === pool && status == 0) ready.add(id) }
            for (tone in listOf("crisp","soft","deep")) context.assets.openFd("feedback-tones/$tone.wav").use { ids[tone] = p.load(it,1) }
            for (tone in FeedbackTuningProfile.RECORDINGS.keys) for (kind in listOf("key", "space", "delete", "enter")) {
                for (phase in listOf("down", "up")) {
                    val name = "$tone-$kind-$phase"
                    context.assets.openFd("feedback-tones/$name.wav").use { ids[name] = p.load(it, 1) }
                }
            }
        }.onFailure { destroy() }
    }
    fun sound(context: Context, keyCode: Int, released: Boolean = false, previewTone: String? = null): Boolean {
        val prefs = FeedbackTuningPrefs(context)
        // Disabled feedback is a hot path too. Do not query AudioService for
        // every press/release when nothing can be played.
        if (!prefs.soundEnabled) return false
        val profile = prefs.profile(keyCode == KeyEvent.KEYCODE_DEL)
        if (profile.volume <= 0 || (released && !profile.releaseSound)) return false
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (!FeedbackTuningProfile.shouldPlay(prefs.soundEnabled, audio.ringerMode == AudioManager.RINGER_MODE_NORMAL,
                audio.getStreamVolume(AudioManager.STREAM_SYSTEM), profile.volume, released, profile.releaseSound)) return false
        val tone = previewTone ?: profile.tone
        return runCatching {
            val volume = profile.volume / 100f * if (released) .65f else 1f
            if (tone == "system") {
                val effect = when(keyCode) { KeyEvent.KEYCODE_DEL -> AudioManager.FX_KEYPRESS_DELETE
                    KeyEvent.KEYCODE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR; KeyEvent.KEYCODE_ENTER -> AudioManager.FX_KEYPRESS_RETURN
                    else -> AudioManager.FX_KEYPRESS_STANDARD }
                audio.playSoundEffect(effect, volume); true
            } else {
                prepare(context)
                val recorded = tone in FeedbackTuningProfile.RECORDINGS
                val kind = when (keyCode) { KeyEvent.KEYCODE_DEL -> "delete"; KeyEvent.KEYCODE_SPACE -> "space"; KeyEvent.KEYCODE_ENTER -> "enter"; else -> "key" }
                val id = ids[if (recorded) "$tone-$kind-${if(released)"up" else "down"}" else tone]
                if (id == null || id !in ready) false else (pool?.play(id,volume,volume,0,0,if(released && !recorded)1.12f else 1f) ?: 0) != 0
            }
        }.getOrDefault(false)
    }
    fun haptic(view: View, keyCode: Int, released: Boolean = false) {
        val context = view.context; val prefs = FeedbackTuningPrefs(context)
        if (!prefs.hapticEnabled || !view.isHapticFeedbackEnabled) return
        val p = prefs.profile(keyCode == KeyEvent.KEYCODE_DEL)
        val strength = if (released) p.releaseStrength else p.pressStrength
        val duration = if (released) p.releaseDuration else p.pressDuration
        if (strength <= 0) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (!FeedbackTuningProfile.shouldVibrate(prefs.hapticEnabled,
                Settings.System.getInt(context.contentResolver,Settings.System.HAPTIC_FEEDBACK_ENABLED,1) != 0,
                audio?.ringerMode == AudioManager.RINGER_MODE_SILENT,strength,view.isHapticFeedbackEnabled)) return
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                val amplitude = if(vibrator.hasAmplitudeControl()) strength else VibrationEffect.DEFAULT_AMPLITUDE
                if (p.hapticStyle == "double") vibrator.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, duration.toLong(), 12, duration.toLong()), intArrayOf(0, amplitude, 0, amplitude), -1))
                else if (p.hapticStyle == "soft" && vibrator.hasAmplitudeControl()) vibrator.vibrate(VibrationEffect.createWaveform(
                    longArrayOf(0, maxOf(1, duration / 3).toLong(), maxOf(1, duration / 3).toLong(), maxOf(1, duration / 3).toLong()),
                    intArrayOf(0, maxOf(1,strength/3), strength, maxOf(1,strength/3)), -1))
                else vibrator.vibrate(VibrationEffect.createOneShot(
                    (if(p.hapticStyle == "solid") minOf(duration * 2, 80) else duration).toLong(), amplitude))
            }
            else { @Suppress("DEPRECATION") vibrator.vibrate(duration.toLong()) }
        }
    }
    @Synchronized fun destroy() { pool?.release(); pool = null; ids.clear(); ready.clear() }
}
