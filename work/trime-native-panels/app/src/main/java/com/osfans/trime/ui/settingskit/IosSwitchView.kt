/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.settingskit

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.ColorInt

/**
 * Self drawn iOS style switch: a capsule track with a white knob, ~200 ms animation, entirely
 * drawn by this view so it looks identical on every API level.
 *
 * ```kotlin
 * val switch = IosSwitchView(context)
 * switch.applyPalette(palette)
 * switch.isChecked = prefs.enabled
 * switch.setOnCheckedChangeListener { checked -> prefs.enabled = checked }
 * ```
 *
 * Clicking the switch flips [isChecked] with animation and notifies the listener, matching the
 * contract of `android.widget.CompoundButton`.
 */
class IosSwitchView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : View(context, attrs, defStyleAttr) {
        enum class Style { LIGHT, DARK, AUTO }

        private fun dp(value: Int): Int = SettingsPalette.dp(context, value)

        private val track = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var palette = Palette.of(false)
        private var style = Style.AUTO

        private var checkedState = false
        private var animatedFraction = 0f
        private var animator: ValueAnimator? = null
        private var listener: ((Boolean) -> Unit)? = null
        private var label: CharSequence? = null

        init {
            isClickable = true
            isFocusable = true
            minimumWidth = dp(SettingsPalette.SWITCH_WIDTH_DP)
            minimumHeight = dp(SettingsPalette.SWITCH_HEIGHT_DP)
            refreshAccessibility()
        }

        /**
         * Whether the switch is currently on.
         *
         * Writing the value jumps immediately, without animation and without notifying the
         * listener, exactly like `android.widget.Switch#setChecked`.
         */
        var isChecked: Boolean
            get() = checkedState
            set(value) {
                if (checkedState == value) return
                checkedState = value
                cancelAnimation()
                animatedFraction = if (value) 1f else 0f
                refreshAccessibility()
                invalidate()
            }

        /** Same contract as `CompoundButton#setOnCheckedChangeListener`; receives the new state. */
        fun setOnCheckedChangeListener(listener: ((Boolean) -> Unit)?) {
            this.listener = listener
        }

        /** Flips the switch with animation and notifies the listener (if any). */
        fun toggle() {
            flip(animate = true, notify = true)
        }

        /** Animates to [value]; when [notify] is true the listener receives the new state. */
        fun setCheckedAnimated(
            value: Boolean,
            notify: Boolean = true,
        ) {
            if (checkedState == value) return
            checkedState = value
            refreshAccessibility()
            animateTo(value)
            if (notify) listener?.invoke(value)
        }

        /** Applies the palette of the settings pages (ignored when [setStyle] pinned a look). */
        fun applyPalette(palette: Palette) {
            this.palette = palette
            if (style == Style.AUTO) invalidate()
        }

        /** Pins the off colour; [Style.AUTO] (the default) follows [applyPalette]. */
        fun setStyle(style: Style) {
            this.style = style
            invalidate()
        }

        /** Sets the human readable label; the on/off state is appended for accessibility. */
        fun setLabel(text: CharSequence?) {
            label = text
            refreshAccessibility()
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val width = resolveSize(dp(SettingsPalette.SWITCH_WIDTH_DP), widthMeasureSpec)
            val height = resolveSize(dp(SettingsPalette.SWITCH_HEIGHT_DP), heightMeasureSpec)
            setMeasuredDimension(width, height)
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            track.set(0f, 0f, w.toFloat(), h.toFloat())
        }

        @ColorInt
        private fun trackColor(): Int {
            val onFraction = SettingsKitState.knobFraction(checkedState, animatedFraction)
            val off =
                when (style) {
                    Style.DARK -> SettingsPalette.SWITCH_OFF_DARK
                    Style.LIGHT -> SettingsPalette.SWITCH_OFF_LIGHT
                    Style.AUTO -> palette.switchOff
                }
            return blend(off, palette.switchOn, onFraction)
        }

