/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.recyclerview.widget.RecyclerView

/**
 * Local click gate for the two liquid-panel scrollers. RecyclerView retains
 * normal scroll handling; crossing touchSlop latches until the next DOWN.
 * The first touch during a fling only stops it, even if that touch is a tap.
 */
class ScrollSafeRecyclerView(context: Context) : RecyclerView(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var stoppingFling = false
    private var downChild: View? = null

    val canActivateItem: Boolean
        get() = !moved && !stoppingFling && scrollState == SCROLL_STATE_IDLE

    fun cancelActiveGesture() {
        moved = true
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        downChild?.dispatchTouchEvent(cancel)
        cancel.recycle()
        downChild = null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                moved = false
                stoppingFling = scrollState != SCROLL_STATE_IDLE
                downChild = null
                if (stoppingFling) {
                    stopScroll()
                } else {
                    downChild = findChildViewUnder(event.x, event.y)
                }
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!moved && (event.pointerCount > 1 || dx * dx + dy * dy > slop * slop)) {
                    moved = true
                    // GestureFrame's larger keyboard swipe threshold must not
                    // leave a repeat/long-press job armed while this list scrolls.
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    downChild?.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            MotionEvent.ACTION_CANCEL -> moved = true
        }
        if (stoppingFling) return true
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            downChild = null
        }
        return handled
    }
}
