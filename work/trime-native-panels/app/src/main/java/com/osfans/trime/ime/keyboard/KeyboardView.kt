/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.children
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.smart.KeyBindingHint

// TODO: move layout calculation responsibilities from Keyboard to KeyboardView using ConstraintLayout
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val theme: Theme,
    private val keyboard: Keyboard,
    val popup: PopupDelegate,
    val service: TrimeInputMethodService,
    private val keyboardActionListener: KeyboardActionListener,
    private val enterKeyDisplay: EnterKeyDisplayDelegate,
) : FrameLayout(context) {

    private val keys get() = keyboard.keys
    var smartFeaturesEnabled = true
    internal val bindingHint by lazy { KeyBindingHint(context) }

    private val smartTouch by lazy {
        com.osfans.trime.ime.smart.SmartKeyboardPointerDispatcher(this, service, keys, keyboardActionListener)
    }

    /** Enabled only by KeyboardWindow for the two personal settings boards. */
    var settingsPage: Int = -1
        set(value) {
            field = value
            children.filterIsInstance<KeyView>().forEach { it.allowSwipeDetection = value < 0 }
        }
    var suppressSettingsTouches = false
    private var blockedSettingsGesture = false
    private val settingsSwipe = SettingsPageSwipe(ViewConfiguration.get(context).scaledTouchSlop.toFloat())

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (settingsPage >= 0) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) blockedSettingsGesture = suppressSettingsTouches
            if (suppressSettingsTouches || blockedSettingsGesture) {
                // A touch that began over a moving page stays canceled even
                // when the animation finishes before the finger is released.
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    blockedSettingsGesture = false
                }
                return true
            }
        }
        if (smartFeaturesEnabled && settingsPage < 0 && smartTouch.dispatch(event)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (settingsPage < 0) return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> settingsSwipe.begin(event.x, event.y)
            MotionEvent.ACTION_POINTER_DOWN -> settingsSwipe.cancel()
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> settingsSwipe.move(event.x, event.y)
            MotionEvent.ACTION_CANCEL -> settingsSwipe.cancel()
        }
        if (settingsSwipe.intercept) {
            // ViewGroup sends ACTION_CANCEL to the originating card. Clear the
            // keyboard's combo anchor too; the destination never inherits it.
            keyboard.firstPressedKeyIndex = -1
            return true
        }
        return super.onInterceptTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (settingsPage < 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> settingsSwipe.begin(event.x, event.y)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> settingsSwipe.cancel()
            MotionEvent.ACTION_MOVE -> settingsSwipe.move(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                val direction = settingsSwipe.release(event.x, event.y)
                val action = when {
                    direction < 0 && settingsPage == 0 -> "CleanSettingsPage2"
                    direction > 0 && settingsPage == 1 -> "CleanSettingsPage1"
                    else -> null
                }
                action?.let { keyboardActionListener.onAction(theme.resolveAction(it)) }
            }
        }
        // Blank gaps also belong to the safe paging surface, not the app below.
        return true
    }

    internal val labelEnter: String
        get() = enterKeyDisplay.keyLabel
    internal val keyTextSize = theme.style.keyTextSize
    internal val keyLongTextSize = theme.style.keyLongTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val symbolTextSize = theme.style.symbolTextSize.takeIf { it > 0 } ?: keyTextSize
    internal val popupOnKeyPress by AppPrefs.defaultInstance().keyboard.popupOnKeyPress
    internal val hookShiftArrow: Boolean by AppPrefs.defaultInstance().keyboard.hookShiftArrow
    internal val hideKeySymbol: Boolean by AppPrefs.defaultInstance().keyboard.hideKeySymbol
    internal val hideKeyHint: Boolean by AppPrefs.defaultInstance().keyboard.hideKeyHint

    init {
        setWillNotDraw(false)
        buildKeyViews()
    }

    private fun buildKeyViews() {
        removeAllViews()

        keys.forEachIndexed { index, key ->
            val keyView = createKeyView(index, key)
            addView(keyView)
        }
    }

    private fun createKeyView(index: Int, key: Key): KeyView = KeyView(context, theme, key, keyboard, this, keyboardActionListener).apply {
        id = index

        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        layoutParams = LayoutParams(totalWidth, key.height)

        translationX = (key.x - key.extraWidthLeft).toFloat()
        translationY = key.y.toFloat()

        setPadding(
            keyboard.horizontalGap / 2 + key.extraWidthLeft,
            keyboard.verticalGap / 2,
            keyboard.horizontalGap / 2 + key.extraWidthRight,
            keyboard.verticalGap / 2,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fullWidth = keyboard.minWidth + paddingLeft + paddingRight
        val fullHeight = keyboard.height + paddingTop + paddingBottom

        val measuredWidth = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            fullWidth,
        )

        measureChildren(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredWidth, fullHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
    }

    fun invalidateAllKeys() {
        children.forEach { it.invalidate() }
    }

    fun invalidateKeyByIndex(index: Int) {
        getChildAt(index)?.invalidate()
    }

    val isCapsOn: Boolean
        get() = keyboard.mShiftKey?.isOn == true

    fun onDetach() {
        smartTouch.cancel()
        if (settingsPage >= 0) {
            // A programmatic page change can detach a still-held card. Stop
            // its long-press job before the old view becomes an overlay/cache.
            val now = SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            settingsSwipe.cancel()
            keyboard.firstPressedKeyIndex = -1
            blockedSettingsGesture = false
        }
        popup.dismissAll()
    }
}
