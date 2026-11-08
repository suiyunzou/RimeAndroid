// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.osfans.trime.data.theme.ColorManager
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot

/** A single rounded strip inside the IME window; draft selection never enters the editor. */
object SymbolVariantPopup {
    private class Session(val anchor: View, val window: PopupWindow, val row: LinearLayout, val viewport: View,
        val symbols: List<String>, val cells: List<Button>, val foreground: Int, val background: Int,
        val originRawX: Float, val originRawY: Float,
        val onCommit: (String) -> Unit) {
        var selected = -1
        var moved = false
        private val dragSlop = ViewConfiguration.get(anchor.context).scaledTouchSlop.toFloat()
            .coerceAtLeast(8f * anchor.resources.displayMetrics.density)
        fun highlight(index: Int) {
            selected = index
            cells.forEachIndexed { i, cell ->
                cell.setTextColor(foreground)
                cell.background = GradientDrawable().apply {
                    setColor(if (i == index) ColorUtils.blendARGB(background,foreground,.17f) else Color.TRANSPARENT)
                    cornerRadius = 24f * row.resources.displayMetrics.density
                }
            }
        }
        fun at(rawX: Float, rawY: Float, directTouch: Boolean = false) {
            if (!rawX.isFinite() || !rawY.isFinite()) return
            val anchorLocation = IntArray(2).also { anchor.getLocationOnScreen(it) }
            if (!moved && !directTouch) {
                // Compare with the original held-key DOWN, not the first MOVE:
                // stationary events cannot select, and a coalesced first drag is retained.
                if (hypot(rawX - originRawX, rawY - originRawY) <= dragSlop) return
            }
            moved = true
            val location = IntArray(2).also { row.getLocationOnScreen(it) }
            val viewportLocation = IntArray(2).also { viewport.getLocationOnScreen(it) }
            val slop = 12f * row.resources.displayMetrics.density
            val index = if (rawX < viewportLocation[0] || rawX >= viewportLocation[0] + viewport.width ||
                rawY < location[1] - slop || rawY > anchorLocation[1] + anchor.height + slop) -1
                else cells.indexOfFirst { cell ->
                    val p = IntArray(2).also { cell.getLocationOnScreen(it) }
                    rawX >= p[0] && rawX < p[0] + cell.width
                }
            highlight(index)
        }
    }
    private var current: Session? = null

    /** Coordinates are local to the anchor, matching KeyView.onMove. */
    fun move(anchor: View, x: Float, y: Float): Boolean {
        val session = current?.takeIf { it.anchor === anchor && it.window.isShowing } ?: return false
        val p = IntArray(2).also { anchor.getLocationOnScreen(it) }
        session.at(p[0] + x, p[1] + y)
        return true
    }
    /** With no movement, keep the strip open for a direct accessible tap. */
    fun release(anchor: View): Boolean {
        val session = current?.takeIf { it.anchor === anchor && it.window.isShowing } ?: return false
        if (session.moved) {
            val symbol = session.symbols.getOrNull(session.selected)
            session.window.dismiss()
            if (symbol != null) session.onCommit(symbol)
        }
        return true
    }
    fun cancel(anchor: View): Boolean {
        val session = current?.takeIf { it.anchor === anchor } ?: return false
        session.window.dismiss()
        return true
    }

    /** Preserve both positional callbacks and the original trailing-lambda API. */
    fun show(anchor: View, symbols: List<String>, onCommit: (String) -> Unit): PopupWindow? =
        show(anchor, symbols, onCommit, initialX = null, initialY = null)

