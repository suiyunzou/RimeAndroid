// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.smart.RadialMenuLayout.Companion.ARC_MAX_DEGREES
import com.osfans.trime.ime.smart.RadialMenuLayout.Companion.ARC_MIN_DEGREES
import com.osfans.trime.ime.smart.RadialMenuLayout.Companion.ARC_SWEEP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The same renderer and gesture model serve the IME and the interactive settings preview. */
class RadialMenuView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val geometry = RadialMenuGeometry(density, includeLunar = Build.VERSION.SDK_INT >= 24)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerBounds = RectF()
    private val gestureBounds = RectF()
    private var layout = RadialMenuLayout(0f, 0f, 0f, density, 0)
    private var iconPositions = emptyList<RadialMenuLayout.Point>()
    private var visualStyle = "rainbow"
    private var originGlow = true
    private var followFinger = true
    private var iconVisibility = "all"
    private var focusStyle = "lens"
    private var cursorPlacement = "finger"
    private var motionStyle = "snappy"
    private var fingerX = 0f
    private var fingerY = 0f
    private var drawnFingerX = 0f
    private var drawnFingerY = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var startedAt = 0L
    private var animatorScale = 1f
    private var focusLevels = FloatArray(0)
    private var previewMode = false
    private var previewActions: List<String> = DEFAULT_SLOTS
    private var previewAction: ((String) -> Unit)? = null
    private var previewTouch = false
    var previewDark: Boolean? = null
        set(value) { field = value; invalidate() }
    var dimmedBottom = Float.POSITIVE_INFINITY
    var traditionalLabel = "简"
        set(value) { field = if (value == "繁") "繁" else "简"; invalidate() }
    val isActive: Boolean get() = geometry.isActive

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = GONE
        setWillNotDraw(false)
    }

    fun begin(
        originX: Float,
        originY: Float,
        slots: List<String> = DEFAULT_SLOTS,
        style: String = "rainbow",
        originGlow: Boolean = true,
        followFinger: Boolean = true,
    ) {
        geometry.begin(originX, originY, slots)
        updateLayout()
        visualStyle = style.takeIf { it in STYLES } ?: "arc"
        this.originGlow = originGlow
        this.followFinger = followFinger
        fingerX = originX
        fingerY = originY
        drawnFingerX = originX
        drawnFingerY = originY
        velocityX = 0f
        velocityY = 0f
        startedAt = SystemClock.uptimeMillis()
        animatorScale = runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        focusLevels = FloatArray(geometry.slots.size)
        visibility = if (isActive) VISIBLE else GONE
        if (!previewMode) bringToFront()
        invalidate()
    }

    /** All values are stable preference keys; this changes the actual IME renderer too. */
    fun configureAppearance(
        iconVisibility: String = "all",
        focusStyle: String = "lens",
        cursorPlacement: String = "finger",
        motionStyle: String = "snappy",
    ) {
        this.iconVisibility = iconVisibility.takeIf { it in ICON_VISIBILITY_MODES } ?: "all"
        this.focusStyle = focusStyle.takeIf { it in FOCUS_STYLES } ?: "lens"
        this.cursorPlacement = cursorPlacement.takeIf { it in CURSOR_PLACEMENTS } ?: "finger"
        this.motionStyle = motionStyle.takeIf { it in MOTION_STYLES } ?: "snappy"
        invalidate()
    }

    /** Preview never commits text or dispatches an application action. */
    fun setPreviewMode(enabled: Boolean, onAction: ((String) -> Unit)? = null) {
        previewMode = enabled
        previewAction = onAction
        isClickable = enabled
        importantForAccessibility = if (enabled) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = if (enabled) "空格轮盘交互预览" else null
        if (enabled) {
            visibility = VISIBLE
            showPreview()
        } else cancel()
    }

    fun configurePreview(
        slots: List<String> = DEFAULT_SLOTS,
        style: String = "rainbow",
        originGlow: Boolean = true,
        followFinger: Boolean = true,
    ) {
        previewActions = slots.toList()
        visualStyle = style.takeIf { it in STYLES } ?: "arc"
        this.originGlow = originGlow
        this.followFinger = followFinger
        if (previewMode) showPreview()
    }

    /** The function editor uses the same geometry as the visible preview. */
    var editingAction: String? = null
        set(value) { field = value; invalidate() }

    fun previewActionAt(x: Float, y: Float): String? {
        fun distance(index: Int): Float {
            val point = iconPositions[index]
            return hypot(x - point.x, y - point.y)
        }
        return iconPositions.indices.minByOrNull(::distance)
            ?.takeIf { distance(it) <= 28f * density }
            ?.let { geometry.slots[it] }
    }

    fun previewDropFraction(x: Float, y: Float): Float? {
        val dx = x - geometry.originX
        val dy = geometry.originY - y
        if (dy < 0f || hypot(dx, dy) < 36f * density) return null
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            .coerceIn(ARC_MIN_DEGREES, ARC_MAX_DEGREES)
        return (ARC_MAX_DEGREES - angle) / ARC_SWEEP
    }

    private fun showPreview() {
        if (width <= 0 || height <= 0) return
        begin(width / 2f, height - 24f * density, previewActions, visualStyle, originGlow, followFinger)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (previewMode) showPreview()
        else if (isActive) updateLayout()
    }

    private fun updateLayout() {
        val x = geometry.originX
        val y = geometry.originY
        val count = geometry.slots.size
        layout = RadialMenuLayout(x, y, width.toFloat(), density, count)
        iconPositions = List(count) { layout.icon(it, count) }
        val outer = layout.outerRadius
        val inner = layout.gestureRadius
        outerBounds.set(x - outer, y - outer, x + outer, y + outer)
        gestureBounds.set(x - inner, y - inner, x + inner, y + inner)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!previewMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previewTouch = true
                parent?.requestDisallowInterceptTouchEvent(true)
                showPreview()
                update(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> if (previewTouch) update(event.x, event.y)
            MotionEvent.ACTION_UP -> {
                if (previewTouch) {
                    update(event.x, event.y)
                    val action = geometry.release(event.x, event.y)
                    action?.let { previewAction?.invoke(it) }
                    performClick()
                }
                previewTouch = false
                parent?.requestDisallowInterceptTouchEvent(false)
                showPreview()
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                previewTouch = false
                geometry.cancel()
                parent?.requestDisallowInterceptTouchEvent(false)
                showPreview()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun update(x: Float, y: Float) {
        if (!isActive) return
        fingerX = x
        fingerY = y
        if (geometry.update(x, y)) tick()
        invalidate()
    }

    fun release(x: Float, y: Float): String? {
        val result = geometry.release(x, y)
        visibility = GONE
        invalidate()
        return result
    }

    fun cancel() {
        geometry.cancel()
        visibility = GONE
        invalidate()
    }

    private fun tick() {
        val tuned = FeedbackTuningPrefs(context)
        val enabled = if (tuned.configured) tuned.hapticEnabled && tuned.profile(false).pressStrength > 0
            else com.osfans.trime.data.prefs.AppPrefs.defaultInstance().keyboard.vibrateOnKeyPress.getValue()
        if (!isHapticFeedbackEnabled || !enabled) return
        runCatching {
            if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
            if ((context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.ringerMode == android.media.AudioManager.RINGER_MODE_SILENT) return
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= 29 && vibrator?.hasVibrator() == true) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    override fun onDetachedFromWindow() { cancel(); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (previewMode) showPreview() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isActive) return
        val board = ColorManager.currentScope()?.colors?.keyboardBackColor ?: Color.rgb(209, 211, 217)
        val dark = previewDark ?: (ColorUtils.calculateLuminance(board) < .3)
        // Retain the real keyboard beneath the overlay. No opaque sector wedges.
        paint.style = Paint.Style.FILL
        paint.color = if (previewMode) Color.TRANSPARENT else ColorUtils.setAlphaComponent(board, 222)
        canvas.drawRect(0f, 0f, width.toFloat(), minOf(height.toFloat(), dimmedBottom), paint)
        val foreground = if (dark) Color.rgb(225, 225, 230) else Color.rgb(65, 67, 73)
        if (geometry.timeArmed) {
            drawTimePreview(canvas, dark, foreground)
            if (isShown) postInvalidateDelayed(200)
            return
        }
        val cx = geometry.originX
        val cy = geometry.originY
        val elapsed = (SystemClock.uptimeMillis() - startedAt).toFloat()
        val opening = if (animatorScale <= 0f) 1f else (elapsed / (150f * animatorScale)).coerceIn(0f, 1f)
        canvas.save()
        val openingScale = .90f + .10f * (1f - (1f - opening) * (1f - opening))
        canvas.scale(openingScale, openingScale, cx, cy)
        updatePointer()
        drawArc(canvas, cx, cy, if (animatorScale > 0f) elapsed else 0f, foreground, dark)
        drawGestureTrack(canvas, foreground)
        drawPointer(canvas, cx, cy, foreground)
        val iconUnit = maxOf(layout.unit, .72f * density)
        for ((index, action) in geometry.slots.withIndex()) {
            if (previewMode && action == editingAction) continue
            val (x, y) = iconPositions[index]
            val selected = geometry.selectedIndex == index
            val target = if (selected) 1f else 0f
            val response = when (motionStyle) { "soft" -> .18f; "spring" -> .30f; else -> .48f }
            focusLevels[index] = if (animatorScale <= 0f) target else
                (focusLevels[index] + (target - focusLevels[index]) * response).let { if (abs(it - target) < .01f) target else it }
            val focus = focusLevels[index]
            val visibleAlpha = when (iconVisibility) {
                "selected" -> if (selected) 255 else 0
                "hint" -> if (selected || elapsed < 1100f) 255 else 30
                "nearby" -> if (selected) 255 else if (geometry.selectedIndex < 0 || abs(index - geometry.selectedIndex) <= 1) 110 else 22
                else -> if (selected) 255 else 150
            }
            if (visibleAlpha == 0) continue
            if ((selected && focusStyle == "lens") || previewMode) {
                paint.style = Paint.Style.FILL
                paint.color = if (dark) Color.argb(if (selected) 210 else 130, 79, 79, 83) else Color.argb(if (selected) 235 else 150, 224, 226, 231)
                canvas.drawCircle(x, y, (12f + 6f * focus) * iconUnit, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = .6f * density
                paint.color = withAlpha(foreground, if (selected) 150 else 45)
                canvas.drawCircle(x, y, (12f + 6f * focus) * iconUnit, paint)
            }
            if (selected && focusStyle == "spotlight") {
                for (ring in 5 downTo 1) {
                    paint.style = Paint.Style.FILL
                    paint.color = withAlpha(foreground, 8)
                    canvas.drawCircle(x, y, (12f + ring * 4f) * iconUnit, paint)
                }
            }
            val scale = 1f + .25f * focus
            drawIcon(canvas, action, x, y, 8f * iconUnit * scale, withAlpha(foreground, visibleAlpha))
        }
        canvas.restore()
        val moving = followFinger && abs(fingerX - drawnFingerX) + abs(fingerY - drawnFingerY) > .5f
        val focusing = focusLevels.indices.any { focusLevels[it] != if (geometry.selectedIndex == it) 1f else 0f }
        if (isShown && animatorScale > 0f && (visualStyle in setOf("rainbow", "dewdrop") || moving || focusing || opening < 1f || (iconVisibility == "hint" && elapsed < 1250f))) postInvalidateOnAnimation()
        else if (isShown && iconVisibility == "hint" && elapsed < 1100f) postInvalidateDelayed((1100f - elapsed).toLong())
    }

    private fun pointerAngle(): Float = if (geometry.selectedIndex < 0) 90f else
        Math.toDegrees(atan2((geometry.originY - if (followFinger && animatorScale > 0f && motionStyle != "snappy") drawnFingerY else fingerY).toDouble(),
            ((if (followFinger && animatorScale > 0f && motionStyle != "snappy") drawnFingerX else fingerX) - geometry.originX).toDouble())).toFloat().coerceIn(0f, 180f)

    private fun iconAngle(index: Int): Double =
        Math.toRadians(RadialMenuLayout.iconDegrees(index, geometry.slots.size).toDouble())

    private fun drawArc(canvas: Canvas, cx: Float, cy: Float, elapsed: Float, foreground: Int, dark: Boolean) {
        val radius = layout.outerRadius
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = .8f * density
        paint.color = withAlpha(foreground, 100)
        when (visualStyle) {
            "none" -> Unit
            "band" -> {
                paint.strokeWidth = 23f * density
                paint.color = if (dark) Color.argb(170, 87, 87, 92) else Color.argb(190, 200, 203, 211)
                canvas.drawArc(outerBounds, -ARC_MAX_DEGREES, ARC_SWEEP, false, paint)
                drawBeads(canvas, cx, cy, radius, foreground)
            }
            "beads" -> drawBeads(canvas, cx, cy, radius, foreground)
            "rainbow" -> {
                val selectedAngle = pointerAngle().coerceIn(ARC_MIN_DEGREES, ARC_MAX_DEGREES)
                for (i in 0 until (ARC_SWEEP / 1.5f).toInt()) {
                    val angle = ARC_MAX_DEGREES - i * 1.5f
                    val proximity = (1f - abs(angle - selectedAngle) / 52f).coerceIn(0f, 1f)
                    paint.strokeWidth = (.9f + if (geometry.selectedIndex >= 0) 3.2f * proximity else 0f) * density
                    val hue = (320f - i * 3f + 7f * sin(elapsed / 1500f)) % 360f
                    paint.color = Color.HSVToColor((145 + proximity * 100).toInt(), floatArrayOf((hue + 360f) % 360f, .65f, if (dark) .9f else .72f))
                    canvas.drawArc(outerBounds, -angle, 1.7f, false, paint)
                }
            }
            "dewdrop" -> {
                canvas.drawArc(outerBounds, -ARC_MAX_DEGREES, ARC_SWEEP, false, paint)
                val selected = geometry.selectedIndex.takeIf { it >= 0 } ?: 2.coerceAtMost(geometry.slots.lastIndex)
                val angle = if (geometry.selectedIndex >= 0) {
                    Math.toRadians(pointerAngle().coerceIn(ARC_MIN_DEGREES, ARC_MAX_DEGREES).toDouble())
                } else iconAngle(selected)
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(foreground, 210)
                canvas.drawCircle(cx + radius * cos(angle).toFloat(), cy - radius * sin(angle).toFloat(), (3.5f + .6f * sin(elapsed / 450f)) * density, paint)
            }
            else -> canvas.drawArc(outerBounds, -ARC_MAX_DEGREES, ARC_SWEEP, false, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawBeads(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL
        for (i in geometry.slots.indices) {
            val angle = iconAngle(i)
            paint.color = withAlpha(color, if (i == geometry.selectedIndex) 255 else 110)
            canvas.drawCircle(cx + radius * cos(angle).toFloat(), cy - radius * sin(angle).toFloat(), (if (i == geometry.selectedIndex) 2.1f else 1.1f) * density, paint)
        }
    }

    private fun updatePointer() {
        if (!followFinger || !fingerX.isFinite() || !fingerY.isFinite()) return
        when (if (animatorScale <= 0f) "snappy" else motionStyle) {
            "soft" -> { drawnFingerX += (fingerX - drawnFingerX) * .22f; drawnFingerY += (fingerY - drawnFingerY) * .22f }
            "spring" -> {
                velocityX = (velocityX + (fingerX - drawnFingerX) * .19f) * .65f
                velocityY = (velocityY + (fingerY - drawnFingerY) * .19f) * .65f
                drawnFingerX += velocityX; drawnFingerY += velocityY
            }
            else -> { drawnFingerX = fingerX; drawnFingerY = fingerY }
        }
    }

    private fun drawGestureTrack(canvas: Canvas, color: Int) {
        if (visualStyle == "none") return
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = density
        paint.color = withAlpha(color, 68)
        canvas.drawArc(gestureBounds, -180f, 180f, false, paint)
        if (geometry.selectedIndex >= 0) {
            val angle = if (followFinger) pointerAngle()
                else RadialMenuLayout.iconDegrees(geometry.selectedIndex, geometry.slots.size)
            paint.strokeWidth = 2.2f * density
            paint.color = withAlpha(color, 172)
            val start = (angle + 13f).coerceAtMost(180f)
            val end = (angle - 13f).coerceAtLeast(0f)
            canvas.drawArc(gestureBounds, -start, start - end, false, paint)
        }
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawPointer(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.FILL
        if (originGlow) {
            paint.color = Color.rgb(19, 126, 180)
            canvas.drawCircle(cx, cy, 4.5f * layout.unit, paint)
            if (focusStyle == "origin") {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density
                canvas.drawCircle(cx, cy, 8f * layout.unit, paint)
            }
        }
        if (!followFinger || !fingerX.isFinite() || !fingerY.isFinite()) return
        val (pointerX, pointerY) = layout.cursor(drawnFingerX, drawnFingerY, cursorPlacement == "above")
        if (visualStyle != "none" && geometry.selectedIndex >= 0) {
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 3f * density
            paint.color = withAlpha(color, 105)
            canvas.drawLine(cx, cy, pointerX, pointerY, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(color, 45)
        canvas.drawCircle(pointerX, pointerY, 11f * layout.unit, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = withAlpha(color, 230)
        canvas.drawCircle(pointerX, pointerY, 11f * layout.unit, paint)
    }

    private fun drawTimePreview(canvas: Canvas, dark: Boolean, color: Int) {
        // Reference frame 0554: a 349 x 111 card on a 486 px wide keyboard.
        val metrics = resources.displayMetrics
        val referenceUnit = minOf(width.toFloat(), minOf(metrics.widthPixels, metrics.heightPixels).toFloat()) / 486f
        val cardWidth = minOf(width - 40f * density, 349f * referenceUnit).coerceAtLeast(100f * density)
        val unit = cardWidth / 349f
        val left = (width - cardWidth) / 2f
        val top = (geometry.originY - 145f * density).coerceAtLeast(34f * density)
        val bottom = top + 111f * unit
        paint.style = Paint.Style.FILL
        paint.color = if (dark) Color.argb(235, 75, 75, 77) else Color.argb(245, 224, 226, 232)
        canvas.drawRoundRect(RectF(left, top, left + cardWidth, bottom), 30f * unit, 30f * unit, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = .6f * density
        paint.color = withAlpha(color, 55)
        canvas.drawRoundRect(RectF(left, top, left + cardWidth, bottom), 30f * unit, 30f * unit, paint)
        val index = geometry.timeFormatIndex
        paint.style = Paint.Style.FILL
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 16f * unit
        paint.color = withAlpha(color, 175)
        canvas.drawText(geometry.timeLabels[index], width / 2f, top + 24f * unit, paint)
        if (index > 0) canvas.drawText("‹ " + geometry.timeLabels[index - 1], left + cardWidth * .2f, top - 15f * unit, paint)
        if (index < geometry.timeLabels.lastIndex) canvas.drawText(geometry.timeLabels[index + 1] + " ›", left + cardWidth * .8f, top - 15f * unit, paint)
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        paint.textSize = 32f * unit
        paint.color = color
        val action = geometry.timeActions[index]
        val value = formatTimeAction(action, Date())
        if (action.startsWith("lunar_")) paint.textSize = minOf(28f * unit, 32f * unit * (cardWidth - 24f * unit) / paint.measureText(value.substringBefore(' ')).coerceAtLeast(1f))
        if (action == "datetime" || action == "lunar_datetime") {
            val parts = value.split(' ')
            canvas.drawText(parts[0], width / 2f, top + 61f * unit, paint)
            canvas.drawText(parts.getOrElse(1) { "" }, width / 2f, top + 99f * unit, paint)
        } else canvas.drawText(value, width / 2f, top + 79f * unit, paint)
    }

    fun drawIcon(canvas: Canvas, action: String, x: Float, y: Float, radius: Float, color: Int) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(radius / 10f, radius / 10f)
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.strokeCap = Paint.Cap.ROUND
        when (action) {
            "time" -> { canvas.drawCircle(0f, 0f, 8.5f, paint); canvas.drawLine(0f, -5f, 0f, 0f, paint); canvas.drawLine(0f, 0f, 4f, 2.5f, paint) }
            "settings" -> for (i in -1..1) {
                val yy = i * 6f
                canvas.drawLine(-9f, yy, 9f, yy, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(if (i == 0) 4f else -3f, yy, 2f, paint)
                paint.style = Paint.Style.STROKE
            }
            "clipboard" -> {
                canvas.drawRoundRect(RectF(-6.5f, -7f, 6.5f, 9f), 1.7f, 1.7f, paint)
                canvas.drawRoundRect(RectF(-3.5f, -10f, 3.5f, -5f), 1.5f, 1.5f, paint)
                canvas.drawLine(-3.5f, -1f, 3.5f, -1f, paint); canvas.drawLine(-3.5f, 3f, 3.5f, 3f, paint)
            }
            "calculator" -> {
                canvas.drawRoundRect(RectF(-7f, -10f, 7f, 10f), 2f, 2f, paint)
                canvas.drawRoundRect(RectF(-4.5f, -7f, 4.5f, -3f), .8f, .8f, paint)
                paint.style = Paint.Style.FILL
                for (row in 0..2) for (col in 0..2) canvas.drawCircle(-4f + col * 4f, row * 3.5f, .85f, paint)
            }
            "quotes" -> {
                val book = android.graphics.Path().apply {
                    moveTo(0f, -7f); quadTo(-4f, -10f, -9f, -8f); lineTo(-9f, 8f)
                    quadTo(-4f, 6f, 0f, 9f); quadTo(4f, 6f, 9f, 8f); lineTo(9f, -8f)
                    quadTo(4f, -10f, 0f, -7f); lineTo(0f, 9f)
                }
                canvas.drawPath(book, paint)
            }
            else -> {
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                paint.textSize = if (action == "symbols") 24f else 18f
                canvas.drawText(if (action == "traditional") traditionalLabel else "#", 0f, -(paint.ascent() + paint.descent()) / 2f, paint)
            }
        }
        canvas.restore()
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        val DEFAULT_SLOTS = RadialMenuGeometry.DEFAULT_SLOTS
        val STYLES = setOf("beads", "arc", "band", "none", "rainbow", "dewdrop")
        val ICON_VISIBILITY_MODES = setOf("selected", "all", "hint", "nearby")
        val FOCUS_STYLES = setOf("lens", "origin", "spotlight")
        val CURSOR_PLACEMENTS = setOf("finger", "above")
        val MOTION_STYLES = setOf("snappy", "soft", "spring")
        fun formatTimeAction(action: String, now: Date = Date()): String {
            if (action == "lunar_date" || action == "lunar_datetime") {
                return if (Build.VERSION.SDK_INT >= 24) ChineseLunarTime.format(now, action == "lunar_datetime") else ""
            }
            val format = when (action) {
                "chinese_date" -> "yyyy年M月d日"
                "weekday" -> "EEEE"
                "datetime" -> "yyyy-MM-dd HH:mm:ss"
                "date" -> "yyyy-MM-dd"
                else -> "HH:mm:ss"
            }
            return SimpleDateFormat(format, Locale.getDefault()).format(now)
        }
    }
}

