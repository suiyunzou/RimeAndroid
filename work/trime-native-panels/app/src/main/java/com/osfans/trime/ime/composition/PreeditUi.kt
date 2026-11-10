/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.graphics.ColorUtils
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.smart.CorrectionPresentation
import com.osfans.trime.ime.smart.CorrectionStyle
import com.osfans.trime.ime.smart.CorrectionStyleSpans
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.view
import splitties.dimensions.dp

open class PreeditUi(
    final override val ctx: Context,
    private val scope: ThemeScope,
    private val setupPreeditView: (TextView.() -> Unit)? = null,
    private val onMoveCursor: ((Int) -> Unit)? = null,
) : Ui {
    private val theme: Theme
        get() = scope.theme

    // Read at use time so a scheme switch re-renders with the new colors.
    private val textColor: Int get() = scope.colors.textColor
    private val highlightTextColor: Int get() = scope.colors.hilitedTextColor

    val preedit =
        view(::PreeditTextView) {
            setTextColor(textColor)
            textSize = theme.preedit.foreground.fontSize
            includeFontPadding = false
            if (theme.isIosStyle) {
                isSingleLine = true
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            typeface = theme.fonts.text
            setupPreeditView?.invoke(this)
            onMoveCursor = this@PreeditUi.onMoveCursor
        }

    private val tailSuggestion = TextView(ctx).apply {
        visibility = View.GONE
        includeFontPadding = false
        gravity = Gravity.CENTER
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = ctx.dp(160)
        setPadding(ctx.dp(7), 0, ctx.dp(7), 0)
        isFocusable = true
        refreshTailColors(this)
    }

    override val root =
        object : LinearLayout(ctx) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

            init {
                id = View.generateViewId()
                orientation = HORIZONTAL
                if (theme.isIosStyle) {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(preedit, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                } else add(preedit, lParams())
                addView(tailSuggestion, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            }
        }

    fun showTailSuggestion(text: String?, action: (() -> Unit)?) = tailSuggestion.run {
        if (text == null || action == null) {
            visibility = View.GONE
            setOnClickListener(null)
            this.text = ""
        } else {
            this.text = ctx.getString(R.string.tail_completion_suggestion, text)
            setOnClickListener { action() }
            visibility = View.VISIBLE
        }
    }

    private fun refreshTailColors(view: TextView) {
        view.setTextColor(highlightTextColor)
        view.background = GradientDrawable().apply {
            cornerRadius = ctx.dp(5).toFloat()
            setColor(ColorUtils.setAlphaComponent(highlightTextColor, 25))
        }
    }

    private fun CompositionProto.toSpannedString() = buildSpannedString {
        if (!preedit.isNullOrEmpty()) {
            append(preedit)
            setSpan(ForegroundColorSpan(highlightTextColor), selStart, selEnd, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
    }

    var visible = false
        private set

    /** Re-applies the text color after a scheme switch. */
    fun refreshColors() {
        preedit.setTextColor(textColor)
        refreshTailColors(tailSuggestion)
    }

    private fun updateTextView(
        str: CharSequence,
        visible: Boolean,
    ) = preedit.run {
        text = str
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun update(composition: CompositionProto, correction: CorrectionPresentation.Hint? = null) {
        preedit.displayOffsetToByteCaret = null
        val plan = correction?.takeIf { CorrectionStyle.matchesPreedit(it.original, composition.preedit.orEmpty()) }
            ?.let { CorrectionStyle.plan(it.original, it.corrected, CorrectionStyleSpans.selected(ctx)) }
        if (plan != null && composition.length > 0) {
            visible = true
            preedit.displayOffsetToByteCaret = { offset -> plan.byteOffset(offset, correction!!.original) }
            updateTextView(CorrectionStyleSpans.render(plan), true)
            return
        }
        val string = composition.toSpannedString()
        val cursorPos = composition.cursorPos
        val hasPreedit = composition.length > 0
        visible = hasPreedit
        if (!visible) {
            updateTextView("", false)
            return
        }
        val stringWithCursor =
            if (cursorPos == 0 || cursorPos == string.length) {
                string
            } else {
                buildSpannedString {
                    if (cursorPos > 0) append(string, 0, cursorPos)
                    append(string, cursorPos, string.length)
                }
            }
        updateTextView(stringWithCursor, true)
    }
}
