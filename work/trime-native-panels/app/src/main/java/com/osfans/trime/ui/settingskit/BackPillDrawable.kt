/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.content.Context

/**
 * Rounded 「‹ 返回」 capsule used as the navigation icon of the redesigned top bar.
 *
 * The reference design replaces the old Android back arrow with a white (dark #2C2C2E in dark
 * mode) pill that carries the word 返回. A plain vector cannot draw text, so the pill is drawn
 * here with [Paint]; the toolbar gives it its intrinsic size.
 */
internal class BackPillDrawable(
    context: Context,
    palette: Palette,
) : Drawable() {
    private val density = context.resources.displayMetrics.density

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (palette.dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
        }

    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = palette.divider
        }

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.ink
            textSize = 16f * density
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }

    override fun getIntrinsicWidth(): Int = (88 * density).toInt()

    override fun getIntrinsicHeight(): Int = (40 * density).toInt()

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val inset = 2f * density
        val rect = RectF(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset,
        )
        val radius = rect.height() / 2f
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        canvas.drawRoundRect(rect, radius, radius, borderPaint)
        val baseline = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("‹ 返回", rect.centerX(), baseline, textPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        borderPaint.alpha = alpha
        textPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
