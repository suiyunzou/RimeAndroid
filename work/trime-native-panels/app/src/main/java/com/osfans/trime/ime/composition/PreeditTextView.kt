/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.TextView
import androidx.core.math.MathUtils

@SuppressLint("AppCompatCustomView")
class PreeditTextView
@JvmOverloads
constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
) : TextView(context, attributeSet) {
    var compactPinyinLine = false
    private val pinyinLineBounds = Rect()
    private var metricsTextSize = -1f
    private var metricsTypeface: Typeface? = null

    private fun updatePinyinLineBounds() {
        if (metricsTextSize == textSize && metricsTypeface === typeface) return
        // Stable envelope: tones, ascenders, descenders, separators and correction arrows.
        // Selected Chinese segments may also occur in an otherwise pinyin composition.
        val sample = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'’ ·|→:āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜĀÁǍÀĒÉĚÈĪÍǏÌŌÓǑÒŪÚǓÙǕǗǙǛ汉語测试"
        paint.getTextBounds(sample, 0, sample.length, pinyinLineBounds)
        metricsTextSize = textSize
        metricsTypeface = typeface
    }

    val compactLineHeight: Int
        get() { updatePinyinLineBounds(); return pinyinLineBounds.height() + 2 }

    override fun onDraw(canvas: Canvas) {
        if (!compactPinyinLine) { super.onDraw(canvas); return }
        updatePinyinLineBounds()
        val textLayout = layout ?: return
        // TextView reserves the full font ascent even with includeFontPadding=false.
        // Translate its baseline into the measured envelope instead of clipping that line box.
        val targetBaseline = (height - pinyinLineBounds.height()) / 2f - pinyinLineBounds.top
        val checkpoint = canvas.save()
        paint.color = currentTextColor
        paint.drawableState = drawableState
        canvas.translate(compoundPaddingLeft.toFloat() - scrollX, targetBaseline - textLayout.getLineBaseline(0))
        textLayout.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    var onMoveCursor: ((Int) -> Unit)? = null
    var displayOffsetToByteCaret: ((Int) -> Int)? = null
    private var lastTapOffset = -1
    private var newCursorPos = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val textString = text.toString()
                lastTapOffset = MathUtils.clamp(
                    getOffsetForPosition(x, y),
                    0,
                    textString.length,
                )
                val bytes = textString.substring(0, lastTapOffset).toByteArray()
                newCursorPos = displayOffsetToByteCaret?.invoke(lastTapOffset) ?: bytes.size
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (newCursorPos >= 0) onMoveCursor?.invoke(newCursorPos)
                lastTapOffset = -1
                newCursorPos = -1
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                lastTapOffset = -1
                newCursorPos = -1
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
