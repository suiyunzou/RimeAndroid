// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Point
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.osfans.trime.R
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.core.SchemaItem
import com.osfans.trime.core.StatusProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.ResidentWindow
import com.osfans.trime.util.isLandscape
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.systemservices.windowManager
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class KeyboardWindow(di: DI) :
    BoardWindow.NoBarBoardWindow(di),
    ResidentWindow,
    InputBroadcastReceiver {
    private val service: TrimeInputMethodService by instance()
    private val theme: Theme by instance()
    private val rime: RimeSession by instance()
    private val commonKeyboardActionListener: CommonKeyboardActionListener by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()
    private val boardWindowManager: com.osfans.trime.ime.window.BoardWindowManager by instance()

    private val cursorCapsMode: Int
        get() =
            service.currentInputEditorInfo.run {
                if (inputType != InputType.TYPE_NULL) {
                    service.currentInputConnection?.getCursorCapsMode(inputType) ?: 0
                } else {
                    0
                }
            }

    private val _currentKeyboardHeight =
        MutableSharedFlow<Int>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val currentKeyboardHeight = _currentKeyboardHeight.asSharedFlow()

    private lateinit var keyboardView: FrameLayout

    companion object : ResidentWindow.Key {
        lateinit var currentKeyboard: Keyboard
    }

    override val key: ResidentWindow.Key
        get() = KeyboardWindow

    private val presetKeyboardIds = theme.presetKeyboards.keys.toList()
    private var currentKeyboardId = ""
    val allowSmartInput: Boolean
        get() = listOf("email", "rules", "local_rule", "search").none { currentKeyboardId.contains(it) }
    val localizePunctuation: Boolean
        get() = allowSmartInput && !editorAsciiRequired &&
            service.currentInputEditorInfo?.let { it.inputType and InputType.TYPE_MASK_VARIATION != InputType.TYPE_TEXT_VARIATION_URI } == true
    private var lastKeyboardId = ""
    private var lastLockKeyboardId = ""
    @Volatile private var tempAsciiMode: Boolean? = null
    private var editorAsciiRequired = requiresAscii(service.currentInputEditorInfo)

    private fun requiresAscii(info: EditorInfo?): Boolean {
        if (info == null) return false
        if (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0) return true
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
            InputType.TYPE_CLASS_TEXT -> (info.inputType and InputType.TYPE_MASK_VARIATION) in setOf(
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            else -> false
        }
    }

    private fun queueAsciiMode(target: Boolean, preservePrevious: Boolean = false) {
        service.postEditorJob {
            // A previously queued layout change may still be pending. Read the
            // actual option here, not the status cached when the editor opened.
            val current = getRuntimeOption("ascii_mode")
            if (preservePrevious && tempAsciiMode == null) tempAsciiMode = current
            if (current != target) {
                commitComposition()
                setRuntimeOption("ascii_mode", target)
            }
        }
    }
    private val cachedKeyboards = mutableMapOf<String, Pair<Keyboard, KeyboardView>>()
    private val activeKeyboard: Keyboard? get() = cachedKeyboards[currentKeyboardId]?.first
    private val currentKeyboardView: KeyboardView? get() = cachedKeyboards[currentKeyboardId]?.second

    private val keyboardActionListener = commonKeyboardActionListener.listener

    private var settingsAnimation: AnimatorSet? = null
    private var outgoingSettingsView: KeyboardView? = null
    private var incomingSettingsView: KeyboardView? = null

    private fun settingsPage(id: String): Int = when (id) {
        "settings_clean", "compact_settings_clean" -> 0
        "settings_clean_more", "compact_settings_clean_more" -> 1
        else -> -1
    }

    private fun finishSettingsTransition() {
        val animation = settingsAnimation
        settingsAnimation = null
        animation?.cancel()
        outgoingSettingsView?.let {
            keyboardView.overlay.remove(it)
            it.translationX = 0f
            it.alpha = 1f
        }
        incomingSettingsView?.let {
            it.translationX = 0f
            it.alpha = 1f
            it.suppressSettingsTouches = false
        }
        outgoingSettingsView = null
        incomingSettingsView = null
    }

    private fun animateSettingsTransition(old: KeyboardView, next: KeyboardView, forward: Boolean) {
        val distance = keyboardView.width.toFloat()
        if (distance <= 0f || old.width <= 0) return
        val sign = if (forward) 1f else -1f
        // Overlay is non-interactive. The incoming surface rejects taps until
        // the 165ms transition ends, so moving cards cannot activate a setting.
        keyboardView.overlay.add(old)
        outgoingSettingsView = old
        incomingSettingsView = next
        next.suppressSettingsTouches = true
        next.translationX = sign * distance
        next.alpha = 0f
        val animation = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(old, View.TRANSLATION_X, 0f, -sign * distance),
                ObjectAnimator.ofFloat(old, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(next, View.TRANSLATION_X, sign * distance, 0f),
                ObjectAnimator.ofFloat(next, View.ALPHA, 0f, 1f),
            )
            duration = 165
            interpolator = DecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    if (settingsAnimation === animator) finishSettingsTransition()
                }
            })
        }
        settingsAnimation = animation
        animation.start()
    }

    private var lastIsPortrait: Boolean? = null
    private var containerWidth: Int = 0
    private var allowedWidth: Int = 0

    private val onKeyboardViewLayoutChangeListener =
        View.OnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val width = right - left
            if (width > 0 && allowedWidth != width) {
                val isPortrait = !context.resources.configuration.isLandscape()
                lastIsPortrait = isPortrait
                containerWidth = width
                allowedWidth = width
                v.post { refreshKeyboards() }
            }
        }

    override fun onCreateView(): View {
        keyboardView = context.frameLayout(R.id.keyboard_view)
        keyboardView.addOnLayoutChangeListener(onKeyboardViewLayoutChangeListener)
        attachKeyboard(evalKeyboard(".default"))
        return keyboardView
    }

    private fun detachCurrentView() {
        finishSettingsTransition()
        currentKeyboardView?.also {
            it.onDetach()
            keyboardView.removeView(it)
        }
        activeKeyboard?.lastAsciiMode = rime.run { statusCached }.isAsciiMode
    }

    /** 计算键盘可用宽度：优先使用已测量的容器宽度，否则回退到系统窗口测量。 */
    private fun computeAllowedWidth(): Int {
        val isPortrait = !context.resources.configuration.isLandscape()

        if (containerWidth > 0 && lastIsPortrait == isPortrait) {
            return containerWidth
        }

        val padding = theme.style.run {
            if (context.isLandscapeMode()) keyboardPaddingLand else keyboardPadding
        }

        val safeWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = context.windowManager.maximumWindowMetrics
            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            val displayWidth = context.resources.displayMetrics.widthPixels
            val windowWidth = windowMetrics.bounds.width() - insets.left - insets.right
            if (windowWidth < displayWidth - context.dp(1)) displayWidth else windowWidth
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            context.windowManager.defaultDisplay.getSize(size)
            size.x
        }

        val width = safeWidth - 2 * context.dp(padding)
        allowedWidth = width
        return width
    }

    private fun selectKeyboardConfig(name: String): TextKeyboard? {
        val config = theme.presetKeyboards[name] ?: theme.presetKeyboards["default"]
        val importPreset = config?.importPreset
        if (!importPreset.isNullOrEmpty()) {
            return selectKeyboardConfig(importPreset)
        }
        return config
    }

    private fun attachKeyboard(target: String) {
        currentKeyboardId = target
        lastKeyboardId = target

        val config = selectKeyboardConfig(target)
        val keyboard = activeKeyboard ?: Keyboard(context, theme, computeAllowedWidth(), config)
        val view = currentKeyboardView ?: KeyboardView(context, theme, keyboard, popup, service, keyboardActionListener, enterKeyDisplay)

        view.settingsPage = settingsPage(target)
        view.smartFeaturesEnabled = allowSmartInput

        if (activeKeyboard == null) {
            cachedKeyboards[target] = keyboard to view
            keyboard.lastAsciiMode = keyboard.asciiMode
        }

        keyboard.also {
            runBlocking { _currentKeyboardHeight.emit(it.keyboardHeight) }
            if (it.isLock) lastLockKeyboardId = target
            dispatchCapsState(it::setShifted)

            val targetMode = editorAsciiRequired || if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode
            // The reference number page is another face of the same keyboard.
            // Keep the selected language; numeric, email and password fields still force ASCII.
            if (!theme.isIosStyle || target != "number" || editorAsciiRequired) {
                queueAsciiMode(targetMode, preservePrevious = editorAsciiRequired)
            }

            currentKeyboard = it
        }

        view.let {
            keyboardView.apply {
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                add(it, lParams(matchParent, matchParent))
            }
        }
    }

    private fun smartMatchKeyboard(): String {
        // 主题的布局中包含方案id，直接采用
        val currentSchema = rime.run { statusCached }.schemaId
        if (presetKeyboardIds.contains(currentSchema)) {
            return currentSchema
        }
        val alphabet = rime.run { schemaCached }.alphabet
        val layout =
            when {
                alphabet.all { it.isLetter() } -> "qwerty"

                // 包含 26 个字母
                alphabet.all { it.isLetter() || ",./;".any(it::equals) } -> "qwerty_"

                // 包含 26 个字母和,./;
                alphabet.all { it.isLetterOrDigit() } -> "qwerty0"

                // 包含 26 个字母和数字键
                else -> "default"
            }
        return if (presetKeyboardIds.contains(layout)) layout else "default"
    }

    private fun evalKeyboard(id: String): String {
        val currentIdx = presetKeyboardIds.indexOfFirst { currentKeyboardId == it }
        val dot =
            when (id) {
                ".default" -> smartMatchKeyboard()

                ".prior" -> presetKeyboardIds.getOrNull(currentIdx - 1) ?: currentKeyboardId

                ".next" -> presetKeyboardIds.getOrNull(currentIdx + 1) ?: currentKeyboardId

                ".last" -> lastKeyboardId

                ".last_lock" -> lastLockKeyboardId

                ".ascii" -> {
                    var ascii = activeKeyboard?.asciiKeyboard
                    if (ascii.isNullOrEmpty()) {
                        ascii = lastLockKeyboardId
                    }
                    if (presetKeyboardIds.contains(ascii)) ascii else currentKeyboardId
                }

                else -> {
                    id.ifEmpty {
                        if (activeKeyboard?.isLock == true) currentKeyboardId else lastLockKeyboardId
                    }
                }
            }
        var final = dot.ifEmpty { smartMatchKeyboard() }

        // 切换到横屏布局
        if (service.isLandscapeMode()) {
            val landscape =
                theme.presetKeyboards[final]?.landscapeKeyboard ?: ""
            if (landscape.isNotEmpty() && presetKeyboardIds.contains(landscape)) final = landscape
        }
        return final
    }

    fun setFlickSymbolsLocked(locked: Boolean) {
        activeKeyboard?.flickSymbolsLocked = locked
        currentKeyboardView?.invalidateAllKeys()
    }

    fun switchKeyboard(to: String, onSwitched: (() -> Unit)? = null) {
        val target = evalKeyboard(to)
        ContextCompat.getMainExecutor(service).execute {
            if (cachedKeyboards.containsKey(target)) {
                if (target == currentKeyboardId) {
                    onSwitched?.invoke()
                    return@execute
                }
            }
            val from = settingsPage(currentKeyboardId)
            cachedKeyboards.values.forEach { (keyboard, _) -> keyboard.flickSymbolsLocked = false }
            val toPage = settingsPage(target)
            val old = currentKeyboardView
            val sameOrientation = currentKeyboardId.startsWith("compact_") == target.startsWith("compact_")
            detachCurrentView()
            attachKeyboard(target)
            val next = currentKeyboardView
            if (from >= 0 && toPage >= 0 && from != toPage && sameOrientation && old != null && next != null) {
                animateSettingsTransition(old, next, toPage > from)
            }
            onSwitched?.invoke()
        }
        Timber.d("Switched to keyboard: $target")
    }

    fun refreshKeyboards(isAll: Boolean = false) {
        val id = currentKeyboardId.ifEmpty { return }
        detachCurrentView()
        if (isAll) {
            cachedKeyboards.clear()
        } else {
            cachedKeyboards.remove(id)
        }
        attachKeyboard(id)
    }

    /** Repaints the keyboard after a color-scheme switch; keys re-resolve their colors. */
    override fun refreshColors() {
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onEnterKeyLabelUpdate(label: String) {
        cachedKeyboards.values.forEach { (_, view) -> view.invalidateAllKeys() }
    }

    override fun onStartInput(info: EditorInfo) = onStartInput(info, false)

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        editorAsciiRequired = requiresAscii(info)
        // restartInput refreshes the current editor; it is not a request to leave symbol mode.
        // Reassign even when preserving so stale commit callbacks cannot clear the new session's latch.
        cachedKeyboards.values.forEach { (keyboard, _) ->
            keyboard.flickSymbolsLocked = restarting && keyboard.flickSymbolsLocked
        }
        currentKeyboardView?.invalidateAllKeys()
        val targetKeyboard =
            when (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
                EditorInfo.IME_FLAG_FORCE_ASCII -> ".ascii"

                else -> {
                    when (info.inputType and InputType.TYPE_MASK_CLASS) {
                        InputType.TYPE_CLASS_NUMBER,
                        InputType.TYPE_CLASS_PHONE,
                        InputType.TYPE_CLASS_DATETIME,
                        -> "number"

                        InputType.TYPE_CLASS_TEXT -> {
                            when (info.inputType and InputType.TYPE_MASK_VARIATION) {
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                                -> ".ascii"

                                else -> ""
                            }
                        }

                        else -> ""
                    }
                }
            }
        // Queue this before attaching a layout so the prior language choice is
        // captured before that layout can force ASCII during cold startup.
        if (editorAsciiRequired) queueAsciiMode(true, preservePrevious = true)
        switchKeyboard(targetKeyboard)
        if (editorAsciiRequired) {
            queueAsciiMode(true, preservePrevious = true)
        } else {
            val focusResetMode = activeKeyboard?.takeIf { theme.style.resetAsciiModeOnFocusChange }
                ?.let { if (it.resetAsciiMode) it.asciiMode else it.lastAsciiMode }
            service.postEditorJob {
                val targetMode = tempAsciiMode ?: focusResetMode
                if (targetMode != null && getRuntimeOption("ascii_mode") != targetMode) {
                    setRuntimeOption("ascii_mode", targetMode)
                }
                tempAsciiMode = null
            }
        }
    }

    private fun dispatchCapsState(setShift: (Boolean, Boolean) -> Unit) {
        val status = rime.run { statusCached }
        // TODO: 启用自动首句大写后，点击方向键时，保持Shift锁定状态功能将无法生效
        if (theme.style.autoCaps == true && status.isAsciiMode && currentKeyboardView?.isCapsOn == false) {
            setShift(false, cursorCapsMode != 0)
        }
    }

    override fun onKeyAppearanceUpdate(composing: Boolean, menu: Boolean, paging: Boolean) {
        if (!rime.run { statusCached }.isAsciiMode) {
            activeKeyboard?.appearanceStateKeys?.forEach { key ->
                currentKeyboardView?.invalidateKeyByIndex(key.index)
            }
        }
    }

    override fun onSelectionUpdate(
        start: Int,
        end: Int,
    ) {
        dispatchCapsState { on, shifted ->
            activeKeyboard?.setShifted(on, shifted)?.let { if (it) currentKeyboardView?.invalidateAllKeys() }
        }
    }

    override fun onRimeSchemaUpdated(schema: SchemaItem) {
        if (editorAsciiRequired) service.currentInputEditorInfo?.let { onStartInput(it, true) }
        else switchKeyboard(".default")
    }

    override fun onRimeOptionUpdated(value: RimeMessage.OptionMessage.Data) {
        val option = value.option
        if (option == "ascii_mode") updateDisplayedLanguage(value.value)
        when {
            option.startsWith("_keyboard_") -> {
                val target = option.removePrefix("_keyboard_")
                if (target.isNotEmpty()) {
                    switchKeyboard(target)
                }
            }

            option.startsWith("_key_") -> {
                val what = option.removePrefix("_key_")
                if (what.isNotEmpty() && value.value) {
                    commonKeyboardActionListener
                        .listener
                        .onAction(theme.resolveAction(what))
                }
            }

            option == "ascii_mode" && !value.value -> {
                // 回到中文时，释放 Shift 锁定
                activeKeyboard?.resetShift()
            }
        }
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onInputStatusUpdate(value: StatusProto) {
        updateDisplayedLanguage(value.isAsciiMode)
    }

    private fun updateDisplayedLanguage(ascii: Boolean) {
        var changed = false
        cachedKeyboards.values.forEach { (keyboard, _) ->
            if (keyboard.displayedAsciiMode != ascii) {
                keyboard.displayedAsciiMode = ascii
                changed = true
            }
        }
        if (changed) currentKeyboardView?.invalidateAllKeys()
    }

    override fun onAttached() {
        if (com.osfans.trime.ime.smart.SmartInputPrefs(context).layout == "t9") {
            keyboardView.post {
                if (com.osfans.trime.ime.smart.SmartInputPrefs(context).layout == "t9")
                    boardWindowManager.attachWindow(com.osfans.trime.ime.smart.T9Window(di))
            }
        }
        currentKeyboardView?.invalidateAllKeys()
    }

    override fun onDetached() {
        finishSettingsTransition()
        currentKeyboardView?.onDetach()
    }
}
