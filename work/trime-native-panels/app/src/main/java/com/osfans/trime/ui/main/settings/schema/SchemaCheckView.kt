/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings.schema

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import com.osfans.trime.ui.settingskit.Palette
import com.osfans.trime.ui.settingskit.SettingsPalette
import kotlin.math.min

/**
 * 参考图里方案多选模式使用的圆形勾选控件。
 *
 * 只重绘外观，接口与 `CheckBox` 保持一致：[isChecked]、[toggle] 与
 * [setOnCheckedChangeListener]，因此 adapter 里的选择逻辑不用改写。
 */
class SchemaCheckView(
    private val ctxRef: Context,
) : View(ctxRef) {
    private var checkedState = false

    private var palette: Palette = Palette.of(ctxRef)

    private var onCheckedChangeListener: ((View, Boolean) -> Unit)? = null

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val checkPath = Path()

    var isChecked: Boolean
        get() = checkedState
        set(value) {
            if (checkedState == value) return
            checkedState = value
            updateDescription()
            invalidate()
            onCheckedChangeListener?.invoke(this, value)
        }

    init {
        isClickable = true
        isFocusable = true
        updateDescription()
        setOnClickListener { toggle() }
        refreshRipple()
    }

    /** 与 `CompoundButton.toggle()` 同名，adapter 里的调用保持不变。 */
    fun toggle() {
        isChecked = !checkedState
    }

    fun setOnCheckedChangeListener(listener: ((View, Boolean) -> Unit)?) {
        onCheckedChangeListener = listener
    }

    fun applyPalette(palette: Palette) {
        this.palette = palette
        refreshRipple()
        invalidate()
    }

    private fun updateDescription() {
        contentDescription = if (checkedState) "已选中" else "未选中"
    }

    private fun refreshRipple() {
        val ripple = 0x1F000000 or (palette.ink and 0x00FFFFFF)
        val mask =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
        background = RippleDrawable(ColorStateList.valueOf(ripple), null, mask)
    }

    private fun dp(value: Float): Float = value * ctxRef.resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = size / 2f - dp(1f)
        if (checkedState) {
            fillPaint.color = SettingsPalette.SWITCH_ON_COLOR
            canvas.drawCircle(cx, cy, radius, fillPaint)
            strokePaint.strokeWidth = dp(2f)
            strokePaint.color = Color.WHITE
            checkPath.reset()
            checkPath.moveTo(cx - size * 0.24f, cy + size * 0.02f)
            checkPath.lineTo(cx - size * 0.06f, cy + size * 0.19f)
            checkPath.lineTo(cx + size * 0.25f, cy - size * 0.18f)
            canvas.drawPath(checkPath, strokePaint)
        } else {
            strokePaint.strokeWidth = dp(1.5f)
            strokePaint.color = palette.chevron
            canvas.drawCircle(cx, cy, radius, strokePaint)
        }
    }
}
