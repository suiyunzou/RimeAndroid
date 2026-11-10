package com.osfans.trime.ime.smart

import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.KeyBehavior
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardActionListener
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import kotlin.math.hypot

/** Owns one complete touch stream, so old key handlers cannot emit a second character. */
class SmartKeyboardTouchController(
    private val host: FrameLayout,
    private val service: TrimeInputMethodService,
    private val keys: List<Key>,
    private val listener: KeyboardActionListener,
) {
    private val density = host.resources.displayMetrics.density
    private val prefs = SmartInputPrefs(host.context)
    private val flick = KeyFlickGesture(density)
    private val glidePrefs = GlidePrefs(host.context)
    private val glide = GlideGestureSession(density)
    private val trail by lazy { GlideTrailView(host.context).also { host.addView(it, FrameLayout.LayoutParams(-1, -1)) } }
    private var glideAllowed = false
    private var glideGeneration = 0
    private var glideJob: Job? = null
    private class ComposingTouch(val editor: Any?, val scrubber: PinyinComposingScrubber, @Volatile var cancelled: Boolean = false) {
        var correction: CorrectionPresentation.Hint? = null
    }
    @Volatile private var composingTouch: ComposingTouch? = null
    private val spatial = SpatialCursorScrubber(density, { service.currentInputConnection }) { direction ->
        CursorDirectionFeedback.perform(host, direction)
    }
    private val wheel by lazy { RadialMenuView(host.context).also { host.addView(it, FrameLayout.LayoutParams(-1, -1)) } }
    private val preview by lazy { ReferenceGestureOverlay(host.context, keys).also { host.addView(it, FrameLayout.LayoutParams(-1, -1)) } }
    private var index = -1
    private var active = false
    private var cancelled = false
    private var mode = Mode.KEY
    private var startX = 0f
    private var startY = 0f
    private var downEventTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var originX = 0f
    private var originY = 0f
    private var composingAtDown = false
    private var cursorNavigated = false
    private var touchId = 0
    private var editorAtDown: Any? = null
    private enum class Mode { KEY, CURSOR, PINYIN, WHEEL, GLIDE }
    private val heldKey get() = keys.getOrNull(index)
    private val keyView get() = host.getChildAt(index) as? KeyView
    // Resolve the originating key once. A queued engine update must not change
    // the touch stream's identity, and MOVE must not repeatedly resolve labels.
    private var keyCode = 0
    private val longPress = Runnable {
        if (!active || cancelled || mode != Mode.KEY) return@Runnable
        if (keyCode == KeyEvent.KEYCODE_SPACE) {
            if (!composingAtDown) {
                mode = Mode.WHEEL
                keyView?.setPressedState(false)
                wheel.bringToFront()
                wheel.configureAppearance(prefs.wheelIconVisibility, prefs.wheelFocusStyle, prefs.wheelCursorPlacement, prefs.wheelMotionStyle)
                val footer = keys.filter { it.getCode(KeyBehavior.CLICK) == KeyEvent.KEYCODE_LANGUAGE_SWITCH || it.getCode(KeyBehavior.CLICK) == KeyEvent.KEYCODE_VOICE_ASSIST }.minOfOrNull { it.y }
                wheel.dimmedBottom = footer?.toFloat() ?: host.height.toFloat()
                wheel.begin(originX, originY, prefs.wheelSlots, prefs.wheelStyle, prefs.originGlow, prefs.followFinger)
                val editor = editorAtDown
                service.postRimeJob {
                    val traditional = if (schemaCached.schemaId.startsWith("wanxiang")) getRuntimeOption("traditionalization") else !getRuntimeOption("simplification")
                    host.post { if (active && mode == Mode.WHEEL && editorAtDown === editor) wheel.traditionalLabel = if (traditional) "繁" else "简" }
                }
            }
        } else if (CursorGesturePrefs(host.context).enabled && flick.advanceTime(SystemClock.uptimeMillis()) == KeyFlickGesture.Action.LONG_PRESS) {
            beginCursor()
        }
    }

    fun dispatch(event: MotionEvent, downKeyIndex: Int = -1, settlingOverlap: Boolean = false): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel()
            listener.clearGlideCandidates()
            index = if (downKeyIndex >= 0) downKeyIndex else keys.indexOfFirst {
                event.x >= it.x - it.extraWidthLeft && event.x < it.x + it.width + it.extraWidthRight &&
                    event.y >= it.y && event.y < it.y + it.height
            }
            keyCode = if (heldKey?.getLabel() == "ɐ") KeyEvent.KEYCODE_SHIFT_LEFT else heldKey?.getCode(KeyBehavior.CLICK) ?: 0
            val code = keyCode
            if (code !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z && code != KeyEvent.KEYCODE_SPACE && code != KeyEvent.KEYCODE_SHIFT_LEFT && code != KeyEvent.KEYCODE_SHIFT_RIGHT) return false
            active = true
            cancelled = false
            editorAtDown = service.currentInputEditorInfo
            mode = Mode.KEY
            startX = event.x; lastX = event.x
            startY = event.y; lastY = event.y
            downEventTime = event.eventTime
            originX = if (prefs.wheelOriginPlacement == "space_center") heldKey!!.x + heldKey!!.width / 2f else startX
            originY = if (prefs.wheelOriginPlacement == "space_center") heldKey!!.y + heldKey!!.height / 2f else startY
            composingAtDown = service.isRimeComposing
            glideAllowed = glidePrefs.enabled && code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z && !isSensitiveEditor()
            if (glideAllowed) glide.begin(event.x, event.y)
            keyView?.setPressedState(true)
            listener.onPress(code)
            InputFeedbackManager.keyPressVibrate(host, keyCode = code)
            flick.down(event.x, event.y, event.eventTime, code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z && CursorGesturePrefs(host.context).enabled)
            host.postDelayed(longPress, if (code == KeyEvent.KEYCODE_SPACE) 350L else
                (flick.longPressDeadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            return true
        }
        if (!active) return false
        if (service.currentInputEditorInfo !== editorAtDown) {
            cancelled = true
            composingTouch?.cancelled = true
            clearVisuals()
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelled = true
            composingTouch?.cancelled = true
            clearVisuals()
        }
        // Handler timers can overtake a queued release or second-thumb DOWN.
        // Compare the original event clock before interpreting that old sample
        // as movement inside a newly opened wheel.
        if (!cancelled && mode == Mode.WHEEL && event.eventTime < downEventTime + 350L &&
            (settlingOverlap || event.actionMasked == MotionEvent.ACTION_UP)) {
            wheel.cancel()
            mode = Mode.KEY
            keyView?.setPressedState(true)
        }
        if (!cancelled && mode == Mode.CURSOR && !cursorNavigated &&
            (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP)) {
            var restore = false
            for (h in 0 until event.historySize) {
                if (flick.restoreBeforeLongPress(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalEventTime(h))) {
                    restore = true
                    break
                }
            }
            if (!restore) restore = flick.restoreBeforeLongPress(event.x, event.y, event.eventTime,
                released = event.actionMasked == MotionEvent.ACTION_UP || settlingOverlap)
            if (restore) {
                mode = Mode.KEY
                preview.close()
                spatial.end()
                keyView?.setPressedState(true)
            }
        }
        if (!cancelled && mode == Mode.KEY && !glideAllowed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            for (h in 0 until event.historySize) {
                if (flick.move(event.getHistoricalX(h), event.getHistoricalY(h), event.getHistoricalEventTime(h)) == KeyFlickGesture.Action.LONG_PRESS) {
                    beginCursor()
                    break
                }
            }
            if (!flick.longPressPending) host.removeCallbacks(longPress)
        }
        if (!cancelled) when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                when (mode) {
                    Mode.WHEEL -> wheel.update(event.x, event.y)
                    Mode.CURSOR -> {
                        if (hypot(dx, dy) > 0f) cursorNavigated = true
                        spatial.move(dx, dy); refreshCursorPreview(event.x, event.y)
                    }
                    Mode.PINYIN -> movePinyin(dx, dy)
                    Mode.GLIDE -> { glide.move(event.x, event.y); trail.move(event.x, event.y) }
                    Mode.KEY -> {
                        if (hypot(event.x - startX, event.y - startY) > 8f * density) host.removeCallbacks(longPress)
                        if (keyCode == KeyEvent.KEYCODE_SPACE && composingAtDown && PinyinGesturePrefs(host.context).enabled && hypot(event.x - startX, event.y - startY) > 8f * density) {
                            beginPinyin(event.x - startX, event.y - startY)
                        } else if (glideAllowed) {
                            if (glide.move(event.x, event.y)) {
                                mode = Mode.GLIDE
                                keyView?.setPressedState(false)
                                host.removeCallbacks(longPress)
                                trail.begin(startX, startY)
                                trail.move(event.x, event.y)
                            }
                        } else {
                            val action = flick.move(event.x, event.y, event.eventTime)
                            if (action == KeyFlickGesture.Action.LONG_PRESS) beginCursor()
                            if (flick.pendingFlick != KeyFlickGesture.Action.NONE) {
                                val up = flick.pendingFlick == KeyFlickGesture.Action.FLICK_UP || flick.pendingFlick == KeyFlickGesture.Action.LOCK_FLICK_UP
                                if (prefs.allowsFlick(up)) {
                                    heldKey?.keyPressOffsetY = 2f
                                    keyView?.showFlickPreview(up)
                                }
                            }
                        }
                    }
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP -> when (mode) {
                Mode.WHEEL -> wheel.release(event.x, event.y)?.let { listener.onSmartAction(it) }
                Mode.CURSOR -> spatial.move(event.x - lastX, event.y - lastY)
                Mode.PINYIN -> movePinyin(event.x - lastX, event.y - lastY)
                Mode.GLIDE -> {
                    val result = glide.finish(event.x, event.y)
                    if (result.kind == GlideGestureSession.Kind.GLIDE) decodeGlide(result.points)
                }
                Mode.KEY -> if (glideAllowed) {
                    if (glide.finish(event.x, event.y).kind == GlideGestureSession.Kind.TAP) keyView?.smartTap()
                } else if (isStationaryTapOnHeldKey(event.x, event.y)) {
                    // A tap stays a tap for the whole key face. The flick classifier
                    // drops a press that rolled farther than the flick distance without
                    // reaching it; using the key rect prevents a lost or a later key.
                    keyView?.smartTap()
                } else when (flick.up(event.x, event.y, event.eventTime)) {
                    KeyFlickGesture.Action.TAP -> keyView?.smartTap()
                    KeyFlickGesture.Action.FLICK_UP -> dispatchFlick(true)
                    KeyFlickGesture.Action.FLICK_DOWN -> dispatchFlick(false)
                    KeyFlickGesture.Action.LOCK_FLICK_UP -> dispatchFlick(true, lock = true)
                    KeyFlickGesture.Action.LOCK_FLICK_DOWN -> dispatchFlick(false, lock = true)
                    else -> Unit
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && !cancelled && mode == Mode.KEY &&
            FeedbackTuningPrefs(host.context).configured) InputFeedbackManager.keyReleaseFeedback(host, keyCode)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) cancel(
            finishPinyin = event.actionMasked == MotionEvent.ACTION_UP && !cancelled,
            preserveGlide = event.actionMasked == MotionEvent.ACTION_UP && !cancelled && mode == Mode.GLIDE,
        )
        return true
    }

    /** True while the current press can still end as a plain tap on its original key. */
    private fun isStationaryTapOnHeldKey(x: Float, y: Float): Boolean {
        val key = heldKey ?: return false
        val inside = KeyTapSettle.isInsideKey(
            x, y,
            (key.x - key.extraWidthLeft).toFloat(),
            (key.x + key.width + key.extraWidthRight).toFloat(),
            key.y.toFloat(),
            (key.y + key.height).toFloat(),
        )
        return KeyTapSettle.canSettle(flick.state, flick.pendingFlick, inside)
    }

    /** Commit an earlier letter/space tap before the next thumb starts, even if it lifts last. */
    fun settleOverlappingTap(x: Float, y: Float): Boolean {
        if (!active || cancelled || mode != Mode.KEY ||
            (keyCode !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z && keyCode != KeyEvent.KEYCODE_SPACE) ||
            (glideAllowed && !glide.isStationaryTap)) return false
        // A fast two-thumb rollover can move the first finger past the old 8dp
        // stationary limit while still staying on its key. Settling only the
        // perfectly still press made the second thumb's letter land first, which
        // users see as a left-hand letter turning into the right-hand one.
        val stationary = flick.finishOverlappingTap(x, y) == KeyFlickGesture.Action.TAP
        if (!stationary && !isStationaryTapOnHeldKey(x, y)) return false
        keyView?.smartTap()
        if (FeedbackTuningPrefs(host.context).configured) InputFeedbackManager.keyReleaseFeedback(host, keyCode)
        // The physical finger stays tracked by the dispatcher, but its eventual UP is inert.
        cancel()
        return true
    }

    private fun dispatchFlick(up: Boolean, lock: Boolean = false) {
        if (glidePrefs.enabled || !prefs.allowsFlick(up)) return
        val name = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> "space"
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "shift"
            else -> ('a'.code + keyCode - KeyEvent.KEYCODE_A).toChar().toString()
        }
        val symbolAction = if (lock) "lock_flick_symbol:" else "flick_symbol:"
        val custom = prefs.binding(name, up)
        when {
            custom != null -> listener.onSmartAction(resolveFlickBinding(custom, lock))
            name == "shift" -> if (heldKey?.keyActions?.containsKey(KeyBehavior.SWIPE_DOWN) == true) keyView?.smartFlick(up) else listener.onSmartAction("english")
            name.length == 1 && name[0] in "qwertyuiop" -> listener.onSmartAction(symbolAction + "1234567890"["qwertyuiop".indexOf(name[0])])
            else -> {
                val behavior = if (up) KeyBehavior.SWIPE_UP else KeyBehavior.SWIPE_DOWN
                val mapped = heldKey?.keyActions?.get(behavior)
                    ?: if (up) heldKey?.keyActions?.get(KeyBehavior.SWIPE_DOWN) else null
                val symbol = mapped?.commit?.takeIf { it.isNotEmpty() }
                    ?: if (mapped == null) heldKey?.symbolLabel?.trim()?.takeIf { it.isNotEmpty() } else null
                if (symbol != null) listener.onSmartAction(symbolAction + symbol)
                else keyView?.smartFlick(up)
            }
        }
        InputFeedbackManager.keyPressVibrate(host, keyCode = keyCode)
    }

    companion object {
        /** Text bindings still participate in hold-to-lock; commands and empty bindings do not. */
        internal fun resolveFlickBinding(binding: String, lock: Boolean): String =
            if (lock && binding.startsWith("text:") && binding.length > 5)
                "lock_flick_symbol:" + binding.removePrefix("text:") else binding
    }

    private fun decodeGlide(points: List<GlideDecoder.Point>) {
        val editor = editorAtDown ?: return
        val generation = glideGeneration
        val keyCenters = keys.mapNotNull { key ->
            val code = key.getCode(KeyBehavior.CLICK)
            if (code !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) null
            else GlideDecoder.KeyCenter(('a'.code + code - KeyEvent.KEYCODE_A).toChar(), key.x + key.width / 2f, key.y + key.height / 2f, key.width.toFloat())
        }
        service.postRimeJob {
            val englishOnly = statusCached.isAsciiMode
            host.post {
                if (generation != glideGeneration || service.currentInputEditorInfo !== editor || !host.isAttachedToWindow) return@post
                glideJob = service.lifecycleScope.launch {
                    val candidates = runCatching { GlideRuntime.decode(host.context, points, keyCenters, englishOnly) }.getOrDefault(emptyList())
                    if (!isActive || generation != glideGeneration || service.currentInputEditorInfo !== editor || !host.isAttachedToWindow) return@launch
                    listener.showGlideCandidates(candidates) { candidate ->
                        if (generation == glideGeneration && service.currentInputEditorInfo === editor) {
                            glideGeneration++
                            listener.onSmartAction("text:${candidate.word}")
                        }
                    }
                }
            }
        }
    }

    private fun beginCursor() {
        host.removeCallbacks(longPress)
        mode = Mode.CURSOR
        cursorNavigated = false
        keyView?.setPressedState(false)
        spatial.begin()
        refreshCursorPreview(lastX, lastY)
        InputFeedbackManager.keyPressVibrate(host, true, keyCode)
    }

    private fun isSensitiveEditor(): Boolean {
        val info = service.currentInputEditorInfo ?: return true
        return OfflinePrivacyPolicy.isSensitive(info.inputType, info.imeOptions)
    }

    private fun refreshCursorPreview(x: Float, y: Float) {
        if (isSensitiveEditor()) {
            preview.showCursor("", 0, x, y)
            return
        }
        val connection = service.currentInputConnection
        val extracted = runCatching {
            connection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply {
                hintMaxChars = 4096
                hintMaxLines = 80
            }, 0)
        }.getOrNull()
        val snapshot = extracted?.text?.toString()
        // selectionEnd is relative to the returned text, even for a nonzero startOffset.
        // A full snapshot keeps line wrapping stable while the caret moves through it.
        if (snapshot != null && extracted.partialStartOffset < 0 && extracted.selectionEnd in 0..snapshot.length) {
            preview.showCursor(snapshot, extracted.selectionEnd, x, y)
            return
        }
        val before = runCatching { connection?.getTextBeforeCursor(1200, 0)?.toString().orEmpty() }.getOrDefault("")
        val after = runCatching { connection?.getTextAfterCursor(600, 0)?.toString().orEmpty() }.getOrDefault("")
        preview.showCursor(before + after, before.length, x, y)
    }

    private fun beginPinyin(dx: Float, dy: Float) {
        mode = Mode.PINYIN
        keyView?.setPressedState(false)
        val touch = ComposingTouch(editorAtDown, PinyinComposingScrubber(density)).also { composingTouch = it }
        service.postRimeJob {
            if (touch.cancelled || service.currentInputEditorInfo !== touch.editor || !statusCached.isComposing) return@postRimeJob
            val raw = getRawInput()
            val byteCaret = getRawCaret().coerceIn(0, raw.toByteArray(Charsets.UTF_8).size)
            val charCaret = raw.toByteArray(Charsets.UTF_8).copyOfRange(0, byteCaret).toString(Charsets.UTF_8).length
            if (touch.cancelled || service.currentInputEditorInfo !== touch.editor || !statusCached.isComposing) return@postRimeJob
            touch.scrubber.begin(raw, charCaret)
            touch.correction = if (isSensitiveEditor()) null else getCandidates(0, 5).asSequence()
                .mapNotNull { CorrectionPresentation.parse(it.comment) }.firstOrNull { !it.sentence }
            touch.scrubber.move(dx, dy)?.let { moveCursorPos(touch.scrubber.rimeCaretPosition()) }
            val rawPreview = touch.scrubber.preview
            host.post {
                if (composingTouch === touch && mode == Mode.PINYIN && !touch.cancelled && !isSensitiveEditor()) {
                    preview.showPinyin(rawPreview, touch.correction)
                }
            }
        }
    }

    private fun movePinyin(dx: Float, dy: Float) {
        val touch = composingTouch ?: return
        service.postRimeJob {
            if (touch.cancelled || service.currentInputEditorInfo !== touch.editor || !statusCached.isComposing) return@postRimeJob
            touch.scrubber.move(dx, dy)?.let { moveCursorPos(touch.scrubber.rimeCaretPosition()) }
            val rawPreview = touch.scrubber.preview
            host.post { if (composingTouch === touch && mode == Mode.PINYIN && !touch.cancelled && !isSensitiveEditor()) preview.showPinyin(rawPreview, touch.correction) }
        }
    }

    private fun clearVisuals() {
        host.removeCallbacks(longPress)
        keyView?.showFlickPreview(null)
        keyView?.setPressedState(false)
        heldKey?.keyPressOffsetY = 0f
        if (mode == Mode.WHEEL) wheel.cancel()
        if (mode == Mode.CURSOR || mode == Mode.PINYIN) preview.close()
        spatial.end()
        flick.cancel()
        glide.cancel()
        if (mode == Mode.GLIDE) trail.clear()
    }

    fun cancel(finishPinyin: Boolean = false, preserveGlide: Boolean = false) {
        if (!preserveGlide) {
            glideGeneration++
            glideJob?.cancel()
            glideJob = null
            listener.clearGlideCandidates()
        }
        if (!finishPinyin) composingTouch?.cancelled = true
        composingTouch = null
        clearVisuals()
        active = false
        cancelled = false
        index = -1
        keyCode = 0
        touchId++
    }
}
