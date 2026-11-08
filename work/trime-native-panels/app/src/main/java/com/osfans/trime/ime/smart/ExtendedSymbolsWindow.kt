// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

/** Two-level strip navigating one continuous, four-row inventory, as in the reference. */
class ExtendedSymbolsWindow(di: DI, initialCategory: String = "标点") : BoardWindow.NoBarBoardWindow(di) {
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val windows: BoardWindowManager by instance()
    private val common: CommonKeyboardActionListener by instance()
    private val inputBar: InputBarDelegate by instance()
    private fun dp(value: Int) = context.dp(value)
    // Reference video: 486 px keyboard width, 419 px symbol surface height.
    private fun referencePx(value: Int): Int {
        val metrics = context.resources.displayMetrics
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        return (shortSide * value / 486f).toInt()
    }
    private val ink get() = scope.colors.keyTextColor
    private val boardColor get() = scope.colors.keyboardBackColor
    private val selectedColor get() = ColorUtils.blendARGB(boardColor, ink, .12f)
    private val stripColor get() = ColorUtils.blendARGB(boardColor, ink, .035f)
    // A rounded child against the same keyboard background has invisible corners.
    // Keep this backing local to the panel; never recolor the shared IME container.
    private val cornerColor get() = if (ColorUtils.calculateLuminance(boardColor) > .45)
        Color.rgb(250, 250, 250) else Color.BLACK
    private fun surfaceBackground() = GradientDrawable().apply {
        setColor(boardColor)
        val radius = referencePx(32).toFloat()
        // The reference's lower corners belong to the phone display. Android's
        // navigation area remains native; reproduce only the panel's top corners.
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }
    private fun pill(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private var group = ReferenceSymbolCatalog.group(initialCategory) ?: ReferenceSymbolCatalog.groups.first()
    private var childMenu = group in ReferenceSymbolCatalog.extraGroups
    private var selectedPage = 0
    private var rootNavigationOffset = 0
    private var savedBarVisibility: Int? = null
    private var savedBoardHeight = 0
    private var attached = false
    private var generation = 0
    private data class Cell(val value: String?, val width: Int)
    private val cells = mutableListOf<Cell>()
    private val pageStarts = mutableListOf<Int>()
    private val tabs = mutableListOf<TextView>()
    private val icons = mutableListOf<LineIcon>()
    private val positions = mutableMapOf<String, android.os.Parcelable?>()
    private val layout by lazy { GridLayoutManager(context, 4, RecyclerView.HORIZONTAL, false) }

    private val adapter: RecyclerView.Adapter<SymbolHolder> = object : RecyclerView.Adapter<SymbolHolder>() {
        override fun getItemCount() = cells.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = SymbolHolder(TextView(context).apply {
            gravity = Gravity.CENTER; textSize = 20f; includeFontPadding = false
            typeface = Typeface.DEFAULT; setPadding(dp(4), 0, dp(4), 0)
        })
        override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
            val cell = cells[position]
            holder.label.apply {
                text = cell.value.orEmpty(); contentDescription = cell.value
                textSize = if (group.id == "emoticons") 16f else 20f
                setTextColor(ink); layoutParams = RecyclerView.LayoutParams(cell.width, -1)
                background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, 24)),
                    ColorDrawable(Color.TRANSPARENT), ColorDrawable(Color.WHITE))
                isClickable = cell.value != null; isFocusable = cell.value != null
                importantForAccessibility = if (cell.value == null) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { cell.value?.let { commit(it) } }
                setOnLongClickListener {
                    val choices = SymbolVariants.forKey(cell.value.orEmpty())
                    if (choices.isEmpty()) false else {
                        content.parent?.requestDisallowInterceptTouchEvent(true)
                        SymbolVariantPopup.show(this, choices, ::commit); true
                    }
                }
                setOnTouchListener { view, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> SymbolVariantPopup.move(view, event.x, event.y)
                        MotionEvent.ACTION_UP -> SymbolVariantPopup.release(view).also {
                            if (it) view.isPressed = false
                            content.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> SymbolVariantPopup.cancel(view).also {
                            if (it) view.isPressed = false
                            content.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        else -> false
                    }
                }
            }
        }
        override fun onViewRecycled(holder: SymbolHolder) {
            SymbolVariantPopup.cancel(holder.label); super.onViewRecycled(holder)
        }
    }
    private class SymbolHolder(val label: TextView) : RecyclerView.ViewHolder(label)
    private val content: RecyclerView by lazy { RecyclerView(context).apply {
        layoutManager = layout; adapter = this@ExtendedSymbolsWindow.adapter
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(dp(8), referencePx(12), dp(8), 0); clipToPadding = true
        itemAnimator = null; contentDescription = "符号内容，四行横向滚动"
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                val first = layout.findFirstVisibleItemPosition()
                val page = pageStarts.indexOfLast { it <= first }.coerceAtLeast(0)
                if (selectedPage != page) {
                    selectedPage = page; updateSelection()
                    if (childMenu) revealSelectedTab()
                }
            }
        })
    } }
    private val tabRow by lazy { LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL } }
    private val tabScroll by lazy { HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        setPadding(dp(2), dp(2), dp(2), dp(2)); clipToPadding = true
        addView(tabRow, ViewGroup.LayoutParams(-2, -1))
        contentDescription = "符号分类，横向滚动"
    } }
    private val navigation by lazy { LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), 0, dp(3), 0)
    } }
    private val menuContainer by lazy { LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; clipToOutline = true } }
    private val root by lazy { LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = surfaceBackground(); clipToOutline = true
        addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(navigation, LinearLayout.LayoutParams(-1, referencePx(40)).apply {
            leftMargin = dp(8); rightMargin = dp(8); topMargin = referencePx(2); bottomMargin = referencePx(5)
        })
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(20), 0)
            addView(icon("globe", "切换中英文") {
                common.listener.onSmartAction("toggle_language")
            }, LinearLayout.LayoutParams(dp(42), -1))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(icon("microphone", "语音输入") {
                common.listener.onAction(scope.theme.resolveAction("VOICE_ASSIST"))
            }, LinearLayout.LayoutParams(dp(42), -1))
        }, LinearLayout.LayoutParams(-1, referencePx(92)))
    } }
    private val surface by lazy { FrameLayout(context).apply {
        setBackgroundColor(cornerColor)
        addView(root, FrameLayout.LayoutParams(-1, -1))
    } }
    private inner class LineIcon(val kind: String, description: String) : View(context) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        init { isClickable = true; isFocusable = true; contentDescription = description }
        override fun onDraw(canvas: Canvas) {
            pen.color = ink; pen.strokeWidth = referencePx(2).toFloat().coerceAtLeast(1f)
            val cx = width / 2f; val cy = height / 2f; val r = referencePx(15).toFloat()
            when (kind) {
                "keyboard" -> {
                    pen.style = Paint.Style.FILL; pen.color = stripColor
                    canvas.drawRoundRect(RectF(cx-referencePx(20), cy-referencePx(18), cx+referencePx(20), cy+referencePx(18)),
                        referencePx(12).toFloat(), referencePx(12).toFloat(), pen)
                    pen.style = Paint.Style.STROKE; pen.color = ink
                    canvas.drawRoundRect(RectF(cx-r, cy-r*.7f, cx+r, cy+r*.7f), referencePx(3).toFloat(), referencePx(3).toFloat(), pen)
                    pen.style = Paint.Style.FILL
                    for (row in 0..1) for (col in 0..5) canvas.drawCircle(cx-r*.72f+col*r*.285f, cy-r*.32f+row*r*.34f, dp(1).toFloat()*.65f, pen)
                    canvas.drawRoundRect(RectF(cx-r*.57f, cy+r*.29f, cx+r*.57f, cy+r*.41f), 1f, 1f, pen)
                    pen.style = Paint.Style.STROKE
                }
                "globe" -> {
                    canvas.drawCircle(cx, cy, r, pen); canvas.drawOval(RectF(cx-r*.45f, cy-r, cx+r*.45f, cy+r), pen)
                    canvas.drawLine(cx-r, cy, cx+r, cy, pen)
                    for (side in listOf(-1f, 1f)) canvas.drawPath(android.graphics.Path().apply {
                        moveTo(cx-r*.9f, cy+side*r*.44f)
                        quadTo(cx, cy+side*r*.2f, cx+r*.9f, cy+side*r*.44f)
                    }, pen)
                }
                else -> {
                    canvas.drawRoundRect(RectF(cx-r*.36f, cy-r, cx+r*.36f, cy+r*.25f), r*.36f, r*.36f, pen)
                    canvas.drawArc(RectF(cx-r*.66f, cy-r*.5f, cx+r*.66f, cy+r*.6f), 0f, 180f, false, pen)
                    canvas.drawLine(cx, cy+r*.6f, cx, cy+r, pen); canvas.drawLine(cx-r*.38f, cy+r, cx+r*.38f, cy+r, pen)
                }
            }
        }
    }
    private fun icon(kind: String, label: String, action: () -> Unit) = LineIcon(kind, label).apply {
        background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, 25)), null, pill(Color.WHITE))
        setOnClickListener { action() }; icons.add(this)
    }
    private fun tab(label: String, action: () -> Unit) = TextView(context).apply {
        text = label; textSize = 15f; typeface = Typeface.DEFAULT; includeFontPadding = false
        gravity = Gravity.CENTER; setPadding(dp(5), 0, dp(5), 0); isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }
    private fun showGroup(next: ReferenceSymbolCatalog.Group) {
        rootNavigationOffset = tabScroll.scrollX
        val changed = next.id != group.id
        if (changed) {
            positions[group.id] = layout.onSaveInstanceState(); group = next; selectedPage = 0; rebuildContent()
        }
        childMenu = next.pages.size > 1 || next in ReferenceSymbolCatalog.extraGroups
        renderNavigation(preserveViewport = !changed)
    }
    private fun renderNavigation(preserveViewport: Boolean = true) {
        // A padded horizontal GridLayoutManager can otherwise anchor on the
        // preceding clipped column when the navigation strip requests layout.
        val anchor = if (preserveViewport) (0 until content.childCount).map { content.getChildAt(it) }
            .firstOrNull { layout.getDecoratedRight(it) > content.paddingLeft && layout.getDecoratedLeft(it) < content.width - content.paddingRight }
            ?.let { content.getChildAdapterPosition(it) to (layout.getDecoratedLeft(it) - content.paddingLeft) } else null
        generation++
        val currentGeneration = generation
        (tabScroll.parent as? ViewGroup)?.removeView(tabScroll)
        navigation.removeAllViews(); menuContainer.removeAllViews(); tabRow.removeAllViews(); tabs.clear()
        icons.removeAll { it.kind == "keyboard" }
        navigation.addView(icon("keyboard", "返回字母键盘") { windows.attachWindow(KeyboardWindow) },
            LinearLayout.LayoutParams(dp(40), -1).apply { rightMargin = dp(5) })
        navigation.addView(menuContainer, LinearLayout.LayoutParams(0, -1, 1f))
        if (childMenu) {
            menuContainer.addView(tab("‹ ${group.label}") {
                childMenu = false; renderNavigation()
            }.apply { contentDescription = "返回符号分类"; setTextColor(ink); setPadding(dp(7), 0, dp(7), 0) }, LinearLayout.LayoutParams(-2, -1))
            menuContainer.addView(View(context).apply {
                setBackgroundColor(ColorUtils.setAlphaComponent(ink, 22))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(1, referencePx(18)))
        }
        menuContainer.addView(tabScroll, LinearLayout.LayoutParams(0, -1, 1f))
        if (childMenu) group.pages.forEachIndexed { index, page ->
            tabRow.addView(tab(page.label) {
                selectedPage = index; layout.scrollToPositionWithOffset(pageStarts[index], 0); updateSelection()
            }.also { tabs.add(it) }, LinearLayout.LayoutParams(-2, -1))
        } else ReferenceSymbolCatalog.groups.forEach { item ->
            tabRow.addView(tab(item.label) { showGroup(item) }.also { tabs.add(it) }, LinearLayout.LayoutParams(-2, -1))
        }
        refreshColors()
        tabScroll.post {
            if (generation == currentGeneration) {
                if (anchor != null && anchor.first >= 0) layout.scrollToPositionWithOffset(anchor.first, anchor.second)
                if (childMenu) { tabScroll.scrollTo(0, 0); revealSelectedTab() }
                else tabScroll.scrollTo(rootNavigationOffset, 0)
            }
        }
    }
    private fun revealSelectedTab() {
        val selected = tabs.getOrNull(selectedPage) ?: return
        val left = selected.left; val right = selected.right
        when {
            left < tabScroll.scrollX -> tabScroll.smoothScrollTo(left, 0)
            right > tabScroll.scrollX + tabScroll.width -> tabScroll.smoothScrollTo(right - tabScroll.width + dp(4), 0)
        }
    }
    private fun updateSelection() {
        tabs.forEachIndexed { index, label ->
            val active = if (childMenu) selectedPage == index else ReferenceSymbolCatalog.groups[index].id == group.id
            label.isSelected = active; label.setTextColor(ink); label.background = if (active) pill(selectedColor, 14) else null
        }
    }
    private fun rebuildContent() {
        content.stopScroll(); cells.clear(); pageStarts.clear()
        val measure = Paint().apply { textSize = 20f * context.resources.displayMetrics.scaledDensity }
        group.pages.forEach { page ->
            pageStarts.add(cells.size)
            val width = if (group.id == "emoticons") dp(128) else
                ((page.symbols.maxOfOrNull { measure.measureText(it) } ?: 0f).toInt() + dp(20)).coerceIn(dp(44), dp(128))
            page.symbols.forEach { cells.add(Cell(it, width)) }
            repeat((4 - cells.size % 4) % 4) { cells.add(Cell(null, width)) }
        }
        adapter.notifyDataSetChanged()
        val saved = positions[group.id]
        if (saved != null) layout.onRestoreInstanceState(saved) else layout.scrollToPositionWithOffset(0, 0)
    }
    private fun commit(value: String) {
        if (common.tryEmailCandidates(value)) return
        val editor = service.currentInputEditorInfo
        service.postRimeJob {
            if (service.currentInputEditorInfo !== editor) return@postRimeJob
            if (statusCached.isComposing) commitComposition()
            if (service.currentInputEditorInfo !== editor) return@postRimeJob
            val ic = service.currentInputConnection
            val success = value.length == 1 && PairPunctuationHandler.insertPair(ic, value[0]) ||
                runCatching { ic?.commitText(value, 1) == true }.getOrDefault(false)
            if (!success) root.post { Toast.makeText(context, "无法上屏，请重新点选输入框", Toast.LENGTH_SHORT).show() }
        }
    }
    override fun onCreateView(): View = surface.also { rebuildContent(); renderNavigation(preserveViewport = false) }
    override fun onAttached() {
        if (!attached) {
            attached = true; savedBarVisibility = inputBar.view.visibility
            savedBoardHeight = windows.view.layoutParams.height
            // Reuse the candidate area; the reference panel may be taller.
            // onDetached restores the exact previous board height and bar visibility.
            val extra = if (inputBar.view.visibility != View.GONE) context.dp(inputBar.themedHeight) else 0
            inputBar.view.visibility = View.GONE
            val referenceHeight = if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
                referencePx(419) else 0
            windows.view.updateLayoutParams { height = maxOf(savedBoardHeight + extra, referenceHeight) }
        }
        refreshColors()
    }
    override fun onDetached() {
        generation++; content.stopScroll()
        for (i in 0 until content.childCount) SymbolVariantPopup.cancel(content.getChildAt(i))
        if (attached) {
            attached = false; inputBar.view.visibility = savedBarVisibility ?: View.VISIBLE
            windows.view.updateLayoutParams { height = savedBoardHeight }
        }
    }
    override fun refreshColors() {
        surface.setBackgroundColor(cornerColor); root.background = surfaceBackground(); menuContainer.background = pill(stripColor)
        updateSelection(); icons.forEach { it.invalidate() }
        for (i in 0 until content.childCount) (content.getChildAt(i) as? TextView)?.setTextColor(ink)
    }
}