        override fun onDraw(canvas: Canvas) {
            val viewHeight = height.toFloat()
            val viewWidth = width.toFloat()
            if (viewHeight <= 0f || viewWidth <= 0f) return
            val radius = viewHeight / 2f
            val inset = radius * 0.1f

            // Capsule track; the colour cross-fades from grey to iOS green while sliding.
            paint.style = Paint.Style.FILL
            paint.color = trackColor()
            paint.alpha = if (isPressed) 225 else 255
            canvas.drawRoundRect(
                RectF(inset, inset, viewWidth - inset, viewHeight - inset),
                radius - inset,
                radius - inset,
                paint,
            )
            paint.alpha = 255

            // White knob sliding from left to right.
            val knobRadius = radius - radius * 0.11f
            val travelLeft = knobRadius + radius * 0.08f
            val travelRight = viewWidth - knobRadius - radius * 0.08f
            val fraction = SettingsKitState.knobFraction(checkedState, animatedFraction)
            val cx = travelLeft + (travelRight - travelLeft) * fraction
            val cy = viewHeight / 2f

            paint.color = KNOB_SHADOW
            canvas.drawCircle(cx, cy + radius * 0.06f, knobRadius, paint)
            paint.color = KNOB
            canvas.drawCircle(cx, cy, knobRadius, paint)
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.45f
        }

        override fun onDetachedFromWindow() {
            cancelAnimation()
            super.onDetachedFromWindow()
        }

        override fun performClick(): Boolean {
            super.performClick()
            if (isEnabled) toggle()
            return true
        }

        /**
         * Called by [SettingsBuilder.toggle] once the row handler returned, so a handler that
         * persisted a different value (or vetoed the change) is reflected on screen.
         */
        fun syncFromCallback(value: Boolean) {
            if (checkedState == value) {
                animatedFraction = if (value) 1f else 0f
                invalidate()
                return
            }
            setCheckedAnimated(value, notify = false)
        }

        private fun flip(
            animate: Boolean,
            notify: Boolean,
        ) {
            val next = !checkedState
            checkedState = next
            refreshAccessibility()
            if (animate) {
                animateTo(next)
            } else {
                cancelAnimation()
                animatedFraction = if (next) 1f else 0f
                invalidate()
            }
            if (notify) listener?.invoke(next)
        }

        private fun animateTo(checked: Boolean) {
            val target = if (checked) 1f else 0f
            if (animatedFraction == target) return
            cancelAnimation()
            val from = animatedFraction
            val update =
                ValueAnimator.AnimatorUpdateListener { animation ->
                    animatedFraction = animation.animatedValue as Float
                    invalidate()
                }
            animator =
                ValueAnimator.ofFloat(from, target).apply {
                    duration = SettingsPalette.SWITCH_ANIMATION_MS.toLong()
                    interpolator = DecelerateInterpolator()
                    addUpdateListener(update)
                    start()
                }
        }

        private fun cancelAnimation() {
            animator?.cancel()
            animator = null
        }

        private fun refreshAccessibility() {
            contentDescription = SettingsKitState.switchDescription(label, checkedState)
            if (Build.VERSION.SDK_INT >= 30) {
                stateDescription = if (checkedState) "已开启" else "已关闭"
            }
        }

        private fun blend(
            from: Int,
            to: Int,
            t: Float,
        ): Int {
            val fraction = t.coerceIn(0f, 1f)
            val a = (from ushr 24) and 0xFF
            val r = (from shr 16) and 0xFF
            val g = (from shr 8) and 0xFF
            val b = from and 0xFF
            val na = (to ushr 24) and 0xFF
            val nr = (to shr 16) and 0xFF
            val ng = (to shr 8) and 0xFF
            val nb = to and 0xFF
            val outA = (a + (na - a) * fraction + 0.5f).toInt()
            val outR = (r + (nr - r) * fraction + 0.5f).toInt()
            val outG = (g + (ng - g) * fraction + 0.5f).toInt()
            val outB = (b + (nb - b) * fraction + 0.5f).toInt()
            return (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
        }

        private companion object {
            const val KNOB = 0xFFFFFFFF.toInt()
            const val KNOB_SHADOW = 0x22000000
        }
    }
