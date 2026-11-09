package com.osfans.trime.ime.smart

import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.KeyView
import com.osfans.trime.ime.keyboard.KeyboardActionListener

/** Each finger owns a complete key stream; overlapping thumb taps must not cancel one another. */
class SmartKeyboardPointerDispatcher(
    private val host: FrameLayout,
    private val service: TrimeInputMethodService,
    private val keys: List<Key>,
    private val listener: KeyboardActionListener,
) {
    private class Stream(val controller: SmartKeyboardTouchController) {
        var pointerId = -1
        var downTime = 0L
        var key: KeyView? = null
        var keyIndex = -1
        var smart = false
        var x = 0f
        var y = 0f
    }
    private val streams = ArrayList<Stream>(2)

    fun dispatch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                begin(event, event.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                streams.filter { it.pointerId >= 0 && it.smart }.forEach { stream ->
                    val index = event.findPointerIndex(stream.pointerId)
                    if (index >= 0) {
                        // POINTER_DOWN can carry movement since the last MOVE.
                        // Replay it before deciding whether the older finger is
                        // a stationary tap, flick, cursor gesture, or glide.
                        deliver(stream, event, index, MotionEvent.ACTION_MOVE, settlingOverlap = true)
                        stream.controller.settleOverlappingTap(event.getX(index), event.getY(index))
                    }
                }
                begin(event, event.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> streams.filter { it.pointerId >= 0 }.forEach { stream ->
                val index = event.findPointerIndex(stream.pointerId)
                if (index >= 0) deliver(stream, event, index, MotionEvent.ACTION_MOVE)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                streams.firstOrNull { it.pointerId == event.getPointerId(index) }?.let { stream ->
                    deliver(stream, event, index, MotionEvent.ACTION_UP)
                    stream.pointerId = -1
                    stream.key = null
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun begin(event: MotionEvent, pointerIndex: Int) {
        val stream = streams.firstOrNull { it.pointerId < 0 }
            ?: Stream(SmartKeyboardTouchController(host, service, keys, listener)).also { streams.add(it) }
        stream.pointerId = event.getPointerId(pointerIndex)
        stream.downTime = event.eventTime
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val keyIndex = keys.indexOfFirst { x >= it.x - it.extraWidthLeft && x < it.x + it.width + it.extraWidthRight && y >= it.y && y < it.y + it.height }
        stream.keyIndex = keyIndex
        stream.key = host.getChildAt(keyIndex) as? KeyView
        deliver(stream, event, pointerIndex, MotionEvent.ACTION_DOWN)
    }

    private fun deliver(stream: Stream, event: MotionEvent, index: Int, action: Int, settlingOverlap: Boolean = false) {
        stream.x = event.getX(index)
        stream.y = event.getY(index)
        val single = if (action == MotionEvent.ACTION_MOVE && event.historySize > 0) {
            MotionEvent.obtain(stream.downTime, event.getHistoricalEventTime(0), action,
                event.getHistoricalX(index, 0), event.getHistoricalY(index, 0), event.metaState).apply {
                for (h in 1 until event.historySize) {
                    addBatch(event.getHistoricalEventTime(h), event.getHistoricalX(index, h),
                        event.getHistoricalY(index, h), 1f, 1f, event.metaState)
                }
                addBatch(event.eventTime, stream.x, stream.y, 1f, 1f, event.metaState)
            }
        } else MotionEvent.obtain(stream.downTime, event.eventTime, action, stream.x, stream.y, event.metaState)
        try {
            if (action == MotionEvent.ACTION_DOWN) stream.smart = stream.controller.dispatch(single, stream.keyIndex)
            else if (stream.smart) stream.controller.dispatch(single, settlingOverlap = settlingOverlap)
            if (!stream.smart) stream.key?.let { key ->
                single.offsetLocation(-key.x, -key.y)
                key.dispatchTouchEvent(single)
            }
        } finally {
            single.recycle()
        }
    }

    fun cancel() {
        val now = SystemClock.uptimeMillis()
        streams.forEach { stream ->
            if (stream.pointerId >= 0 && !stream.smart) stream.key?.let { key ->
                val event = MotionEvent.obtain(stream.downTime, now, MotionEvent.ACTION_CANCEL, stream.x - key.x, stream.y - key.y, 0)
                try { key.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            stream.controller.cancel()
            stream.pointerId = -1
            stream.key = null
        }
    }
}
