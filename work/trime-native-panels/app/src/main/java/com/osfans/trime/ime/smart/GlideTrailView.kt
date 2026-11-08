// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Non-interactive local trail. It stores no strokes once the pointer is released. */
class GlideTrailView(context: Context) : View(context) {
    private val path = Path()
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 45, 145, 220)
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f * resources.displayMetrics.density
    }
    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; visibility = GONE }
    fun begin(x: Float, y: Float) { path.reset(); path.moveTo(x, y); visibility = VISIBLE; bringToFront(); invalidate() }
    fun move(x: Float, y: Float) { if (x.isFinite() && y.isFinite()) { path.lineTo(x, y); invalidate() } }
    fun clear() { path.reset(); visibility = GONE; invalidate() }
    override fun onDraw(canvas: Canvas) { canvas.drawPath(path, pen) }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
}
