// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** The miniature uses the live renderer, so changing a style cannot leave a stale pictogram. */
class WheelAppearancePreview(context: Context, private val prefs: SmartInputPrefs,
    private val dark: Boolean, private val setting: String, private val value: String) : View(context) {
    private val density = resources.displayMetrics.density
    private val wheel = RadialMenuView(context).apply {
        previewDark = dark
        isHapticFeedbackEnabled = false
        setPreviewMode(true)
    }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val virtualWidth = (300 * density).toInt()
        val virtualHeight = (174 * density).toInt()
        wheel.measure(MeasureSpec.makeMeasureSpec(virtualWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(virtualHeight, MeasureSpec.EXACTLY))
        wheel.layout(0, 0, virtualWidth, virtualHeight)
        wheel.configureAppearance(
            if (setting == "wheel_icon_visibility") value else prefs.wheelIconVisibility,
            if (setting == "wheel_focus_style") value else prefs.wheelFocusStyle,
            if (setting == "wheel_cursor_placement") value else prefs.wheelCursorPlacement,
            if (setting == "wheel_motion_style") value else prefs.wheelMotionStyle,
        )
        wheel.configurePreview(prefs.wheelSlots, if (setting == "wheel_style") value else prefs.wheelStyle, prefs.originGlow, prefs.followFinger)
        val originX = if (setting == "wheel_origin_placement" && value == "finger") 128f else 150f
        wheel.begin(originX * density, 150 * density, prefs.wheelSlots,
            if (setting == "wheel_style") value else prefs.wheelStyle, prefs.originGlow, prefs.followFinger)
        if (setting != "wheel_style") wheel.update(originX * density, 95 * density)
    }
    override fun onDraw(canvas: Canvas) {
        if (wheel.width == 0) return
        val scale = minOf(width.toFloat() / wheel.width, height.toFloat() / wheel.height)
        canvas.save()
        canvas.translate((width-wheel.width*scale)/2, (height-wheel.height*scale)/2)
        canvas.scale(scale, scale)
        if (setting == "wheel_origin_placement") {
            pen.color = if (dark) 0xff48484b.toInt() else 0xffd3d4da.toInt()
            canvas.drawRoundRect(RectF(105*density, 139*density, 195*density, 160*density), 4*density, 4*density, pen)
        }
        wheel.draw(canvas)
        canvas.restore()
    }
}
