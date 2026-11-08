// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.UnderlineSpan

object CorrectionStyleSpans {
    private val errorColor = 0xffb43b44.toInt()
    fun selected(context: Context): String = context.getSharedPreferences("smart_input",Context.MODE_PRIVATE)
        .getString("correction_style","underline")?.takeIf { it in CorrectionStyle.labels } ?: "underline"
    fun render(plan: CorrectionStyle.Plan): CharSequence = SpannableString(plan.text).apply {
        plan.marks.forEach { mark ->
            val span = when(mark.kind) {
                CorrectionStyle.Kind.UNDERLINE -> UnderlineSpan()
                CorrectionStyle.Kind.STRIKE -> DiagonalStrikeSpan(errorColor)
                CorrectionStyle.Kind.ERROR -> ForegroundColorSpan(errorColor)
                CorrectionStyle.Kind.CORRECT -> ForegroundColorSpan(0xff285fab.toInt())
                CorrectionStyle.Kind.BACKGROUND -> BackgroundColorSpan(0x55b58ccc)
            }
            setSpan(span,mark.start,mark.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    private class DiagonalStrikeSpan(private val color: Int) : ReplacementSpan() {
        override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int = kotlin.math.ceil(paint.measureText(text,start,end).toDouble()).toInt()
        override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint) {
            val width=paint.measureText(text,start,end)
            val originalColor=paint.color
            val stroke=paint.strokeWidth
            try {
                // ReplacementSpan owns both the glyph and slash. Its draw paint
                // need not contain an overlapping ForegroundColorSpan's color.
                paint.color=color
                canvas.drawText(text,start,end,x,y.toFloat(),paint)
                paint.strokeWidth=(paint.textSize*.045f).coerceAtLeast(1f)
                canvas.drawLine(x,y+paint.descent()*.4f,x+width,y+paint.ascent()*.8f,paint)
            } finally {
                paint.color=originalColor
                paint.strokeWidth=stroke
            }
        }
    }
}
