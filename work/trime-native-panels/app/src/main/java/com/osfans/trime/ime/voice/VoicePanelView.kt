// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.voice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Full-keyboard voice listening panel: an audio meter, a live
 * transcript, a microphone control and a cancel affordance. Everything is drawn
 * with the platform canvas: no resources, no child views and no extra
 * dependency.
 *
 * Add it over the keyboard body with `FrameLayout.LayoutParams(MATCH_PARENT,
 * MATCH_PARENT)`, call [applyStyle], then drive it with [setPhase],
 * [setAmplitude], [setTranscript] and [setCaption]. All setters must run on the
 * main thread. While visible the panel consumes every touch inside its bounds.
 */
class VoicePanelView(context: Context) : FrameLayout(context) {
    enum class Phase { HIDDEN, LOADING, LISTENING, FINISHING, ERROR }

    data class Style(
        val dark: Boolean,
        val background: Int,
        val card: Int,
        val text: Int,
        val muted: Int,
        val accent: Int,
    )

    var onMicClick: (() -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val legacyMotionEnabled = Build.VERSION.SDK_INT >= 26 ||
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    private val animationsEnabled: Boolean
        get() = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled() else legacyMotionEnabled
    private val mediumTypeface: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private var style = Style(
        dark = false,
        background = 0xFFD1D3D9.toInt(),
        card = 0xFFF2F2F7.toInt(),
        text = 0xFF1C1C1E.toInt(),
        muted = 0xFF8A8A8E.toInt(),
        accent = 0xFF0A84FF.toInt(),
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelPath = Path()
    private val hillPath = Path()
    private val hillRect = RectF()
    private val micFill = Path()
    private val micStroke = Path()
    private val micBody = RectF()
    private val micCradle = RectF()

    private var phase = Phase.HIDDEN
    val isListening: Boolean get() = phase == Phase.LISTENING
    private var caption = ""
    private var transcript = ""
    private var transcriptLines: List<String> = emptyList()
    private var transcriptMaxLines = 3
    private var transcriptTextSize = 0f
    private var transcriptLineHeight = 0f
    private val wave = VoiceWaveMotion()
    private val waveFrame = Runnable { updateWave() }
    private var motion = 0f
    private var motionTarget = 0f
    private var motionAnimator: ValueAnimator? = null
    private var pressed = Target.NONE
    private var pressedY = 0f

    /** True while the user slides up from the microphone: WeChat-style cancel. */
    private var cancelArmed = false

    // Geometry, recomputed only when the view is measured again.
    private var contentTop = 0f
    private var closeCx = 0f
    private var closeCy = 0f
    private var closeHitRadius = 0f
    private var micCx = 0f
    private var micCy = 0f
    private var micRadius = 0f
    private var captionY = 0f
    private var hillHeight = 0f
    private var transcriptTop = 0f
    private var waveCx = 0f
    private var waveCy = 0f
    private var waveHalfWidth = 0f
    private var waveHalfHeight = 0f
    private val wavePath = Path()
    private val ribbonHeights = FloatArray(WAVE_SAMPLES + 1)
    private val idleRect = RectF()
    private var idleShader: LinearGradient? = null
    private var panelShader: LinearGradient? = null

    private enum class Target { NONE, MIC, CLOSE }

    init {
        isClickable = true
        isFocusable = false
        // Transient canvas overlay: the mic and close targets are not separate
        // accessibility nodes, so do not trap TalkBack focus on a dead target.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = "语音输入面板"
        // A plain ViewGroup skips onDraw by default; the whole panel is custom paint.
        setWillNotDraw(false)
        visibility = GONE
        buildMicGlyph()
    }

    /** Colors only change the palette; geometry and animation state are kept. */
    fun applyStyle(style: Style) {
        this.style = style
        idleShader = null
        panelShader = null
        invalidate()
    }

    /**
     * LOADING, LISTENING, FINISHING and ERROR show the panel and play the entrance
     * animation when it was hidden. HIDDEN plays the reverse animation and then
     * becomes GONE. Repeating the current phase is a no-op.
     *
     * Every phase change resets the caption to the phase default, so call
     * [setCaption] after [setPhase] to override it.
     */
    fun setPhase(phase: Phase) {
        if (phase == this.phase) return
        val previous = this.phase
        this.phase = phase
        caption = defaultCaption(phase)
        if (phase != Phase.LISTENING) wave.stop()
        if (phase == Phase.LOADING || (phase == Phase.LISTENING && previous != Phase.LISTENING)) wave.reset()
        if (phase == Phase.HIDDEN) {
            removeCallbacks(waveFrame)
            wave.reset()
            if (visibility == VISIBLE && motion > 0f) {
                animateMotion(0f)
            } else {
                motion = 0f
                motionTarget = 0f
                visibility = GONE
                invalidate()
            }
            return
        }
        val entering = visibility != VISIBLE || motion <= 0f
        if (entering) {
            visibility = VISIBLE
            animateMotion(1f)
        } else if (motion < 1f || motionTarget < 1f) {
            // A previous exit was still running; replay the entrance instead.
            animateMotion(1f)
        } else {
            invalidate()
        }
        updateWave()
    }

    /** RMS of the latest microphone slice, relative to full-scale PCM. */
    fun setAmplitude(rms: Float) {
        if (phase != Phase.LISTENING) return
        wave.sample(rms, SystemClock.uptimeMillis())
        updateWave()
    }

    /** Recognized text, up to three wrapped lines with an ellipsis. */
    fun setTranscript(text: String) {
        if (text == transcript) return
        transcript = text
        rebuildTranscript()
        invalidate()
    }

    /** Caption above the microphone. Ignored silently when blank. */
    fun setCaption(text: String) {
        if (text == caption) return
        caption = text
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Fill the parent even when the caller used WRAP_CONTENT.
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> max(widthSize, dp(320f).toInt())
            else -> widthSize
        }
        val measuredHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> max(heightSize, dp(220f).toInt())
            else -> heightSize
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateWave()
    }

    override fun onDetachedFromWindow() {
        // Drop pending frames and animation listeners before detaching.
        val animator = motionAnimator
        motionAnimator = null
        animator?.cancel()
        removeCallbacks(waveFrame)
        wave.reset()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!isShown) {
            removeCallbacks(waveFrame)
            wave.reset()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            removeCallbacks(waveFrame)
            wave.reset()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val fade = motion.coerceIn(0f, 1f)
        if (fade <= 0f) return
        val checkpoint = canvas.save()
        canvas.clipPath(panelPath)
        drawPanel(canvas, fade)
        if (cancelArmed) {
            drawCancelHill(canvas, fade)
        } else {
            drawWaves(canvas, fade)
        }
        drawTranscript(canvas, fade)
        drawCaption(canvas, fade)
        if (!cancelArmed) drawMic(canvas, fade)
        drawClose(canvas, fade)
        canvas.restoreToCount(checkpoint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = when {
                    hitsMic(event.x, event.y) -> Target.MIC
                    hitsClose(event.x, event.y) -> Target.CLOSE
                    else -> Target.NONE
                }
                pressedY = event.y
                cancelArmed = false
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (pressed) {
                    Target.MIC -> {
                        // Slide up from the microphone arms the WeChat-style
                        // cancel: a crest rises from the bottom with the
                        // 「上滑取消文字」 hint, and releasing cancels.
                        val armed = event.y - pressedY < -dp(48f)
                        if (armed != cancelArmed) {
                            cancelArmed = armed
                            performHapticFeedback(
                                if (armed) HapticFeedbackConstants.VIRTUAL_KEY else HapticFeedbackConstants.VIRTUAL_KEY,
                            )
                        }
                    }
                    Target.CLOSE -> if (!hitsClose(event.x, event.y)) pressed = Target.NONE
                    Target.NONE -> Unit
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val wasArmed = cancelArmed
                cancelArmed = false
                val activated = pressed
                pressed = Target.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                if (wasArmed) {
                    performClick()
                    // Slide-up release cancels the session; nothing is committed.
                    onCancel?.invoke()
                } else if (activated != Target.NONE) {
                    performClick()
                    if (activated == Target.MIC) onMicClick?.invoke() else onCancel?.invoke()
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = Target.NONE
                cancelArmed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        // The panel owns the keyboard body while it is visible.
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // region geometry

    private fun updateLayout() {
        val panelWidth = width.toFloat()
        val panelHeight = height.toFloat()
        if (panelWidth <= 0f || panelHeight <= 0f) return
        panelShader = null
        idleShader = null
        val radius = min(dp(20f), min(panelWidth, panelHeight) * 0.5f)
        panelPath.reset()
        panelPath.addRoundRect(0f, 0f, panelWidth, panelHeight, radius, radius, Path.Direction.CW)

        closeCx = panelWidth - dp(22f)
        closeCy = dp(24f)
        closeHitRadius = dp(19f)

        contentTop = min(dp(42f), panelHeight * 0.21f)
        micRadius = max(dp(15f), min(dp(24f), panelHeight * 0.15f))
        micCx = panelWidth * 0.5f
        micCy = panelHeight - max(micRadius + dp(24f), dp(48f))
        captionY = micCy - micRadius - dp(14f)
        hillHeight = panelHeight * 0.38f
        transcriptTop = contentTop
        transcriptLineHeight = dp(22f)
        transcriptTextSize = dp(16f)

        // Allocate space above the meter for text, even when recognition is empty.
        val bandBottom = captionY - dp(20f)
        val available = (bandBottom - transcriptTop).coerceAtLeast(0f)
        val preferredWaveHeight = min(panelWidth * 0.26f, available * 0.68f)
        transcriptMaxLines = ((available - preferredWaveHeight - dp(4f)) / transcriptLineHeight)
            .toInt().coerceIn(0, 3)
        val bandTop = transcriptTop + transcriptMaxLines * transcriptLineHeight + dp(4f)
        val band = (bandBottom - bandTop).coerceAtLeast(0f)
        waveCx = panelWidth * 0.5f
        waveCy = bandTop + band * 0.5f
        waveHalfWidth = panelWidth * 0.45f
        waveHalfHeight = min(band * 0.5f, panelWidth * 0.13f)

        textPaint.textSize = transcriptTextSize
        rebuildTranscript()
    }

    private fun buildMicGlyph() {
        micBody.set(-4.5f, -9f, 4.5f, 2f)
        micFill.reset()
        micFill.addRoundRect(micBody, 4.5f, 4.5f, Path.Direction.CW)
        micCradle.set(-7.5f, -4f, 7.5f, 7f)
        micStroke.reset()
        micStroke.addArc(micCradle, 0f, 180f)
        micStroke.moveTo(0f, 6.5f)
        micStroke.lineTo(0f, 9.5f)
        micStroke.moveTo(-4.5f, 9.5f)
        micStroke.lineTo(4.5f, 9.5f)
    }

    private fun rebuildTranscript() {
        transcriptLines = emptyList()
        if (width <= 0) return
        val limit = width - dp(48f)
        if (limit <= dp(40f) || transcript.isEmpty()) return
        textPaint.textSize = transcriptTextSize
        val maxLines = transcriptMaxLines
        if (maxLines <= 0) return
        val lines = ArrayList<String>()
        val builder = StringBuilder()
        var widthSoFar = 0f
        val one = CharArray(1)
        for (char in transcript) {
            if (char == '\n') {
                lines.add(builder.toString().trimEnd())
                builder.setLength(0)
                widthSoFar = 0f
                continue
            }
            one[0] = char
            val charWidth = textPaint.measureText(one, 0, 1)
            if (widthSoFar + charWidth > limit && builder.isNotEmpty()) {
                lines.add(builder.toString().trimEnd())
                builder.setLength(0)
                widthSoFar = 0f
            }
            builder.append(char)
            widthSoFar += charWidth
        }
        if (builder.isNotEmpty()) lines.add(builder.toString().trimEnd())
        if (lines.size > maxLines) {
            // Transcript keeps growing: drop the oldest lines and show the
            // newest window, with a leading ellipsis marking the cut.
            val kept = ArrayList<String>(maxLines)
            for (index in lines.size - maxLines until lines.size) kept.add(lines[index])
            var first = kept[0]
            while (first.isNotEmpty() && textPaint.measureText("…$first") > limit) {
                first = first.substring(1)
            }
            kept[0] = "…$first"
            transcriptLines = kept
        } else {
            transcriptLines = lines
        }
    }

    // endregion

    // region drawing

    private fun drawPanel(canvas: Canvas, fade: Float) {
        paint.style = Paint.Style.FILL
        // A very light vertical tint keeps the sheet from looking like a flat
        // grey block under the keys.
        paint.shader = ensurePanelShader()
        paint.alpha = (255f * fade).toInt().coerceIn(0, 255)
        canvas.drawPath(panelPath, paint)
        paint.alpha = 255
        paint.shader = null
        // A soft inner shadow and a hairline border, both clipped by the panel path.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = withAlpha(if (style.dark) Color.WHITE else Color.BLACK, (8f * fade).toInt())
        canvas.drawPath(panelPath, paint)
        paint.strokeWidth = dp(0.9f)
        paint.color = withAlpha(if (style.dark) Color.WHITE else Color.BLACK, (30f * fade).toInt())
        canvas.drawPath(panelPath, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawWaves(canvas: Canvas, fade: Float) {
        if (waveHalfWidth <= 0f || waveHalfHeight <= 0f) return
        val listening = phase == Phase.LISTENING
        if (!listening && phase != Phase.FINISHING) return
        val level = wave.level
        val color = if (style.dark) 0xFF409CFF.toInt() else 0xFF007AFF.toInt()
        paint.style = Paint.Style.FILL
        if (listening && level < 0.10f) {
            val extent = waveHalfWidth * 0.82f
            val thickness = dp(0.9f)
            idleRect.set(waveCx - extent, waveCy - thickness / 2f, waveCx + extent, waveCy + thickness / 2f)
            paint.shader = idleShader ?: LinearGradient(
                idleRect.left, waveCy, idleRect.right, waveCy,
                intArrayOf(withAlpha(color, 0), withAlpha(color, 195), withAlpha(color, 0)),
                null, Shader.TileMode.CLAMP,
            ).also { idleShader = it }
            paint.alpha = (255 * fade * (1f - level / 0.10f)).toInt()
            canvas.drawRoundRect(idleRect, thickness / 2f, thickness / 2f, paint)
            paint.shader = null
        }
        if (level <= 0f) {
            paint.alpha = 255
            return
        }
        val halfWidth = waveHalfWidth * (0.82f + 0.18f * (level * 4f).coerceAtMost(1f))
        val left = waveCx - halfWidth
        val step = 2f * halfWidth / WAVE_SAMPLES
        val halfHeight = waveHalfHeight * level.pow(0.83f)
        val opacity = fade * (level * 10f).coerceAtMost(1f)
        for (layer in RIBBONS) {
            val phase = layer.phase + TAU * wave.seconds * layer.turns / VoiceWaveMotion.PHASE_PERIOD_S
            val c = cos(phase)
            val s = sin(phase)
            for (i in 0..WAVE_SAMPLES) {
                // The carrier crosses the centre several times. Reflect each lobe
                // around that fixed axis; the taper brings both tips to zero.
                val carrier = abs(layer.sin[i] * c - layer.cos[i] * s)
                ribbonHeights[i] = halfHeight * layer.taper[i] * carrier
            }
            wavePath.rewind()
            wavePath.moveTo(left, waveCy)
            for (i in 1..WAVE_SAMPLES) wavePath.lineTo(left + step * i, waveCy - ribbonHeights[i])
            for (i in WAVE_SAMPLES - 1 downTo 0) wavePath.lineTo(left + step * i, waveCy + ribbonHeights[i])
            wavePath.close()
            paint.color = withAlpha(color, (layer.alpha * opacity).toInt())
            canvas.drawPath(wavePath, paint)
        }
        paint.alpha = 255
    }

    private fun ensurePanelShader(): LinearGradient {
        panelShader?.let { return it }
        return LinearGradient(
            0f,
            0f,
            0f,
            max(height.toFloat(), 1f),
            style.background,
            blend(style.background, style.accent, if (style.dark) 0.10f else 0.05f),
            Shader.TileMode.CLAMP,
        ).also { panelShader = it }
    }

    private fun drawTranscript(canvas: Canvas, fade: Float) {
        val lines = transcriptLines
        if (lines.isEmpty() || transcriptMaxLines <= 0) return
        textPaint.style = Paint.Style.FILL
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = transcriptTextSize
        textPaint.color = withAlpha(style.text, (232f * fade).toInt())
        val firstBaseline = transcriptTop + transcriptLineHeight * 0.82f
        for (index in lines.indices) {
            canvas.drawText(lines[index], width * 0.5f, firstBaseline + index * transcriptLineHeight, textPaint)
        }
    }

    private fun drawCaption(canvas: Canvas, fade: Float) {
        val text = if (cancelArmed) "上滑取消文字" else caption
        if (text.isEmpty() || captionY <= contentTop) return
        textPaint.style = Paint.Style.FILL
        textPaint.typeface = mediumTypeface
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = dp(13f)
        textPaint.color = if (cancelArmed) {
            withAlpha(style.accent, (255f * fade).toInt())
        } else {
            withAlpha(style.muted, (240f * fade).toInt())
        }
        val limit = max(width - dp(48f), dp(60f))
        val lines = wrapSmall(text, limit, 2)
        val baseline0 = captionY - (lines.size - 1) * dp(17f)
        for (index in lines.indices) {
            canvas.drawText(lines[index], width * 0.5f, baseline0 + index * dp(17f), textPaint)
        }
    }

    /** Wraps short single-paragraph text into at most [maxLines] centred lines. */
    private fun wrapSmall(
        text: String,
        limit: Float,
        maxLines: Int,
    ): List<String> {
        if (textPaint.measureText(text) <= limit) return listOf(text)
        val lines = ArrayList<String>(maxLines)
        val builder = StringBuilder()
        var widthSoFar = 0f
        val one = CharArray(1)
        for (char in text) {
            one[0] = char
            val charWidth = textPaint.measureText(one, 0, 1)
            if (widthSoFar + charWidth > limit && builder.isNotEmpty()) {
                lines.add(builder.toString())
                builder.setLength(0)
                widthSoFar = 0f
                if (lines.size == maxLines) break
            }
            builder.append(char)
            widthSoFar += charWidth
        }
        if (lines.size < maxLines && builder.isNotEmpty()) lines.add(builder.toString())
        return lines
    }

    /**
     * The WeChat-style slide-up cancel crest: a wide arc rising from the bottom
     * edge with a white microphone on top, tinted with the Apple accent.
     */
    private fun drawCancelHill(canvas: Canvas, fade: Float) {
        if (hillHeight <= 0f) return
        val panelWidth = width.toFloat()
        val panelHeight = height.toFloat()
        val rx = panelWidth * 0.9f
        hillRect.set(
            panelWidth * 0.5f - rx,
            panelHeight - hillHeight,
            panelWidth * 0.5f + rx,
            panelHeight + hillHeight,
        )
        hillPath.reset()
        hillPath.addOval(hillRect, Path.Direction.CW)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = withAlpha(style.accent, (250f * fade).toInt())
        canvas.drawPath(hillPath, paint)
        // White microphone glyph centred on the crest, sized like the
        // reference hill (roughly a third of the crest height).
        drawMicGlyph(canvas, panelWidth * 0.5f, panelHeight - hillHeight * 0.5f, panelWidth * 0.0052f, fade)
    }

    private fun drawMic(canvas: Canvas, fade: Float) {
        if (micRadius <= 0f) return
        paint.style = Paint.Style.FILL
        val pressedScale = if (pressed == Target.MIC) 0.93f else 1f
        val radius = micRadius * pressedScale
        paint.color = withAlpha(style.accent, (252f * fade).toInt())
        canvas.drawCircle(micCx, micCy, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = withAlpha(Color.WHITE, (70f * fade).toInt())
        canvas.drawCircle(micCx, micCy, radius - dp(1.2f), paint)
        drawMicGlyph(canvas, micCx, micCy - dp(1f), radius * 1.15f / MIC_BOX_HEIGHT, fade)
        paint.style = Paint.Style.FILL
    }

    private fun drawMicGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        scale: Float,
        fade: Float,
    ) {
        if (scale <= 0f) return
        val checkpoint = canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(Color.WHITE, (245f * fade).toInt())
        canvas.drawPath(micFill, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = withAlpha(Color.WHITE, (235f * fade).toInt())
        canvas.drawPath(micStroke, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        canvas.restoreToCount(checkpoint)
    }

    private fun drawClose(canvas: Canvas, fade: Float) {
        if (closeHitRadius <= 0f) return
        val pressedNow = pressed == Target.CLOSE
        paint.style = Paint.Style.FILL
        paint.color = if (pressedNow) {
            withAlpha(style.accent, (40f * fade).toInt())
        } else {
            withAlpha(style.card, (210f * fade).toInt())
        }
        canvas.drawCircle(closeCx, closeCy, closeHitRadius * 0.66f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = withAlpha(if (style.dark) Color.WHITE else Color.BLACK, (20f * fade).toInt())
        canvas.drawCircle(closeCx, closeCy, closeHitRadius * 0.66f, paint)
        paint.strokeWidth = dp(1.6f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = withAlpha(style.muted, (240f * fade).toInt())
        val arm = dp(4.6f)
        canvas.drawLine(closeCx - arm, closeCy - arm, closeCx + arm, closeCy + arm, paint)
        canvas.drawLine(closeCx - arm, closeCy + arm, closeCx + arm, closeCy - arm, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    // endregion

    // region animation

    private fun animateMotion(target: Float) {
        motionTarget = target
        val previous = motionAnimator
        motionAnimator = null
        previous?.cancel()
        if (!animationsEnabled) {
            motion = target
            motionTarget = target
            onMotionEnd(target)
            return
        }
        val from = motion
        val animator = ValueAnimator.ofFloat(from, target).apply {
            duration = if (target > from) ENTER_MS else EXIT_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                motion = animation.animatedValue as Float
                invalidate()
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (motionAnimator === animation) {
                            motionAnimator = null
                            onMotionEnd(target)
                        }
                    }
                },
            )
        }
        motionAnimator = animator
        animator.start()
    }

    private fun onMotionEnd(target: Float) {
        motion = target
        motionTarget = target
        if (target <= 0f && phase == Phase.HIDDEN) visibility = GONE
        invalidate()
    }

    private fun updateWave() {
        removeCallbacks(waveFrame)
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE) return
        val before = wave.level
        val animate = animationsEnabled
        val listening = phase == Phase.LISTENING
        if (wave.advance(SystemClock.uptimeMillis(), animate, listening)) {
            postOnAnimation(waveFrame)
        } else if (!animate && listening && wave.level > 0f) {
            postDelayed(waveFrame, VoiceWaveMotion.SAMPLE_TIMEOUT_MS)
        }
        if (before > 0f || wave.level > 0f) invalidate()
    }

    // endregion

    private fun hitsMic(x: Float, y: Float): Boolean = hypot(x - micCx, y - micCy) <= micRadius + dp(10f)

    private fun hitsClose(x: Float, y: Float): Boolean = hypot(x - closeCx, y - closeCy) <= closeHitRadius

    private fun defaultCaption(phase: Phase): String = when (phase) {
        Phase.LOADING -> "正在准备语音模型…"
        Phase.LISTENING -> "正在聆听 · 点击麦克风结束"
        Phase.FINISHING -> "正在识别…"
        Phase.ERROR -> "语音识别不可用，点击麦克风重试"
        Phase.HIDDEN -> ""
    }

    private fun dp(value: Float): Float = value * density

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    private fun blend(
        base: Int,
        target: Int,
        amount: Float,
    ): Int {
        val a = amount.coerceIn(0f, 1f)
        fun channel(from: Int, to: Int) = (from + (to - from) * a).toInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(base), Color.red(target)),
            channel(Color.green(base), Color.green(target)),
            channel(Color.blue(base), Color.blue(target)),
        )
    }

    private class Ribbon(
        val alpha: Int,
        val phase: Float,
        val turns: Int,
        cycles: Float,
        height: Float,
        lean: Float,
    ) {
        val sin = FloatArray(WAVE_SAMPLES + 1) { kotlin.math.sin(TAU * cycles * it / WAVE_SAMPLES) }
        val cos = FloatArray(WAVE_SAMPLES + 1) { kotlin.math.cos(TAU * cycles * it / WAVE_SAMPLES) }
        val taper = FloatArray(WAVE_SAMPLES + 1) { i ->
            val x = 2f * i / WAVE_SAMPLES - 1f
            height * (1f - x * x).pow(1.65f) * (1f + lean * x)
        }
    }

    private companion object {
        const val ENTER_MS = 140L
        const val EXIT_MS = 120L
        const val MIC_BOX_HEIGHT = 18.5f
        const val TAU = 6.2831855f
        const val WAVE_SAMPLES = 120
        val RIBBONS = arrayOf(
            Ribbon(alpha = 75, phase = 1.0f, turns = 7, cycles = 2.12f, height = 0.99f, lean = 0.31f),
            Ribbon(alpha = 121, phase = 2.0f, turns = 10, cycles = 2.25f, height = 0.77f, lean = -0.36f),
            Ribbon(alpha = 253, phase = 0.1f, turns = 9, cycles = 2.39f, height = 0.72f, lean = 0.04f),
        )
    }
}