    fun show(anchor: View, symbols: List<String>, onCommit: (String) -> Unit,
        initialX: Float?, initialY: Float?, onExpand: (() -> Unit)? = null): PopupWindow? {
        if (!anchor.isAttachedToWindow || symbols.isEmpty()) return null
        current?.window?.dismiss()
        val density = anchor.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val foreground = runCatching { ColorManager.getColor("key_text_color") }.getOrDefault(Color.BLACK)
        val background = runCatching { ColorManager.getColor("key_back_color") }.getOrDefault(Color.WHITE)
        val choices = symbols.distinct().let { if (onExpand != null) it.take(7) else it }
        val root = anchor.rootView
        val width = (root.width - dp(12)).coerceAtLeast(dp(42))
        val row = LinearLayout(anchor.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val scrollable = choices.size > 10
        val cellWidth = (width - dp(6)) / 10
        val viewport = if (scrollable) HorizontalScrollView(anchor.context).apply {
            contentDescription = "长按字符候选"
            isHorizontalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(10))
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row, FrameLayout.LayoutParams(cellWidth * choices.size + dp(6), -1))
        } else row
        val window = PopupWindow(viewport, width, dp(42), false).apply {
            isOutsideTouchable = true
            isTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(background); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), (foreground and 0x00ffffff) or 0x24000000)
            })
            elevation = dp(2).toFloat()
        }
        val cells = choices.map { symbol ->
            Button(anchor.context).apply {
                text = if (symbol.length == 1 && symbol[0] in '\uff01'..'\uff5e') {
                    SpannableString("${symbol}全").apply {
                        setSpan(RelativeSizeSpan(.5f), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        setSpan(SuperscriptSpan(), 1, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } else symbol
                textSize = if(onExpand!=null)16f else if (choices.size > 9) 17f else 21f
                isAllCaps = false; includeFontPadding = false
                setTextColor(foreground)
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(0, 0, 0, 0)
                contentDescription = "输入 $symbol"
                setOnClickListener { window.dismiss(); onCommit(symbol) }
                row.addView(this, if (scrollable) LinearLayout.LayoutParams(cellWidth, -1)
                    else LinearLayout.LayoutParams(0, -1, 1f))
            }
        }
        if(onExpand!=null)row.addView(Button(anchor.context).apply{
            text="⌄";textSize=22f;setTextColor(foreground);this.background=null
            minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(0,0,0,0)
            contentDescription="展开颜文字";setOnClickListener{window.dismiss();onExpand()}
        },LinearLayout.LayoutParams(dp(30),-1))
        val anchorOrigin = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val session = Session(anchor, window, row, viewport, choices, cells, foreground, background,
            anchorOrigin[0] + (initialX?.takeIf { it.isFinite() } ?: anchor.width / 2f),
            anchorOrigin[1] + (initialY?.takeIf { it.isFinite() } ?: anchor.height / 2f), onCommit)
        current = session
        session.highlight(-1)
        cells.forEachIndexed { index, cell ->
            if (scrollable) {
                // After lifting the original key, native scrolling owns horizontal
                // swipes. Its child CANCEL must not dismiss the whole popup.
                cell.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> session.highlight(index)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> session.highlight(-1)
                        MotionEvent.ACTION_POINTER_DOWN -> { cancel(anchor); return@setOnTouchListener true }
                    }
                    false
                }
                return@forEachIndexed
            }
            cell.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    // This is a fresh touch inside the strip, not the original held key.
                    // A direct tap must select immediately even with zero displacement.
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { session.at(event.rawX, event.rawY, directTouch = true); true }
                    MotionEvent.ACTION_UP -> { session.at(event.rawX, event.rawY, directTouch = true); release(anchor); true }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { cancel(anchor); true }
                    else -> false
                }
            }
        }
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { window.dismiss() }
        }
        anchor.addOnAttachStateChangeListener(detach)
        val keyboard = generateSequence(anchor.parent) { it.parent }.filterIsInstance<View>()
            .firstOrNull { it.javaClass.simpleName == "KeyboardView" }
        val dim = ColorDrawable(ColorUtils.setAlphaComponent(ColorManager.currentScope()?.colors?.keyboardBackColor ?: background,130)).apply {
            setBounds(0,0,keyboard?.width?:0,((keyboard?.height?:0)-dp(36)).coerceAtLeast(0))
        }
        keyboard?.overlay?.add(dim)
        window.setOnDismissListener {
            keyboard?.overlay?.remove(dim)
            anchor.removeOnAttachStateChangeListener(detach)
            if (current === session) current = null
        }
        return runCatching {
            val location = IntArray(2).also { anchor.getLocationInWindow(it) }
            val keyboardTop = keyboard?.let { view -> IntArray(2).also { view.getLocationInWindow(it) }[1] }
            window.showAtLocation(root, Gravity.TOP or Gravity.LEFT,
                dp(6),
                ((keyboardTop ?: location[1]) - window.height - dp(3)).coerceAtLeast(0))
            window
        }.getOrElse { keyboard?.overlay?.remove(dim); anchor.removeOnAttachStateChangeListener(detach); if (current === session) current = null; null }
    }
}
