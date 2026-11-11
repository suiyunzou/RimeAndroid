// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService

/**
 * Connects the microphone key and [VoicePanelView] to [OfflineVoiceManager].
 *
 * Owned by the keyboard view, so every state change happens on the main thread:
 * the manager posts its callbacks there.
 */
class VoiceUiController(
    private val service: TrimeInputMethodService,
    private val panel: VoicePanelView,
) : OfflineVoiceManager.Listener {
    private val handler = Handler(Looper.getMainLooper())

    init {
        panel.onMicClick = {
            timber.log.Timber.i("voice: mic click")
            OfflineVoiceManager.stop()
        }
        panel.onCancel = {
            timber.log.Timber.i("voice: cancel click")
            OfflineVoiceManager.cancel()
            hidePanel()
        }
        panel.setPhase(VoicePanelView.Phase.HIDDEN)
        OfflineVoiceManager.setListener(this)
    }

    fun applyTheme(scope: ThemeScope) {
        val colors = scope.colors
        val dark = ColorUtils.calculateLuminance(colors.keyboardBackColor) < 0.3f
        // The panel is a sheet above the keys: a touch lighter than the keyboard
        // back colour reads as a separate layer, like the reference recording UI.
        val background =
            if (dark) {
                colors.keyboardBackColor
            } else {
                ColorUtils.blendARGB(colors.keyboardBackColor, Color.WHITE, 0.55f)
            }
        val accent = vividAccent(
            runCatching { colors.hilitedCandidateTextColor }.getOrNull(),
            dark,
        )
        panel.applyStyle(
            VoicePanelView.Style(
                dark = dark,
                background = background,
                card = if (dark) colors.keyBackColor else Color.WHITE,
                text = colors.keyTextColor,
                muted = colors.keySymbolColor,
                accent = accent,
            ),
        )
    }

    /**
     * Theme highlights are usually dark enough for text; a waveform needs a
     * more saturated, brighter version of the same hue to read on the sheet.
     * The iOS reference theme has no accent colour at all (its highlight is
     * near-black), so an achromatic source falls back to the iOS system blue.
     */
    private fun vividAccent(source: Int?, dark: Boolean): Int {
        val fallback = if (dark) 0xFF64D2FF.toInt() else 0xFF0A84FF.toInt()
        if (source == null) return fallback
        val hsv = FloatArray(3)
        Color.colorToHSV(source, hsv)
        if (hsv[1] < 0.12f || hsv[2] < 0.05f) return fallback
        hsv[1] = hsv[1].coerceAtLeast(0.6f)
        hsv[2] = if (dark) 0.9f else 0.74f
        return Color.HSVToColor(hsv)
    }

    /** Microphone key tap: start listening, or finish the running session. */
    fun toggle() {
        if (VoiceModelStore.status(service) != VoiceModelStore.Status.READY) {
            timber.log.Timber.i("voice: model missing, opening download page")
            Toast.makeText(service, "离线语音模型未下载", Toast.LENGTH_SHORT).show()
            service.startActivity(
                Intent(service, VoiceModelActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (OfflineVoiceManager.isBusy()) {
            OfflineVoiceManager.stop()
            return
        }
        if (!VoicePermissionActivity.hasPermission(service)) {
            VoicePermissionActivity.request(service) { granted ->
                if (granted) {
                    // The permission activity took focus and hid the keyboard;
                    // bring it back before the overlay starts recording.
                    service.requestShowSelf(0)
                    handler.postDelayed({ begin() }, 150)
                } else {
                    showError("未授予录音权限")
                }
            }
            return
        }
        begin()
    }

    private fun begin() {
        handler.removeCallbacksAndMessages(null)
        panel.setTranscript("")
        panel.setPhase(VoicePanelView.Phase.LOADING)
        panel.setCaption("正在准备离线语音模型…")
        OfflineVoiceManager.start(service)
    }

    private fun hideDelayed(delayMs: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ hidePanel() }, delayMs)
    }

    fun hidePanel() {
        handler.removeCallbacksAndMessages(null)
        panel.setPhase(VoicePanelView.Phase.HIDDEN)
    }

    /** Called when the keyboard is dismissed while a session is still open. */
    fun onKeyboardHidden() {
        OfflineVoiceManager.cancel()
        hidePanel()
    }

    /** Keyboard view detached: drop the native recognizer and unregister. */
    fun release() {
        OfflineVoiceManager.setListener(null)
        OfflineVoiceManager.release()
        hidePanel()
    }

    override fun onStateChanged(state: OfflineVoiceManager.State) {
        when (state) {
            OfflineVoiceManager.State.PREPARING -> panel.setPhase(VoicePanelView.Phase.LOADING)
            OfflineVoiceManager.State.LISTENING -> panel.setPhase(VoicePanelView.Phase.LISTENING)
            OfflineVoiceManager.State.FINISHING -> {
                // A cancel may already have closed the panel while the engine drains.
                if (panel.isListening) panel.setPhase(VoicePanelView.Phase.FINISHING)
            }

            OfflineVoiceManager.State.IDLE -> hideDelayed(420)
        }
    }

    override fun onCaption(text: String) {
        panel.setCaption(text)
    }

    override fun onTranscript(text: String) {
        panel.setTranscript(text)
    }

    override fun onAmplitude(level: Float) {
        panel.setAmplitude(level)
    }

    override fun onError(message: String) {
        showError(message)
    }

    private fun showError(message: String) {
        panel.setTranscript("")
        panel.setPhase(VoicePanelView.Phase.ERROR)
        panel.setCaption(message)
        hideDelayed(1_600)
    }
}
