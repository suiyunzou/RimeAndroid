/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupAction
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.smart.FlickDirection
import com.osfans.trime.ime.smart.KeyBindingLabels
import com.osfans.trime.ime.smart.KeyboardPunctuation
import com.osfans.trime.ime.smart.OfflinePrivacyPolicy
import com.osfans.trime.util.sp
import splitties.dimensions.dp
import timber.log.Timber

@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class KeyView(
    context: Context,
    private val theme: Theme,
    private val key: Key,
    private val keyboard: Keyboard,
    private val keyboardView: KeyboardView,
    private val keyboardActionListener: KeyboardActionListener,
) : GestureFrame(context) {
    override val feedbackKeyCode: Int get() = key.getCode(KeyBehavior.CLICK)

    private val service: TrimeInputMethodService
        get() = keyboardView.service

    private val popup: PopupDelegate
        get() = keyboardView.popup

    private val deletedTextBuffer = ArrayDeque<String>()
    private val smartPrefs = com.osfans.trime.ime.smart.SmartInputPrefs(context)
    private val glidePrefs = com.osfans.trime.ime.smart.GlidePrefs(context)
    private var variantDownX: Float? = null
    private var variantDownY: Float? = null
    internal var flickPreviewLabel: String? = null
        private set

    private fun bindingName(): String = when (val code = key.getCode(KeyBehavior.CLICK)) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a'.code + code - KeyEvent.KEYCODE_A).toChar().toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0'.code + code - KeyEvent.KEYCODE_0).toChar().toString()
        KeyEvent.KEYCODE_SPACE -> "space"
        KeyEvent.KEYCODE_DEL -> "backspace"
        KeyEvent.KEYCODE_ENTER -> "enter"
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "shift"
        else -> if (isEmojiKey()) "emoji" else key.getLabel().lowercase()
    }

    internal fun showFlickPreview(up: Boolean?) {
        flickPreviewLabel = if (up == null || !smartPrefs.allowsFlick(up) || glidePrefs.enabled) null else {
            val code = key.getCode(KeyBehavior.CLICK)
            val name = bindingName()
            val custom = smartPrefs.binding(name, up)
            when {
                custom?.startsWith("text:") == true -> custom.removePrefix("text:").take(5)
                custom != null -> KeyBindingLabels.corner(custom)
                code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT -> "EN"
                else -> key.symbolLabel.trim().takeIf { it.isNotEmpty() }
            }
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            variantDownX = event.x
            variantDownY = event.y
        }
        return super.onTouchEvent(event)
    }
    private fun variants(): List<String> = com.osfans.trime.ime.smart.SymbolVariants.forKey(key.getLabel())
    private fun isEmojiKey(): Boolean = key.click?.let { (it.command == "liquid_keyboard" && (it.option.contains("表情") || it.option.contains("emoji"))) || (it.command == "smart_input" && it.option.startsWith("emoji")) || it.select.contains("emoji") } == true

    private var keyPressed = false
    override fun isPressed(): Boolean = keyPressed

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val labelInk = Rect()

    private var cachedIcon: IconicsDrawable? = null
    private var cachedIconName: String? = null

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false

    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { getLocationInWindow(it) }
        cachedBounds.set(x + key.extraWidthLeft, y, x + width - key.extraWidthRight, y + height)
        boundsValid = true
    }

    init {
        setWillNotDraw(false)
        isRepeatable = key.click?.isRepeatable ?: false
        isSlideCursor = key.click?.isSlideCursor ?: false
        isSlideDelete = key.click?.isSlideDelete ?: false
        hasLongPress = key.hasAction(KeyBehavior.LONG_CLICK) || variants().isNotEmpty() || isEmojiKey()
        hasDouble = key.hasAction(KeyBehavior.DOUBLE_CLICK)
        hasLazyDouble = key.hasAction(KeyBehavior.LAZY_DOUBLE_CLICK)
        hasPopup = key.popup.isNotEmpty()

        onPress = {
            if (keyboard.firstPressedKeyIndex == -1) keyboard.firstPressedKeyIndex = id
            setPressedState(true)
            key.getCode(KeyBehavior.CLICK).let { keyboardActionListener.onPress(it) }
            showPopupPreview()
        }

        onRelease = { behavior, isFromLongPress ->
            Timber.d("KeyView release: label=${key.getLabel()}, behavior=$behavior, fromLongPress=$isFromLongPress")
            if (isFromLongPress) {
                if (com.osfans.trime.ime.smart.SymbolVariantPopup.release(this)) {
                    setPressedState(false)
                    dismissPopupPreview()
                } else if (hasPopup) {
                    val triggerAction = PopupAction.TriggerAction(id)
                    popup.listener.onPopupAction(triggerAction)
                    triggerAction.outAction?.let { action ->
                        keyboardActionListener.onAction(theme.resolveAction(action))
                        dismissPopupPreview()
                    }
                    setPressedState(false)
                } else if (isRepeatable) {
                    key.getAction(KeyBehavior.CLICK)?.let { processKeyAction(it, KeyBehavior.CLICK) }
                }
            } else {
                when (behavior) {
                    KeyBehavior.CLICK -> {
                        val pressedIdx = keyboard.firstPressedKeyIndex
                        val actionBehavior = if (pressedIdx != -1 && pressedIdx != id) KeyBehavior.COMBO else behavior
                        key.getAction(actionBehavior)?.let { processKeyAction(it, actionBehavior) }
                    }

                    KeyBehavior.DOUBLE_CLICK, KeyBehavior.LAZY_DOUBLE_CLICK,
                    KeyBehavior.SWIPE_UP, KeyBehavior.SWIPE_DOWN, KeyBehavior.SWIPE_LEFT, KeyBehavior.SWIPE_RIGHT,
                    ->
                        if (behavior == KeyBehavior.SWIPE_UP || behavior == KeyBehavior.SWIPE_DOWN) {
                            if (keyboardView.smartFeaturesEnabled && (glidePrefs.enabled || !smartPrefs.allowsFlick(behavior == KeyBehavior.SWIPE_UP))) {
                                setPressedState(false)
                                dismissPopupPreview()
                            } else {
                            val name = bindingName()
                            val binding = if (keyboardView.smartFeaturesEnabled) smartPrefs.binding(name, behavior == KeyBehavior.SWIPE_UP) else null
                            if (binding != null) keyboardActionListener.onSmartAction(binding)
                            else if (keyboardView.smartFeaturesEnabled) smartFlick(behavior == KeyBehavior.SWIPE_UP)
                            else key.getAction(behavior)?.let { processKeyAction(it, behavior) }
                            }
                        } else key.getAction(behavior)?.let { processKeyAction(it, behavior) }

                    else -> {}
                }

                setPressedState(false)
                dismissPopupPreview()
            }
            if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
        }

        onSwipe = { direction ->
            setPressedState(true)
            showPopupPreview(direction)
        }

        onSlide = { delta, _, _ ->
            if (isSlideCursor) {
                when {
                    delta > 0 -> keyboardActionListener.onAction(theme.resolveAction("Right"))
                    delta < 0 -> keyboardActionListener.onAction(theme.resolveAction("Left"))
                }
            } else if (isSlideDelete) {
                val ic = service.currentInputConnection
                when {
                    delta < 0 -> {
                        val beforeText = ic.getTextBeforeCursor(1, 0) ?: ""
                        if (beforeText.isNotEmpty()) {
                            deletedTextBuffer.addFirst(beforeText.toString())
                            ic.deleteSurroundingText(1, 0)
                        }
                    }

                    delta > 0 -> {
                        if (deletedTextBuffer.isNotEmpty()) {
                            ic.commitText(deletedTextBuffer.removeFirst(), 1)
                        }
                    }
                }
            }
        }

        onLongClick = {
            val variants = if (keyboardView.smartFeaturesEnabled) variants() else emptyList()
            if (variants.isNotEmpty()) {
                dismissPopupPreview()
                com.osfans.trime.ime.smart.SymbolVariantPopup.show(
                    this, variants,
                    onCommit = { keyboardActionListener.onSmartAction("text:$it") },
                    initialX = variantDownX, initialY = variantDownY,
                )
                setPressedState(false)
            } else if (keyboardView.smartFeaturesEnabled && (isEmojiKey() || key.getLabel().contains("表情") || key.getLabel() in listOf("☺", "😊", "😀", "☻"))) {
                com.osfans.trime.ime.smart.SymbolVariantPopup.show(this,
                    com.osfans.trime.ime.smart.ReferenceEmojiCatalog.emoticons,
                    onCommit={keyboardActionListener.onSmartAction("text:$it")},
                    initialX=variantDownX, initialY=variantDownY,
                    onExpand={keyboardActionListener.onSmartAction("emoji_expand")})
                setPressedState(false)
                dismissPopupPreview()
            } else if (key.popup.isNotEmpty()) {
                dismissPopupPreview()
                showPopupKeyboard()
            } else if (hasLongPress) {
                key.getAction(KeyBehavior.LONG_CLICK)?.let {
                    processKeyAction(it, KeyBehavior.LONG_CLICK)
                    setPressedState(false)
                    dismissPopupPreview()
                }
            }
        }

        onMove = { x, y, isLongPress ->
            if (isLongPress && !com.osfans.trime.ime.smart.SymbolVariantPopup.move(this, x, y) && hasPopup) {
                popup.listener.onPopupAction(PopupAction.ChangeFocusAction(id, x, y))
            }
        }

        onCancel = {
            com.osfans.trime.ime.smart.SymbolVariantPopup.cancel(this)
            deletedTextBuffer.clear()
            setPressedState(false)
            dismissPopupPreview()
        }
    }

    fun setPressedState(pressed: Boolean) {
        if (keyPressed != pressed) {
            keyPressed = pressed
            if (pressed) {
                key.onPressed()
            } else {
                key.onReleased()
            }
            invalidate()
        }
    }

    internal fun smartTap() {
        key.getAction(KeyBehavior.CLICK)?.let { processKeyAction(it, KeyBehavior.CLICK) }
    }

    internal fun smartFlick(up: Boolean) {
        if (glidePrefs.enabled || !smartPrefs.allowsFlick(up)) return
        val behavior = if (up) KeyBehavior.SWIPE_UP else KeyBehavior.SWIPE_DOWN
        val symbol = key.symbolLabel.trim()
        val mapped = key.keyActions[behavior] ?: if (up) key.keyActions[KeyBehavior.SWIPE_DOWN] else null
        when {
            mapped != null && mapped.commit.isNotEmpty() -> keyboardActionListener.onSmartAction("text:${mapped.commit}")
            mapped != null -> processKeyAction(mapped, behavior)
            symbol.isNotEmpty() && key.getCode(KeyBehavior.CLICK) in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> keyboardActionListener.onSmartAction("text:$symbol")
        }
    }

    private fun processKeyAction(action: KeyAction, behavior: KeyBehavior) {
        Timber.d("processKeyAction: label=${key.getLabel()}, code=${action.code}, type=$behavior")

        if (keyboard.flickSymbolsLocked && (behavior == KeyBehavior.CLICK || behavior == KeyBehavior.COMBO)) {
            if (key.click?.select == "number") {
                keyboard.flickSymbolsLocked = false
                keyboardView.invalidateAllKeys()
                return
            }
            if (key.getCode(KeyBehavior.CLICK) in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                val up = smartPrefs.flickDirection == com.osfans.trime.ime.smart.FlickDirection.UP
                val behavior = if (up) KeyBehavior.SWIPE_UP else KeyBehavior.SWIPE_DOWN
                val mapped = key.keyActions[behavior] ?: if (up) key.keyActions[KeyBehavior.SWIPE_DOWN] else null
                val defaultSymbol = mapped?.commit?.takeIf { it.isNotEmpty() }
                    ?: key.symbolLabel.trim().takeIf { it.isNotEmpty() }
                val symbol = com.osfans.trime.ime.smart.FlickSymbolBinding.lockedText(
                    smartPrefs.binding(bindingName(), up), defaultSymbol)
                if (symbol != null) {
                    if (symbol.isNotEmpty()) keyboardActionListener.onSmartAction("locked_symbol:$symbol")
                    return
                }
            }
        }
        if (keyboard.flickSymbolsLocked && (action.isModifierKey || action.toggle == "ascii_mode")) {
            keyboard.flickSymbolsLocked = false
            keyboardView.invalidateAllKeys()
        }

        if (action.isModifierKey) {
            keyboard.clickModifierKey(
                action.isShiftLock xor (behavior == KeyBehavior.LONG_CLICK),
                action.modifierKeyOnMask,
            )
            keyboardView.invalidateAllKeys()
            return
        }

        keyboardActionListener.onAction(action)

        val hookArrow = if (keyboardView.hookShiftArrow) {
            when (action.code) {
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
                else -> false
            }
        } else {
            false
        }

        if (!hookArrow) {
            if (keyboard.refreshModifier()) {
                keyboardView.invalidateAllKeys()
            }
        }
    }

    private fun showPopupKeyboard() {
        val popupKeys = key.popup
        if (popupKeys.isEmpty()) return

        popup.listener.onPopupAction(
            PopupAction.ShowKeyboardAction(id, popupKeys, bounds),
        )
    }

    private fun showPopupPreview(behavior: KeyBehavior = KeyBehavior.CLICK) {
        if (!keyboardView.popupOnKeyPress) return
        key.getPreviewText(behavior).takeIf { it.isNotEmpty() }?.let { previewText ->
            val context = if (previewText.isIconFont) {
                previewText
            } else {
                String(Character.toChars(previewText.codePointAt(0)))
            }
            popup.listener.onPopupAction(PopupAction.PreviewAction(id, context, bounds))
        }
    }

    private fun dismissPopupPreview() {
        popup.listener.onPopupAction(
            PopupAction.DismissAction(id),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        val desiredWidth = totalWidth + paddingLeft + paddingRight
        val desiredHeight = key.height + paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        boundsValid = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawBackground(canvas, key)

        val reference = theme.isIosStyle
        // Paint from the cached status: drawing a key must not wait for native input work.
        val languageKey = reference && (key.code == KeyEvent.KEYCODE_SPACE || key.click?.toggle == "ascii_mode")
        val asciiMode = keyboard.displayedAsciiMode
            ?: (com.osfans.trime.daemon.RimeDaemon.getFirstSessionOrNull()?.run { statusCached.isAsciiMode } == true)
        val localized = keyboardView.smartFeaturesEnabled && !OfflinePrivacyPolicy.sensitiveEditor &&
            service.currentInputEditorInfo?.inputType?.let {
                it and android.text.InputType.TYPE_MASK_CLASS == android.text.InputType.TYPE_CLASS_TEXT &&
                    it and android.text.InputType.TYPE_MASK_VARIATION != android.text.InputType.TYPE_TEXT_VARIATION_URI
            } == true
        val rawLabel = when {
            flickPreviewLabel != null -> flickPreviewLabel!!
            keyboard.flickSymbolsLocked && key.click?.select == "number" -> "ABC"
            languageKey && key.code == KeyEvent.KEYCODE_SPACE -> ""
            languageKey -> if (asciiMode) "EN" else "中"
            else -> key.getLabel().let {
                if (it == "enter_labels") keyboardView.labelEnter
                else if (it.length == 1 && it[0].isLetter() && smartPrefs.uppercaseLabels) it.uppercase()
                else it
            }
        }
        val label = if (localized) KeyboardPunctuation.localized(rawLabel, asciiMode) else rawLabel

        contentDescription = when (key.getCode(KeyBehavior.CLICK)) {
            KeyEvent.KEYCODE_SPACE -> "空格"
            KeyEvent.KEYCODE_DEL -> "退格"
            KeyEvent.KEYCODE_LANGUAGE_SWITCH -> if (reference && key.click?.select.isNullOrEmpty()) "切换中英文" else "切换输入法"
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift"
            else -> if (isEmojiKey()) "表情" else label
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        val labelTop = if (label.isNotEmpty()) drawLabel(canvas, label) else Float.POSITIVE_INFINITY

        if (reference && key.code == KeyEvent.KEYCODE_SPACE) {
            textPaint.apply {
                color = key.getSymbolColor()
                textSize = dp(12f)
                typeface = theme.fonts.key
                textAlign = Paint.Align.RIGHT
                clearShadowLayer()
            }
            canvas.drawText(if (asciiMode) "EN" else "中",
                width - paddingRight - dp(10f), height - paddingBottom - dp(6f) - textPaint.descent(), textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }

        val direction = smartPrefs.flickDirection
        val showFlick = !glidePrefs.enabled && direction != FlickDirection.OFF
        val binding = if (keyboardView.smartFeaturesEnabled && showFlick) {
            smartPrefs.binding(bindingName(), direction == FlickDirection.UP)
        } else null
        ViewCompat.setStateDescription(this, KeyBindingLabels.actionHint(binding, direction == FlickDirection.UP))
        val defaultSymbol = key.symbolLabel
        if (keyboardView.smartFeaturesEnabled && showFlick && !keyboardView.hideKeySymbol &&
            (binding != null || defaultSymbol.isNotEmpty() && !defaultSymbol.isIconFont && '\n' !in defaultSymbol)) {
            val area = RectF(
                paddingLeft + dp(3f), paddingTop + dp(1.5f),
                width - paddingRight - dp(3f), minOf(labelTop - dp(2f), paddingTop + (height - paddingTop - paddingBottom) * .27f),
            )
            keyboardView.bindingHint.draw(canvas, binding, defaultSymbol, area,
                sp(key.symbolTextSize.takeIf { it > 0f } ?: keyboardView.symbolTextSize),
                key.getSymbolColor(), theme.fonts.symbol,
                asciiMode.takeIf { localized })
        } else if ((!keyboardView.smartFeaturesEnabled || showFlick) && defaultSymbol.isNotEmpty()) {
            drawSymbol(canvas, defaultSymbol)
        }

        val hint = key.hint
        if (hint.isNotEmpty()) {
            drawSymbol(canvas, hint, isTop = false)
        }
    }

    private fun drawBackground(canvas: Canvas, k: Key) {
        val bg = k.getBackgroundDrawable() ?: return

        if (bg is GradientDrawable) {
            k.roundCorner.takeIf { it > 0f }?.let { bg.cornerRadius = dp(it) }
            k.keyBorder.takeIf { it > 0 }?.let { bg.setStroke(dp(it), k.getBorderColor()) }
        }

        bg.setBounds(
            paddingLeft,
            paddingTop,
            width - paddingRight,
            height - paddingBottom,
        )
        bg.draw(canvas)
    }

    private fun drawLabel(canvas: Canvas, label: String): Float {
        val dimLetter = keyboard.flickSymbolsLocked && flickPreviewLabel == null &&
            key.getCode(KeyBehavior.CLICK) in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z &&
            label.length == 1 && label[0].isLetter()
        val textColor = if (dimLetter) androidx.core.graphics.ColorUtils.setAlphaComponent(key.getTextColor(), 102)
            else key.getTextColor()
        val reference = theme.isIosStyle && !label.isIconFont
        if (reference && key.getCode(KeyBehavior.CLICK) == KeyEvent.KEYCODE_ENTER && label in setOf("↵", "⏎", "⤶")) {
            val contentWidth = (width - paddingLeft - paddingRight).toFloat()
            val contentHeight = (height - paddingTop - paddingBottom).toFloat()
            com.osfans.trime.ime.smart.ReferenceReturnIcon.draw(
                canvas, paddingLeft + contentWidth / 2f, paddingTop + contentHeight / 2f,
                minOf(dp(29f), contentWidth * .7f, contentHeight * .76f), textColor,
            )
            return paddingTop + contentHeight * .12f
        }
        val display = if (reference) com.osfans.trime.ime.smart.ReferenceDisplayPrefs(context) else null
        val textSize = sp(key.keyTextSize.takeIf { it > 0 } ?: if (label.length > 1 && !label.isIconFont) keyboardView.keyLongTextSize else keyboardView.keyTextSize) * ((display?.keyTextSize ?: 24) / 24f)

        if (label.isIconFont) {
            drawIcon(canvas, label, textSize.toInt(), textColor, key.keyTextOffsetX, key.keyTextOffsetY)
            return (height - paddingTop - paddingBottom) / 2f + paddingTop + sp(key.keyTextOffsetY) - textSize / 2f
        } else {
            textPaint.apply {
                color = textColor
                this.textSize = textSize
                textAlign = Paint.Align.CENTER
                typeface = if (display != null) android.graphics.Typeface.create(theme.fonts.key, if (display.keyBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL) else theme.fonts.key
                clearShadowLayer()
            }

            if (reference) {
                // Landscape rows are shorter, and system font scaling can exceed a key's face.
                // Fit the label to the actual drawn key, not the screen or its expanded hit area.
                val availableWidth = (width - paddingLeft - paddingRight - dp(4f)).coerceAtLeast(1f)
                val availableHeight = ((height - paddingTop - paddingBottom) * .76f).coerceAtLeast(1f)
                val metrics = textPaint.fontMetrics
                val fit = minOf(1f, availableWidth / textPaint.measureText(label).coerceAtLeast(1f),
                    availableHeight / (metrics.descent - metrics.ascent).coerceAtLeast(1f))
                textPaint.textSize *= fit
            }

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft
            val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop
            val fontMetrics = textPaint.fontMetrics
            val adjustmentY = -(fontMetrics.ascent + fontMetrics.descent) / 2f

            val offsetX = sp(key.keyTextOffsetX)
            val offsetY = sp(key.keyTextOffsetY)
            val x = if (reference) (centerX + offsetX).coerceIn(
                paddingLeft + textPaint.measureText(label) / 2f,
                width - paddingRight - textPaint.measureText(label) / 2f,
            ) else centerX + offsetX
            val baseline = if (reference) (centerY + adjustmentY + offsetY).coerceIn(
                paddingTop - fontMetrics.ascent,
                height - paddingBottom - fontMetrics.descent,
            ) else centerY + adjustmentY + offsetY
            canvas.drawText(label, x, baseline, textPaint)
            textPaint.getTextBounds(label, 0, label.length, labelInk)
            return baseline + labelInk.top
        }
    }

    private fun drawIcon(
        canvas: Canvas,
        iconName: String,
        size: Int,
        color: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        isTop: Boolean? = null,
    ) {
        val halfSize = size / 2

        val cmdName = iconName.toIconName()
        val icon = if (cachedIconName == cmdName) {
            cachedIcon!!
        } else {
            IconicsDrawable(context, cmdName).apply {
                sizeDp = size
            }.also {
                cachedIcon = it
                cachedIconName = cmdName
            }
        }

        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)

        val centerY = when (isTop) {
            true -> paddingTop + halfSize + sp(offsetY)
            false -> height - paddingBottom - size + sp(offsetY)
            null -> (height - paddingTop - paddingBottom) / 2f + paddingTop + sp(offsetY)
        }

        icon.setBounds(
            (centerX - halfSize).toInt(),
            (centerY - halfSize).toInt(),
            (centerX + halfSize).toInt(),
            (centerY + halfSize).toInt(),
        )
        icon.draw(canvas)
    }

    private fun drawSymbol(canvas: Canvas, text: String, isTop: Boolean = true) {
        if(isTop && keyboardView.smartFeaturesEnabled && glidePrefs.enabled) return
        if (isTop && keyboardView.hideKeySymbol) return
        if (!isTop && keyboardView.hideKeyHint) return

        val lockedSymbol = keyboard.flickSymbolsLocked &&
            key.getCode(KeyBehavior.CLICK) in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
        val textColor = if (lockedSymbol) 0xff1c1c1e.toInt() else key.getSymbolColor()
        val textSize = sp(key.symbolTextSize.takeIf { it > 0f } ?: keyboardView.symbolTextSize)
        val offsetX = if (isTop) key.keySymbolOffsetX else key.keyHintOffsetX
        val offsetY = if (isTop) key.keySymbolOffsetY else key.keyHintOffsetY

        if (text.isIconFont) {
            drawIcon(canvas, text, textSize.toInt(), textColor, offsetX, offsetY, isTop)
        } else {
            symbolPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = if (lockedSymbol) android.graphics.Typeface.create(theme.fonts.symbol, android.graphics.Typeface.BOLD)
                    else theme.fonts.symbol
            }

            val lines = text.split("\n")
            val fontMetrics = symbolPaint.fontMetrics
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val totalHeight = lineHeight * lines.size

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)
            val startY = if (isTop) {
                paddingTop - fontMetrics.top + sp(offsetY) - (totalHeight - lineHeight) / 2
            } else {
                height - paddingBottom - fontMetrics.bottom + sp(offsetY) - (totalHeight - lineHeight) / 2
            }

            for (i in lines.indices) {
                val lineY = startY + lineHeight * i
                canvas.drawText(lines[i], centerX, lineY, symbolPaint)
            }
        }
    }
}
