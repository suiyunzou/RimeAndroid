/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.segments

import android.app.SearchManager
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.FrameLayout
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.smart.OfflinePrivacyPolicy
import androidx.lifecycle.lifecycleScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.LoadState
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.R
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ime.smart.SmartClipboardTokenizer
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.util.NativeTokenizer
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.systemservices.clipboardManager

class SegmentsWindow(di: DI, private val source: String, private val smartMode: Boolean = true) : BoardWindow.BarBoardWindow(di) {
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme
    private val windowManager: BoardWindowManager by instance()
    private val inputBar: InputBarDelegate by instance()
    private val common: CommonKeyboardActionListener by instance()
    private var attached = false
    private var previousBar = View.VISIBLE
    private var previousHeight = 0
    private fun px(n: Int) = (minOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels) * n / 486f).toInt()
    private val board get() = scope.colors.keyboardBackColor
    private val ink get() = scope.colors.keyTextColor
    private fun rounded(color: Int, radius: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = px(radius).toFloat() }

    override val title: String by lazy {
        context.getString(R.string.word_segment)
    }

    private val adapter by lazy {
        SegmentsAdapter(scope, { onSelectionChanged() }, source)
    }

    private val touchListener by lazy {
        DragSelectTouchListener(context, adapter) {
            onSelectionChanged()
        }
    }

    private val ui by lazy {
        SegmentsUi(context, scope).apply {
            recyclerView.apply {
                layoutManager = FlexboxLayoutManager(context).apply {
                    flexDirection = FlexDirection.ROW
                    flexWrap = FlexWrap.WRAP
                    alignItems = AlignItems.FLEX_START
                }
                adapter = this@SegmentsWindow.adapter
            }
            selectButton.setOnClickListener {
                if (adapter.isAllSelected) {
                    adapter.deselect()
                } else {
                    adapter.selectAll()
                }
                onSelectionChanged()
            }
            shareButton.apply {
                isEnabled = false
                setOnClickListener {
                    val text = adapter.joinedSegments
                    val target = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    val chooser = Intent.createChooser(target, null).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(chooser)
                }
            }
            searchButton.apply {
                isEnabled = false
                setOnClickListener {
                    val text = adapter.joinedSegments
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra(SearchManager.QUERY, text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    service.startActivity(intent)
                }
            }
            starButton.apply {
                isEnabled = false
                setOnClickListener {
                    val text = adapter.joinedSegments
                    service.lifecycleScope.launch {
                        CollectionHelper.addNewBean(text)
                    }
                    ctx.toast(R.string.star_success)
                }
            }
            copyButton.apply {
                isEnabled = false
                setOnClickListener {
                    val text = adapter.joinedSegments
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("", text))
                    windowManager.attachWindow(KeyboardWindow)
                }
            }
        }
    }

    private val toolbarButtons by lazy {
        val buttonSpacing = theme.toolBar.buttonSpacing
        FlexboxLayout(context).apply {
            flexDirection = FlexDirection.ROW_REVERSE
            alignItems = AlignItems.CENTER
            val buttons = listOf(ui.selectButton, ui.copyButton, ui.starButton, ui.searchButton, ui.shareButton)
            val size = theme.style.run { candidateViewHeight + commentHeight }
            buttons.forEachIndexed { index, button ->
                val lParams = FlexboxLayout.LayoutParams(dp(size), dp(size))
                if (index > 0) {
                    lParams.marginEnd = dp(buttonSpacing)
                }
                addView(button, lParams)
            }
        }
    }

    override val showTitle: Boolean get() = !smartMode
    override fun onCreateBarView(): View? = if (smartMode) null else toolbarButtons

    private var tokenFilter = SmartClipboardTokenizer.Filter.ALL
    private var currentSource = source
    private var historyVisible = false
    private var historyJob: Job? = null
    private var attachmentGeneration = 0
    private var tokenPage = 0
    private val cardsPerPage = 100
    private val tokens: MutableList<SmartClipboardTokenizer.Token> by lazy { if (OfflinePrivacyPolicy.sensitiveEditor) mutableListOf() else SmartClipboardTokenizer.tokenize(source) { NativeTokenizer.tokenize(it) }.toMutableList() }
    private val smartCards by lazy {
        FlexboxLayout(context).apply {
            flexDirection = FlexDirection.ROW
            flexWrap = FlexWrap.WRAP
            alignItems = AlignItems.FLEX_START
            alignContent = com.google.android.flexbox.AlignContent.FLEX_START
            setPadding(px(18), px(4), px(18), px(4))
        }
    }
    private val filterButtons = linkedMapOf<SmartClipboardTokenizer.Filter, TextView>()
    private fun actionButton(label: String, action: () -> Unit) =
        com.osfans.trime.ime.smart.ReferencePanelStyle.button(context, scope, label, action).apply { textSize = 13f }
    private val smartToolbar by lazy {
        LinearLayout(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(px(18), px(23), px(18), px(8))
            addView(toolIcon("back", "返回键盘") { windowManager.attachWindow(KeyboardWindow) }, LinearLayout.LayoutParams(px(48), px(42)))
            val filters = LinearLayout(context).apply { background = rounded(ColorUtils.blendARGB(board, ink, .07f), 22) }
            listOf("数字" to SmartClipboardTokenizer.Filter.NUMBERS, "链接" to SmartClipboardTokenizer.Filter.URLS).forEach { (label, filter) ->
                val tab = TextView(context).apply {
                    text = label; textSize = 13f; gravity = Gravity.CENTER; setTextColor(ink)
                    contentDescription = if (filter == SmartClipboardTokenizer.Filter.NUMBERS) "纯数字" else "网址"
                    setOnClickListener { tokenFilter = if (tokenFilter == filter) SmartClipboardTokenizer.Filter.ALL else filter; tokenPage = 0; renderSmartCards() }
                }
                filterButtons[filter] = tab
                filters.addView(tab, LinearLayout.LayoutParams(px(55), -1))
            }
            addView(filters, LinearLayout.LayoutParams(px(110), px(42)))
            addView(toolIcon("history", "历史") { showHistory() }, LinearLayout.LayoutParams(px(44), px(42)).apply { leftMargin = px(16) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(toolIcon("delete", "退格") { common.listener.onAction(theme.resolveAction("BackSpace")) }, LinearLayout.LayoutParams(px(48), px(42)))
            addView(toolIcon("insert", "粘贴全部") { commit(currentSource) }, LinearLayout.LayoutParams(px(48), px(42)).apply { leftMargin = px(12) })
        }
    }
    private val smartScroll by lazy { ScrollView(context).apply { isFillViewport = true; addView(smartCards) } }
    private val historyList by lazy { ClipboardHistoryView(context, scope) { value ->
        if (!OfflinePrivacyPolicy.sensitiveEditor) {
            currentSource = value
            tokens.clear()
            tokens.addAll(SmartClipboardTokenizer.tokenize(value) { NativeTokenizer.tokenize(it) })
            tokenFilter = SmartClipboardTokenizer.Filter.ALL
            tokenPage = 0
            hideHistory()
            renderSmartCards()
            smartScroll.scrollTo(0, 0)
        }
    }.apply {
        clips.addLoadStateListener { state ->
            historyEmpty.visibility = if (historyVisible && state.refresh is LoadState.NotLoading && clips.itemCount == 0) View.VISIBLE else View.GONE
        }
    } }
    private val historyEmpty by lazy { notice("暂无最近记录").apply { gravity = Gravity.CENTER; visibility = View.GONE } }
    private val historyBody by lazy { FrameLayout(context).apply {
        visibility = View.GONE
        addView(historyList, FrameLayout.LayoutParams(-1, -1))
        addView(historyEmpty, FrameLayout.LayoutParams(-1, -1))
    } }
    private val historyToolbar by lazy { LinearLayout(context).apply {
        visibility = View.GONE
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(18), px(23), px(18), px(8))
        addView(toolIcon("back", "返回分词") { hideHistory() }, LinearLayout.LayoutParams(px(36), px(42)))
        addView(TextView(context).apply { text = "最近记录"; textSize = 14f; setTextColor(ink) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(toolIcon("trash", "清空最近记录") { clearHistory() }, LinearLayout.LayoutParams(px(36), px(42)))
    } }
    private fun showHistory() {
        if (OfflinePrivacyPolicy.sensitiveEditor) return
        historyVisible = true
        smartToolbar.visibility = View.GONE
        smartScroll.visibility = View.GONE
        historyToolbar.visibility = View.VISIBLE
        historyBody.visibility = View.VISIBLE
        historyJob?.cancel()
        historyJob = service.lifecycleScope.launch {
            Pager(PagingConfig(pageSize = 16, enablePlaceholders = false)) { ClipboardHelper.allBeans() }.flow.collectLatest { historyList.clips.submitData(it) }
        }
    }
    private fun hideHistory() {
        historyVisible = false
        historyJob?.cancel()
        historyJob = null
        historyToolbar.visibility = View.GONE
        historyBody.visibility = View.GONE
        smartToolbar.visibility = View.VISIBLE
        smartScroll.visibility = View.VISIBLE
    }
    private fun clearHistory() {
        if (OfflinePrivacyPolicy.sensitiveEditor) return
        service.showDialog(AlertDialog.Builder(context)
            .setTitle("清空最近记录")
            .setMessage("删除未固定的历史记录？当前分词内容和系统剪贴板会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ -> service.lifecycleScope.launch { ClipboardHelper.deleteAll(true) } }
            .create())
    }
    private val footer by lazy { LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(px(27), 0, px(27), 0)
        addView(toolIcon("globe", "切换中英文") { common.listener.onSmartAction("toggle_language") }, LinearLayout.LayoutParams(px(48), -1))
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(toolIcon("microphone", "语音输入") { common.listener.onAction(theme.resolveAction("VOICE_ASSIST")) }, LinearLayout.LayoutParams(px(48), -1))
    } }
    private val smartSurface by lazy { LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(smartToolbar, LinearLayout.LayoutParams(-1, px(82)))
        addView(historyToolbar, LinearLayout.LayoutParams(-1, px(82)))
        addView(smartScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(historyBody, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(footer, LinearLayout.LayoutParams(-1, context.dp(36)))
    } }
    private val smartRoot by lazy {
        FrameLayout(context).apply { addView(smartSurface, FrameLayout.LayoutParams(-1, -1)) }
    }
    private fun commit(value: String) {
        if (value.isEmpty() || OfflinePrivacyPolicy.sensitiveEditor) return
        val generation = attachmentGeneration
        service.commitPanelText(value, isActive = { attached && generation == attachmentGeneration && !OfflinePrivacyPolicy.sensitiveEditor }) { success ->
            if (!success && attached && generation == attachmentGeneration) context.toast("当前输入框无法粘贴")
        }
    }
    private fun toolIcon(kind: String, description: String, action: () -> Unit) = object : View(context) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        init { contentDescription = description; isClickable = true; isFocusable = true; setOnClickListener { action() } }
        override fun onDraw(canvas: Canvas) {
            val x = width / 2f; val y = height / 2f; val r = px(if (kind == "globe") 16 else 13).toFloat()
            pen.style = Paint.Style.FILL
            if (kind in setOf("history", "delete", "insert")) {
                pen.color = if (kind == "insert") 0xff2e88df.toInt() else ColorUtils.blendARGB(board, ink, .09f)
                canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), px(18).toFloat(), px(18).toFloat(), pen)
            }
            pen.style = Paint.Style.STROKE; pen.color = if (kind == "insert") Color.WHITE else ink; pen.strokeWidth = px(2).toFloat()
            when(kind) {
                "back" -> { canvas.drawLine(x+px(3), y-px(6), x-px(3), y, pen); canvas.drawLine(x-px(3), y, x+px(3), y+px(6), pen) }
                "insert" -> { canvas.drawLine(x, y+r*.8f, x, y-r*.8f, pen); canvas.drawLine(x, y-r*.8f, x-r*.65f, y-r*.15f, pen); canvas.drawLine(x, y-r*.8f, x+r*.65f, y-r*.15f, pen) }
                "delete" -> {
                    val path = android.graphics.Path().apply { moveTo(x-r,y); lineTo(x-r*.4f,y-r*.7f); lineTo(x+r,y-r*.7f); lineTo(x+r,y+r*.7f); lineTo(x-r*.4f,y+r*.7f); close() }
                    canvas.drawPath(path,pen); canvas.drawLine(x-r*.15f,y-r*.3f,x+r*.5f,y+r*.3f,pen); canvas.drawLine(x+r*.5f,y-r*.3f,x-r*.15f,y+r*.3f,pen)
                }
                "history" -> { canvas.drawArc(RectF(x-r,y-r,x+r,y+r),205f,300f,false,pen); canvas.drawLine(x,y-r*.55f,x,y,pen); canvas.drawLine(x,y,x+r*.4f,y+r*.3f,pen); canvas.drawLine(x-r,y-r*.65f,x-r,y,pen); canvas.drawLine(x-r,y,x-r*.4f,y-r*.15f,pen) }
                "trash" -> { canvas.drawLine(x-r*.55f,y-r*.6f,x+r*.55f,y-r*.6f,pen); canvas.drawRoundRect(RectF(x-r*.4f,y-r*.6f,x+r*.4f,y+r*.7f),px(2).toFloat(),px(2).toFloat(),pen); canvas.drawLine(x-r*.18f,y-r*.85f,x+r*.18f,y-r*.85f,pen); for (side in listOf(-1,1)) canvas.drawLine(x+side*r*.15f,y-r*.3f,x+side*r*.15f,y+r*.45f,pen) }
                "globe" -> { canvas.drawCircle(x,y,r,pen); canvas.drawOval(RectF(x-r*.45f,y-r,x+r*.45f,y+r),pen); canvas.drawLine(x-r,y,x+r,y,pen); canvas.drawLine(x-r*.85f,y-r*.5f,x+r*.85f,y-r*.5f,pen); canvas.drawLine(x-r*.85f,y+r*.5f,x+r*.85f,y+r*.5f,pen) }
                else -> { canvas.drawRoundRect(RectF(x-r*.35f,y-r,x+r*.35f,y+r*.25f),r*.35f,r*.35f,pen); canvas.drawArc(RectF(x-r*.65f,y-r*.4f,x+r*.65f,y+r*.6f),0f,180f,false,pen); canvas.drawLine(x,y+r*.6f,x,y+r,pen); canvas.drawLine(x-r*.4f,y+r,x+r*.4f,y+r,pen) }
            }
        }
    }
    private fun notice(text: String) = TextView(context).apply {
        this.text = text; textSize = 12f
        setTextColor(scope.colors.keyTextColor)
        setPadding(context.dp(8), context.dp(6), context.dp(8), context.dp(6))
    }
    private fun renderSmartCards() {
        smartCards.removeAllViews()
        filterButtons.forEach { (filter, button) ->
            button.isSelected = filter == tokenFilter
            button.background = if (filter == tokenFilter) rounded(ColorUtils.blendARGB(board, ink, .14f), 22) else null
        }
        val visible = SmartClipboardTokenizer.filter(tokens, tokenFilter)
        val pageCount = maxOf(1, (visible.size + cardsPerPage - 1) / cardsPerPage)
        tokenPage = tokenPage.coerceIn(0, pageCount - 1)
        if (currentSource.length > SmartClipboardTokenizer.MAX_ANALYSIS_CHARS) {
            smartCards.addView(notice("已分析前 ${SmartClipboardTokenizer.MAX_ANALYSIS_CHARS} 个字符，上箭头可粘贴完整原文。"), FlexboxLayout.LayoutParams(-1, -2))
        }
        if (pageCount > 1) {
            smartCards.addView(LinearLayout(context).apply {
                addView(actionButton("‹ 上页") { tokenPage--; renderSmartCards() }.apply { isEnabled = tokenPage > 0 })
                addView(notice("${tokenPage + 1}/$pageCount 页 · ${visible.size} 项"))
                addView(actionButton("下页 ›") { tokenPage++; renderSmartCards() }.apply { isEnabled = tokenPage + 1 < pageCount })
            }, FlexboxLayout.LayoutParams(-1, -2))
        }
        if (visible.isEmpty()) smartCards.addView(notice(if (OfflinePrivacyPolicy.sensitiveEditor) "隐私输入不读取剪贴板" else "暂无符合条件的内容"))
        visible.drop(tokenPage * cardsPerPage).take(cardsPerPage).forEach { token ->
            val availableSplits = token.splitPoints.filter { point ->
                SmartClipboardTokenizer.split(token, point).any { part ->
                    tokens.none { it.start == part.start && it.end == part.end && it.text == part.text }
                }
            }
            // The video marks word/entity boundaries, not the centre of every Chinese word.
            // Character-level refinement remains available by long press and accessibility.
            val visibleSplits = availableSplits.filter { point ->
                point in token.wordBoundaries || token.kind != SmartClipboardTokenizer.Kind.TEXT ||
                    token.text.any { it.code in 32..126 }
            }
            val chip = TokenChip(context, scope, token, visibleSplits, availableSplits, {
                commit(token.text)
            }, { point ->
                val expanded = SmartClipboardTokenizer.expand(tokens, token, point)
                if (expanded != tokens) {
                    InputFeedbackManager.keyPressVibrate(smartCards)
                    InputFeedbackManager.keyPressSound(android.view.KeyEvent.KEYCODE_SPACE)
                    val scrollY = smartScroll.scrollY
                    tokens.clear(); tokens.addAll(expanded)
                    renderSmartCards()
                    smartScroll.post { smartScroll.scrollTo(0, scrollY) }
                }
            })
            smartCards.addView(chip, FlexboxLayout.LayoutParams(-2, context.dp(42)).apply {
                setMargins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
                maxWidth = context.resources.displayMetrics.widthPixels - context.dp(28)
            })
        }
    }

    /** One flow chip: tap text to paste; a dashed divider reveals child fragments. */
    private class TokenChip(
        context: android.content.Context,
        scope: ThemeScope,
        private val token: SmartClipboardTokenizer.Token,
        private val splitPoints: List<Int>,
        private val refinementPoints: List<Int>,
        private val paste: () -> Unit,
        private val split: (Int) -> Unit,
    ) : androidx.appcompat.widget.AppCompatTextView(context) {
        private val divider = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = scope.colors.keyTextColor
            alpha = 85
            strokeWidth = context.dp(1).toFloat()
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(context.dp(2).toFloat(), context.dp(3).toFloat()), 0f)
        }
        private var splitAt: Int? = null
        // A real gap around each boundary keeps the divider clear of the glyphs.
        private val dividerGap = context.dp(8).toFloat()
        private inner class BoundaryGap : android.text.style.ReplacementSpan() {
            override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
                kotlin.math.ceil(paint.measureText(text, start, end) + dividerGap).toInt()
            override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
                canvas.drawText(text, start, end, x + dividerGap, y.toFloat(), paint)
            }
        }
        init {
            text = android.text.SpannableString(token.text).apply {
                splitPoints.take(16).forEach { point ->
                    setSpan(BoundaryGap(), point, token.text.offsetByCodePoints(point, 1), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            textSize = 19f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setPadding(context.dp(9), 0, context.dp(9), 0)
            maxWidth = context.resources.displayMetrics.widthPixels - context.dp(28)
            setTextColor(scope.colors.keyTextColor)
            background = scope.decorDrawable("key_back_color", cornerRadius = context.dp(6).toFloat())
            isClickable = true; isFocusable = true
            contentDescription = "粘贴 ${token.text.take(128)}" + when {
                splitPoints.isNotEmpty() -> "；点虚线拆分，长按拆分第一处"
                refinementPoints.isNotEmpty() -> "；长按拆字"
                else -> ""
            }
            setOnClickListener { val point = splitAt; splitAt = null; if (point == null) paste() else split(point) }
            if (refinementPoints.isNotEmpty()) {
                setOnLongClickListener { splitAt = null; split(refinementPoints.first()); true }
                androidx.core.view.ViewCompat.addAccessibilityAction(this, if (splitPoints.isEmpty()) "拆字" else "拆分词卡") { _, _ -> split(refinementPoints.first()); true }
            }
        }
        private fun dividerPositions(): List<Pair<Int, Float>> {
            val line = layout ?: return emptyList()
            val clippedAt = if (line.getEllipsisCount(0) > 0) line.getEllipsisStart(0) else token.text.length
            return splitPoints.take(16).filter { it < clippedAt }.map { index ->
                index to (compoundPaddingLeft + line.getPrimaryHorizontal(index) + dividerGap / 2f)
            }.filter { it.second < width - compoundPaddingRight }
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            dividerPositions().forEach { (_, x) -> canvas.drawLine(x, context.dp(5).toFloat(), x, (height - context.dp(5)).toFloat(), divider) }
        }
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                splitAt = dividerPositions().minByOrNull { kotlin.math.abs(it.second - event.x) }
                    ?.takeIf { kotlin.math.abs(it.second - event.x) <= context.dp(7) }?.first
            }
            if (event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) splitAt = null
            return super.onTouchEvent(event)
        }
    }

    private fun onSelectionChanged() {
        val joined = adapter.joinedSegments
        service.updateComposingText(joined)

        if (adapter.isAllSelected) {
            ui.setSelectButtonToDeselect()
        } else {
            ui.setSelectButtonToSelectAll()
        }

        val hasSelection = adapter.selectionSnapshot.isNotEmpty()
        ui.updateButtons(hasSelection)
    }

    private var viewCreated = false

    override fun onCreateView(): View {
        viewCreated = true
        return if (smartMode) smartRoot else ui.root
    }

    override fun refreshColors() {
        if (!viewCreated) return
        if (smartMode) {
            smartRoot.setBackgroundColor(if (ColorUtils.calculateLuminance(board) > .4) 0xfff2f2f6.toInt() else Color.BLACK)
            val radius = px(32).toFloat()
            smartSurface.background = GradientDrawable().apply { setColor(board); cornerRadii = floatArrayOf(radius,radius,radius,radius,0f,0f,0f,0f) }
            smartSurface.clipToOutline = true
            renderSmartCards()
            return
        }
        ui.refreshColors()
        (ui.recyclerView.adapter as? SegmentsAdapter)?.notifyDataSetChanged()
    }

    override fun onAttached() {
        if (smartMode) {
            if (OfflinePrivacyPolicy.sensitiveEditor) tokens.clear()
            if (!attached) {
                attached = true; attachmentGeneration++; previousBar = inputBar.view.visibility; previousHeight = windowManager.view.layoutParams.height
                val extra = if (previousBar != View.GONE) inputBar.view.height.takeIf { it > 0 } ?: context.dp(inputBar.themedHeight) else 0
                val boardHeight = previousHeight.takeIf { it > 0 } ?: windowManager.view.height
                footer.updateLayoutParams { height = KeyboardWindow.currentKeyboard.keys.lastOrNull()?.height ?: context.dp(36) }
                inputBar.view.visibility = View.GONE
                windowManager.view.updateLayoutParams { height = boardHeight + extra }
            }
            refreshColors()
            return
        }
        val segments = NativeTokenizer.tokenize(source)
        service.lifecycleScope.launch {
            adapter.submitList(segments)
        }
        ui.recyclerView.addOnItemTouchListener(touchListener)
    }

    override fun onDetached() {
        if (smartMode) {
            historyJob?.cancel()
            attachmentGeneration++
            if (attached) { attached = false; inputBar.view.visibility = previousBar; windowManager.view.updateLayoutParams { height = previousHeight } }
            return
        }
        ui.recyclerView.removeOnItemTouchListener(touchListener)
        service.currentInputConnection?.finishComposingText()
    }
}
