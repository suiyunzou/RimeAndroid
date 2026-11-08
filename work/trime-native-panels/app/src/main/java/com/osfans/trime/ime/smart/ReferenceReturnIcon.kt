package com.osfans.trime.ime.smart

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/** An optical-size return arrow; font glyph side bearings must not shrink this key. */
object ReferenceReturnIcon {
    fun draw(canvas: Canvas, cx: Float, cy: Float, width: Float, color: Int) {
        val unit = width / 32f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * unit
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        val path = Path().apply {
            moveTo(cx + 7 * unit, cy - 12 * unit)
            lineTo(cx + 15 * unit, cy - 12 * unit)
            lineTo(cx + 15 * unit, cy - 1 * unit)
            quadTo(cx + 15 * unit, cy + 3 * unit, cx + 11 * unit, cy + 3 * unit)
            lineTo(cx - 15 * unit, cy + 3 * unit)
            moveTo(cx - 7 * unit, cy - 5 * unit)
            lineTo(cx - 15 * unit, cy + 3 * unit)
            lineTo(cx - 7 * unit, cy + 11 * unit)
        }
        canvas.drawPath(path, paint)
    }
}
