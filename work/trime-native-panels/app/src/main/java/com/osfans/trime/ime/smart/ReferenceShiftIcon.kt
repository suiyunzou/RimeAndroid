// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * iOS-style filled shift arrow for the active (uppercase) state. The outline
 * state keeps the theme font glyph; this filled silhouette matches how Apple
 * and Gboard mark an engaged shift key.
 */
object ReferenceShiftIcon {
    fun drawFilled(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
    ) {
        val unit = size / 32f
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL_AND_STROKE
                strokeWidth = 1.6f * unit
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
        // Arrowhead: rounded tip, base wider than the stem, like SF shift.fill.
        val head =
            Path().apply {
                moveTo(cx, cy - 15.5f * unit)
                lineTo(cx + 13f * unit, cy - 3.5f * unit)
                lineTo(cx - 13f * unit, cy - 3.5f * unit)
                close()
            }
        canvas.drawPath(head, paint)
        // Stem reaching below the head base with slightly rounded feet.
        canvas.drawRoundRect(
            RectF(cx - 4.6f * unit, cy - 4.5f * unit, cx + 4.6f * unit, cy + 14.5f * unit),
            2.2f * unit,
            2.2f * unit,
            paint,
        )
    }
}
