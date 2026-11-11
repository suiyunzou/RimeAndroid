// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.osfans.trime.data.theme.ColorManager

/**
 * Small frosted-glass strip shown at the keyboard's top edge when the
 * bottom-right key is long-pressed: one tap rebinds that key to the chosen
 * wheel function and the strip collapses on its own.
 */
object CornerKeyMenu {
    private var current: PopupWindow? = null

    /** The rebindable choices: the offline voice input plus the space-wheel functions. */
    fun options(prefs: SmartInputPrefs): List<String> = listOf("voice") + prefs.wheelSlots

    fun show(anchor: View, onSelect: (String) -> Unit): Boolean {
        if (!anchor.isAttachedToWindow) return false
        current?.dismiss()
        val prefs = SmartInputPrefs(anchor.context)
        val choices = options(prefs)
        val density = anchor.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val keyboardBackColor = runCatching { ColorManager.currentScope()?.colors?.keyboardBackColor }
            .getOrNull()
            ?: runCatching { ColorManager.getColor("keyboard_back_color") }.getOrDefault(Color.WHITE)
        val keyTextColor = runCatching { ColorManager.getColor("key_text_color") }.getOrDefault(Color.BLACK)
        val keyboard = generateSequence(anchor.parent) { it.parent }.filterIsInstance<View>()
            .firstOrNull { it.javaClass.simpleName == "KeyboardView" }
        val width = (keyboard?.width ?: anchor.rootView.width).coerceAtLeast(dp(200))
        val row = LinearLayout(anchor.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val cellWidth = (width - dp(8)) / choices.size
        choices.forEach { id ->
            val label = if (id == "voice") "语音" else SmartInputPrefs.LABELS[id] ?: id
            val cell =
                LinearLayout(anchor.context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    contentDescription = "右下角功能设为$label"
                    background =
                        GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            cornerRadius = dp(12).toFloat()
                        }
                    setOnClickListener {
                        current?.dismiss()
                        onSelect(id)
                    }
                    addView(
                        TextView(anchor.context).apply {
                            text = label
                            textSize = 14f
                            setTextColor(keyTextColor)
                            gravity = Gravity.CENTER
                        },
                        LinearLayout.LayoutParams(-1, dp(30)),
                    )
                }
            cell.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        cell.background = GradientDrawable().apply {
                            setColor(ColorUtils.blendARGB(keyboardBackColor, keyTextColor, 0.17f))
                            cornerRadius = dp(12).toFloat()
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        cell.background = GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            cornerRadius = dp(12).toFloat()
                        }
                        val inside = event.x >= 0f && event.x <= cell.width.toFloat() &&
                            event.y >= 0f && event.y <= cell.height.toFloat()
                        if (inside) {
                            current?.dismiss()
                            onSelect(id)
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        cell.background = GradientDrawable().apply {
                            setColor(Color.TRANSPARENT)
                            cornerRadius = dp(12).toFloat()
                        }
                        true
                    }
                    else -> true
                }
            }
            row.addView(cell, LinearLayout.LayoutParams(cellWidth, -1))
        }
        // Frosted glass: translucent keyboard surface, hairline stroke, soft elevation.
        val window =
            PopupWindow(row, width, dp(44), false).apply {
                isOutsideTouchable = true
                isTouchable = true
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
                setBackgroundDrawable(
                    GradientDrawable().apply {
                        setColor(ColorUtils.setAlphaComponent(keyboardBackColor, 216))
                        cornerRadius = dp(20).toFloat()
                        setStroke(dp(1), (keyTextColor and 0x00FFFFFF) or 0x2E000000)
                    },
                )
                elevation = dp(3).toFloat()
            }
        current = window
        val dim =
            ColorDrawable(ColorUtils.setAlphaComponent(keyboardBackColor, 110)).apply {
                setBounds(0, 0, keyboard?.width ?: 0, ((keyboard?.height ?: 0) - dp(36)).coerceAtLeast(0))
            }
        keyboard?.overlay?.add(dim)
        window.setOnDismissListener {
            keyboard?.overlay?.remove(dim)
            if (current === window) current = null
        }
        return try {
            val keyboardTop =
                keyboard?.let { view -> IntArray(2).also { view.getLocationInWindow(it) }[1] }
                    ?: IntArray(2).also { anchor.getLocationInWindow(it) }[1]
            window.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, 0, keyboardTop.coerceAtLeast(0))
            true
        } catch (error: Exception) {
            keyboard?.overlay?.remove(dim)
            if (current === window) current = null
            false
        }
    }

    fun dismiss() {
        current?.dismiss()
    }
}
