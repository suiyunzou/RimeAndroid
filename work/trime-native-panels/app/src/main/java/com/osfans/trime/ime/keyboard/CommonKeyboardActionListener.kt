/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.app.Dialog
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeConfig
import com.osfans.trime.core.RimeKeyEvent
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.smart.*
import com.osfans.trime.ime.segments.SegmentsWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dialog.EnabledSchemaPickerDialog
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.SoundEffectPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.InputMethodUtils
import com.osfans.trime.util.buildIntentFromAction
import com.osfans.trime.util.buildIntentFromArgument
import com.osfans.trime.util.customFormatDateTime
import com.osfans.trime.util.isAsciiPrintable
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber

class CommonKeyboardActionListener(override val di: DI) : DIAware {

    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()
    private val theme: Theme by instance()
    private val rime: RimeSession by instance()
    private val windowManager: BoardWindowManager by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val liquidWindow: LiquidWindow by instance()
    private val inputBar: com.osfans.trime.ime.bar.InputBarDelegate by instance()

    private val prefs = AppPrefs.defaultInstance()

    /** A single typed @ must visit the same processor as a hardware @ key.
     * Candidate, clipboard, and multi-character commits remain literal.
     */
    fun tryEmailCandidates(text: String): Boolean {
        if (text != "@" || !keyboardWindow.allowSmartInput || OfflinePrivacyPolicy.sensitiveEditor) return false
        val editor = service.currentInputEditorInfo ?: return false
        val connection = service.currentInputConnection ?: return false
        service.postEditorJob {
            if (service.currentInputEditorInfo !== editor || service.currentInputConnection !== connection) return@postEditorJob
            // Only enhanced schemas opt in. An ordinary schema retains literal @
            // even if its punctuator gives this character a different meaning.
            val supported = RimeConfig.openSchema(schemaCached.schemaId).use { config ->
                config.getList("engine/processors", RimeConfig::getString).any {
                    it == "lua_processor@*clean_smart_processor" || it == "lua_processor@clean_smart_processor"
                }
            }
            Rime.enqueueEditorAction {
                if (service.currentInputEditorInfo !== editor || service.currentInputConnection !== connection) return@enqueueEditorAction
                fun sendAt() {
                    service.postEditorJob send@{
                        if (service.currentInputEditorInfo !== editor || service.currentInputConnection !== connection) return@send
                        val handled = supported && !getRuntimeOption("smart_sensitive_input") && processKey('@'.code)
                        if (!handled && statusCached.isComposing) commitComposition()
                        Rime.enqueueEditorAction {
                            if (service.currentInputEditorInfo === editor && service.currentInputConnection === connection && !handled) {
                                service.commitText("@")
                            }
                        }
                    }
                }
                if (supported) {
                    // Symbol locking/panels would hide candidates or turn the next
                    // letter into punctuation. Switch before processing @: changing
                    // the keyboard's ASCII mode commits an existing composition.
                    keyboardWindow.setFlickSymbolsLocked(false)
                    keyboardWindow.switchKeyboard("default") {
                        if (service.currentInputEditorInfo !== editor || service.currentInputConnection !== connection) return@switchKeyboard
                        if (!windowManager.isAttached(keyboardWindow)) windowManager.attachWindow(KeyboardWindow)
                        sendAt()
                    }
                } else sendAt()
            }
        }
        return true
    }

    private fun showDialog(dialog: suspend (RimeApi) -> Dialog) {
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                service.showDialog(dialog(api))
            }
        }
    }

    private fun showThemePicker() {
        showDialog { api ->
            ThemePickerDialog.build(service.lifecycleScope, context) {
                api.commitComposition()
            }
        }
    }

    private fun showColorPicker() {
        showDialog { api ->
            ColorPickerDialog.build(service.lifecycleScope, context) {
                api.commitComposition()
            }
        }
    }

    private fun showSoundEffectPicker() {
        showDialog {
            SoundEffectPickerDialog.build(service.lifecycleScope, context)
        }
    }

    private fun showEnabledSchemaPicker() {
        showDialog { api ->
            EnabledSchemaPickerDialog.build(api, service.lifecycleScope, context) {
                setNegativeButton(R.string.enable_schemata) { _, _ ->
                    AppUtils.launchMainToSchemaList(context)
                }
            }
        }
    }

    private fun expandActiveText(input: String): String = if (input.matches(PLACEHOLDER_PATTERN)) {
        input.format(
            service.getActiveText(1),
            service.getActiveText(2),
            service.getActiveText(3),
            service.getActiveText(4),
        )
    } else {
        input
    }

    val listener by lazy {
        object : KeyboardActionListener {
            private fun toggleLanguage() {
                val editor = service.currentInputEditorInfo ?: return
                keyboardWindow.setFlickSymbolsLocked(false)
                service.postRimeJob {
                    if (service.currentInputEditorInfo !== editor) return@postRimeJob
                    // Keep pending input before switching, and read the live option so
                    // consecutive taps toggle in order even before the keys redraw.
                    if (getRawInput().isNotEmpty()) commitComposition()
                    setRuntimeOption("ascii_mode", !getRuntimeOption("ascii_mode"))
                }
            }

            private fun smartCommit(text: String) {
                if (text.isEmpty()) return
                if (tryEmailCandidates(text)) return
                service.commitPanelText(text, isActive = { true }, pairPunctuation = true,
                    localizePunctuation = keyboardWindow.localizePunctuation) { }
            }

            override fun showGlideCandidates(candidates: List<GlideDecoder.Candidate>, onChoose: (GlideDecoder.Candidate) -> Unit) = inputBar.showGlideCandidates(candidates, onChoose)
            override fun clearGlideCandidates() = inputBar.clearGlideCandidates()

            override fun onSmartAction(action: String) {
                when {
                    action.startsWith("flick_symbol:") || action.startsWith("lock_flick_symbol:") || action.startsWith("locked_symbol:") -> {
                        val text = action.substringAfter(':')
                        if (tryEmailCandidates(text)) return
                        if (action.startsWith("lock_flick_symbol:")) keyboardWindow.setFlickSymbolsLocked(true)
                        val keyboard = KeyboardWindow.currentKeyboard
                        val revision = keyboard.flickSymbolsRevision
                        service.commitPanelText(text, isActive = { true }, pairPunctuation = true,
                            localizePunctuation = keyboardWindow.localizePunctuation) { committed ->
                            if (!committed && KeyboardWindow.currentKeyboard === keyboard &&
                                keyboard.flickSymbolsRevision == revision) keyboardWindow.setFlickSymbolsLocked(false)
                        }
                    }
                    action.startsWith("text:") -> smartCommit(action.removePrefix("text:"))
                    action == "action:clear_all" -> service.clearCompositionOrText()
                    action.startsWith("action:") -> ActionKeyDispatcher.execute(service.currentInputConnection, action)
                    else -> when (action) {
                        "settings" -> service.startActivity(Intent(service, com.osfans.trime.ime.smart.SmartSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        "calculator" -> windowManager.attachWindow(CalculatorWindow(di))
                        "quotes" -> windowManager.attachWindow(BookQuoteWindow(di))
                        "symbols" -> windowManager.attachWindow(ExtendedSymbolsWindow(di))
                        "emoji_variants", "emoji_expand" -> windowManager.attachWindow(ReferenceEmojiWindow(di, true))
                        "emoji" -> windowManager.attachWindow(ReferenceEmojiWindow(di))
                        "clipboard" -> handleClipboardWindow("smart")
                        "date", "time", "chinese_date", "weekday", "datetime", "lunar_date", "lunar_datetime" -> smartCommit(com.osfans.trime.ime.smart.RadialMenuView.formatTimeAction(action))
                        "traditional" -> service.postRimeJob { SmartLanguageOptions.toggleTraditional(this) }
                        "toggle_language" -> toggleLanguage()
                        "english" -> service.postRimeJob {
                            if (statusCached.isComposing) commitComposition()
                            setRuntimeOption("ascii_mode", true)
                        }
                        "t9" -> windowManager.attachWindow(T9Window(di))
                    }
                }
            }
            override fun onPress(keyEventCode: Int) {
                InputFeedbackManager.run {
                    keyPressSound(keyEventCode)
                    keyPressSpeak(keyEventCode)
                }
            }

            override fun onAction(action: KeyAction) {
                val text = action.getText(KeyboardWindow.currentKeyboard)
                val shouldHandle = when {
                    action.commit.isNotEmpty() -> {
                        if (tryEmailCandidates(action.commit)) {
                            // The Rime processor owns the email candidate composition.
                        } else if (keyboardWindow.localizePunctuation && KeyboardPunctuation.isLocalizable(action.commit)) {
                            smartCommit(action.commit)
                        } else if (keyboardWindow.allowSmartInput && !service.isRimeComposing && action.commit.length == 1 && PairPunctuationHandler.insertPair(service.currentInputConnection, action.commit[0])) {
                            // A symbol key owns the pair; candidate/paste commits remain literal.
                        } else service.commitText(action.commit)
                        false
                    }

                    text.isNotEmpty() -> {
                        onText(text)
                        false
                    }

                    else -> true
                }

                if (shouldHandle) {
                    when (action.code) {
                        KeyEvent.KEYCODE_SWITCH_CHARSET -> handleSwitchCharset(action)
                        KeyEvent.KEYCODE_EISU -> keyboardWindow.switchKeyboard(action.select)
                        KeyEvent.KEYCODE_LANGUAGE_SWITCH -> handleLanguageSwitch(action)
                        KeyEvent.KEYCODE_FUNCTION -> handleFunctionCommand(action)
                        KeyEvent.KEYCODE_SETTINGS -> handleSettings(action)
                        KeyEvent.KEYCODE_PROG_RED -> showColorPicker()
                        KeyEvent.KEYCODE_MENU -> showEnabledSchemaPicker()
                        KeyEvent.KEYCODE_VOICE_ASSIST -> switchToVoiceInputMethod()
                        else -> handleDefaultKeyAction(action)
                    }
                }
            }

            private fun handleSwitchCharset(action: KeyAction) {
                val option = action.toggle.ifEmpty { return }

                rime.launchOnReady { api ->
                    service.lifecycleScope.launch {
                        val isEnabled = api.getRuntimeOption(option)
                        val isComposing = api.statusCached.isComposing
                        api.setRuntimeOption(option, !isEnabled)
                        if (option == "ascii_mode" && isComposing) {
                            api.getRawInput().takeIf { it.isNotEmpty() }?.let {
                                service.commitText(it)
                                api.clearComposition()
                            }
                        }
                    }
                }
            }

            private fun handleLanguageSwitch(action: KeyAction) {
                // Existing installed reference themes already bind the globe to
                // LANGUAGE_SWITCH. Keep that keycode for its icon/footer geometry.
                if (theme.isIosStyle && action.select.isEmpty()) {
                    toggleLanguage()
                    return
                }
                when {
                    action.select == ".next" -> service.switchToNextIme()
                    action.select.isNotEmpty() -> service.switchToPrevIme()
                    else -> inputMethodManager.showInputMethodPicker()
                }
            }

            private fun handleFunctionCommand(action: KeyAction) {
                val arg = expandActiveText(action.option)

                when (action.command) {
                    "smart_input" -> onSmartAction(arg)
                    "liquid_keyboard" -> handleLiquidKeyboard(arg)
                    "menu_keyboard" -> windowManager.attachWindow(SwitchOptionWindow(di))
                    "clipboard_window" -> handleClipboardWindow(arg)
                    "set_color_scheme" -> handleColorScheme(arg)
                    "set_theme" -> handleTheme(arg)
                    "broadcast" -> service.sendBroadcast(Intent(arg))
                    "clipboard" -> handleClipboard()
                    "commit" -> service.commitText(arg)
                    "date" -> service.commitText(customFormatDateTime(arg))
                    "run" -> handleRunCommand(arg)
                    "apply" -> handleApplyCommand(arg)
                    "share_text" -> service.shareText()
                    "select_candidate" -> handleSelectCandidate(arg)
                    "switch_hide_key_symbol" -> switchHideKeySymbol()
                    "switch_hide_key_hint" -> switchHideKeyHint()
                    else -> handleIntentAction(action.command, arg)
                }
            }

            private fun handleLiquidKeyboard(arg: String) {
                if (keyboardWindow.allowSmartInput && theme.isIosStyle && arg in setOf("emoji", "表情")) {
                    windowManager.attachWindow(ReferenceEmojiWindow(di))
                    return
                }
                // for compatibility
                if (arg == "剪贴" || arg == "clipboard") {
                    if (keyboardWindow.allowSmartInput && theme.isIosStyle) handleClipboardWindow("smart")
                    else windowManager.attachWindow(ClipboardWindow(di))
                    return
                }
                val liquidTagList = theme.liquidKeyboard.getTagList()
                // A command may use the stable section id while its tab name is localized.
                // Keep legacy label/type matching first for existing themes.
                val index = liquidTagList.indexOfFirst { tag ->
                    tag.label == arg || runCatching {
                        LiquidKeyboard.DataType.valueOf(arg.uppercase())
                    }.getOrNull() == tag.type
                }.takeIf { it >= 0 } ?: theme.liquidKeyboard.keyboards.indexOfFirst { it.id == arg }

                if (index >= 0) {
                    windowManager.attachWindow(LiquidWindow)
                    liquidWindow.setDataByIndex(index)
                } else {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            private fun handleClipboardWindow(arg: String) {
                if (arg == "smart" && OfflinePrivacyPolicy.sensitiveEditor) {
                    windowManager.attachWindow(SegmentsWindow(di, ""))
                    return
                }
                if (arg != "history" && arg != "1") {
                    val latest = runCatching {
                        clipboardManager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(service)?.toString()
                    }.getOrNull()
                    if (!latest.isNullOrEmpty() || arg == "smart") {
                        windowManager.attachWindow(SegmentsWindow(di, latest.orEmpty()))
                        return
                    }
                }
                val tabIndex = arg.toIntOrNull()?.coerceIn(0, 1) ?: 0
                windowManager.attachWindow(ClipboardWindow(di, tabIndex))
            }

            private fun handleColorScheme(arg: String) {
                if (arg in ThemeManager.activeTheme.presetColorSchemes) {
                    ColorManager.setColorScheme(arg)
                }
            }

            private fun handleTheme(arg: String) {
                if (arg.isEmpty()) {
                    // 参数为空时，刷新当前主题
                    val themeId = ThemeManager.prefs.selectedTheme.getValue()
                    service.lifecycleScope.launch { ThemeManager.selectTheme(themeId) }
                } else {
                    // 通过主题名称查找对应的配置ID并切换主题
                    ThemeManager.getAllThemes()
                        .find { it.name.equals(arg, ignoreCase = true) }?.let { item ->
                            service.lifecycleScope.launch { ThemeManager.selectTheme(item.configId) }
                        }
                }
            }

            private fun handleClipboard() {
                clipboardManager.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(service)
                    ?.let { service.commitText(it.toString()) }
            }

            private fun handleRunCommand(arg: String) {
                buildIntentFromArgument(arg)?.let { intent ->
                    // Keep existing themes working while the height editor now lives in this app.
                    if (intent.component?.packageName == "local.trime.heighteditor" &&
                        intent.component?.className == "local.trime.heighteditor.HeightLaunchActivity"
                    ) {
                        intent.setClass(service, com.osfans.trime.ui.height.HeightActivity::class.java)
                    }
                    intent.flags = if (intent.component?.packageName == service.packageName &&
                        intent.action == "${service.packageName}.OPEN_LOCAL_RULES"
                    ) {
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    } else {
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                    }
                    service.startActivity(intent)
                }
            }

            private fun handleApplyCommand(arg: String) {
                when (arg) {
                    "DEPLOY" -> {
                        Timber.i("try to start maintenance via command ...")
                        rime.launchOnReady { api -> api.deploy() }
                    }

                    "SYNC_USER_DATA" -> {
                        Timber.i("try to sync rime user data via command ...")
                        rime.launchOnReady { api -> api.syncUserData() }
                    }

                    "UPDATE_CONFIG" -> {
                        Timber.i("try to update rime config via command ...")
                        rime.launchOnReady { api ->
                            api.updateConfig()
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    else -> Timber.w("Unknown apply method: $arg")
                }
            }

            private fun handleIntentAction(command: String, arg: String) {
                buildIntentFromAction(command, arg)?.let { intent ->
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                    service.startActivity(intent)
                }
            }

            private fun handleSelectCandidate(arg: String) {
                val index = arg.toIntOrNull() ?: return
                rime.launchOnReady { api ->
                    service.lifecycleScope.launch {
                        api.selectCandidate(index, false)
                    }
                }
            }

            private fun switchHideKeySymbol() {
                val preference = prefs.keyboard.hideKeySymbol
                preference.setValue(!preference.getValue())
            }

            private fun switchHideKeyHint() {
                val preference = prefs.keyboard.hideKeyHint
                preference.setValue(!preference.getValue())
            }

            private fun handleSettings(action: KeyAction) {
                when (action.option) {
                    "theme" -> showThemePicker()
                    "color" -> showColorPicker()
                    "schema" -> AppUtils.launchMainToSchemaList(context)
                    "sound" -> showSoundEffectPicker()
                    else -> AppUtils.launchMainActivity(service)
                }
            }

            private fun switchToVoiceInputMethod() {
                val pkgName = prefs.general.preferredVoiceInput.getValue()
                val voiceInputSubType = if (pkgName.isNotEmpty()) {
                    InputMethodUtils.voiceInputMethods().find {
                        it.first.packageName == pkgName
                    }?.let {
                        it.first.id to it.second
                    } ?: InputMethodUtils.firstVoiceInput()
                } else {
                    InputMethodUtils.firstVoiceInput()
                }
                if (voiceInputSubType != null) {
                    val (id, subType) = voiceInputSubType
                    InputMethodUtils.switchInputMethod(service, id, subType)
                } else {
                    service.toast(R.string.no_voice_input_installed)
                }
            }

            private fun handleDefaultKeyAction(action: KeyAction) {
                val shouldHookShiftKey = when {
                    prefs.keyboard.hookShiftSpace.getValue() && action.code == KeyEvent.KEYCODE_SPACE -> true
                    prefs.keyboard.hookShiftNum.getValue() && action.code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> true
                    prefs.keyboard.hookShiftSymbol.getValue() && action.code in KeyEvent.KEYCODE_GRAVE..KeyEvent.KEYCODE_SLASH -> true
                    prefs.keyboard.hookShiftSymbol.getValue() && action.code in setOf(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD) -> true
                    else -> false
                }

                if (action.modifier == 0 && KeyboardWindow.currentKeyboard.isOnlyShiftOn && shouldHookShiftKey) {
                    onKey(action.code, 0)
                    return
                }

                val modifier = when {
                    action.modifier == 0 -> KeyboardWindow.currentKeyboard.modifier

                    (action.modifier and KeyEvent.META_CTRL_ON) != 0 && isNavigationKey(action.code) ->
                        action.modifier or KeyboardWindow.currentKeyboard.modifier

                    else -> action.modifier
                }

                onKey(action.code, modifier)
            }

            private fun isNavigationKey(keyCode: Int): Boolean = keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
                keyCode == KeyEvent.KEYCODE_MOVE_END

            override fun onKey(
                keyEventCode: Int,
                metaState: Int,
            ) {
                if (keyEventCode == KeyEvent.KEYCODE_DEL && metaState == 0 && keyboardWindow.allowSmartInput && !service.isRimeComposing &&
                    PairPunctuationHandler.handleBackspace(service.currentInputConnection)
                ) return
                if (keyboardWindow.localizePunctuation && metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK) == 0) {
                    val char = com.osfans.trime.util.virtualKeyCharacterMap.get(keyEventCode, metaState)
                    if (char in 1..Char.MAX_VALUE.code) {
                        val mark = char.toChar().toString()
                        if (KeyboardPunctuation.isLocalizable(mark)) {
                            smartCommit(mark)
                            return
                        }
                    }
                }
                if (keyboardWindow.allowSmartInput && !service.isRimeComposing && metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK) == 0) {
                    val char = com.osfans.trime.util.virtualKeyCharacterMap.get(keyEventCode, metaState)
                    if (char in 1..Char.MAX_VALUE.code && PairPunctuationHandler.insertPair(service.currentInputConnection, char.toChar())) return
                }
                // An uppercase letter key (e.g. from `{x: A}`) is passed to
                // rime as the uppercase keysym with Shift, matching what a
                // physical keyboard reports via the unicode char, so that
                // rime commits the uppercase letter in ascii mode as well.
                // The generated reverse mapping would otherwise resolve e.g.
                // KEYCODE_A to the lowercase name "a" (XK_a).
                val value =
                    if (metaState and KeyEvent.META_SHIFT_ON != 0 &&
                        keyEventCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
                    ) {
                        'A'.code + (keyEventCode - KeyEvent.KEYCODE_A) // XK_A..XK_Z
                    } else {
                        val name = KeyCode.codeToKeyName(keyEventCode) ?: "VoidSymbol"
                        RimeKeyEvent.getKeycodeByName(name)
                    }
                val m = if (keyEventCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_EQUALS) {
                    metaState or KeyEvent.META_NUM_LOCK_ON
                } else {
                    metaState
                }
                val modifiers = KeyModifiers.fromMetaState(m).modifiers
                service.postEditorJob {
                    if (service.hookKeyboard(keyEventCode, m)) {
                        Timber.d("handleKey: hook")
                        return@postEditorJob
                    }
                    if (processKey(value, modifiers)) {
                        Timber.d("handleKey: processKey")
                        return@postEditorJob
                    }
                    if (AppUtils.launchKeyCategory(service, keyEventCode)) {
                        Timber.d("handleKey: openCategory")
                        return@postEditorJob
                    }
                    // other special cases
                    if (keyEventCode == KeyEvent.KEYCODE_BACK) {
                        service.requestHideSelf(0)
                    }
                }
            }

            override fun onText(input: String) {
                if (input.isEmpty()) return
                if (keyboardWindow.localizePunctuation && KeyboardPunctuation.isLocalizable(input)) {
                    smartCommit(input)
                    return
                }
                if (input.length == 1 && keyboardWindow.allowSmartInput && !service.isRimeComposing && PairPunctuationHandler.insertPair(service.currentInputConnection, input[0])) return
                Timber.d("onText: $input")
                val status = rime.run { statusCached }
                if (!input[0].isAsciiPrintable() && status.isComposing) {
                    service.postEditorJob { commitComposition() }
                }

                val escaped = input.replace("{}", "{braceleft}{braceright}")
                var i = 0
                while (i < escaped.length) {
                    val value = when (val match = TEXT_INPUT_PATTERN.matchEntire(escaped.substring(i))) {
                        match if (match != null) -> match.groupValues[1]
                        else -> escaped[i].toString()
                    }

                    service.postEditorJob {
                        if (value.run { startsWith('{') && endsWith('}') }) {
                            val token = value.removeSurrounding("{", "}")
                            onAction(theme.resolveAction(token))
                        } else if (!value[0].isAsciiPrintable()) {
                            service.commitText(value)
                        } else {
                            simulateKeySequence(value)
                        }
                    }

                    i += value.length
                }
            }
        }
    }

    companion object {
        /**
         * Regex for combined key events.
         * group(1) captures either:
         *   - a plain prefix (optionally preceded by {Escape}) from the left branch,
         *   - or a standalone {xxx} block from the right branch.
         * The trailing .* consumes the rest of the input without affecting group(1).
         */
        private val TEXT_INPUT_PATTERN = """^((?:\{Escape\})?[^{}]+|\{[^{}]+\}).*$""".toRegex()

        private val PLACEHOLDER_PATTERN = Regex(".*(%([1-4]\\$)?s).*")
    }
}
