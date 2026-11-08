// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

/** Offline book → chapter → contiguous verses. All drafts stay inside the IME. */
class BookQuoteWindow(di: DI) : BoardWindow.NoBarBoardWindow(di) {
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val windows: BoardWindowManager by instance()
    private val inputBar: InputBarDelegate by instance()
    private val common: CommonKeyboardActionListener by instance()
    private var taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generation = 0
    private var attached = false
    private var oldBar = View.VISIBLE
    private var oldHeight = 0
    private var books = emptyList<BookQuoteCatalog.Book>()
    private var verses = emptyList<BookQuoteCatalog.Verse>()
    private var book: BookQuoteCatalog.Book? = null
    private var chapter = 0
    private var firstVerse = -1
    private var lastVerse = -1
    private var testament = 1
    private var busy = false
    private var submitting = false
    private val queue = BookQuoteSelection()
    private val ink get() = scope.colors.keyTextColor
    private val board get() = scope.colors.keyboardBackColor
    private val track get() = ColorUtils.blendARGB(board, ink, .05f)
    private val blue = 0xff1976d2.toInt()
    private fun dp(v: Int) = context.dp(v)
    private fun px(v: Int): Int = (minOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels) * v / 486f).toInt()
    private fun rounded(color: Int, radius: Int = 22) = GradientDrawable().apply { setColor(color); cornerRadius = px(radius).toFloat() }
    private val grid = GridLayout(context)
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(grid); setPadding(px(18), px(22), px(18), 0) }
    private val navigation = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(14), 0, px(14), 0) }
    private val contentRoot = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(navigation, LinearLayout.LayoutParams(-1, px(44)))
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(px(27), 0, px(27), 0)
            addView(icon("globe", "切换中英文") {
                common.listener.onSmartAction("toggle_language")
            }, LinearLayout.LayoutParams(px(48), -1))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(icon("microphone", "语音输入") { common.listener.onAction(scope.theme.resolveAction("VOICE_ASSIST")) }, LinearLayout.LayoutParams(px(48), -1))
        }, LinearLayout.LayoutParams(-1, px(92)))
    }
    private val root = FrameLayout(context).apply { addView(contentRoot, FrameLayout.LayoutParams(-1, -1)) }
    private fun label(value: String, description: String = value, click: () -> Unit) = TextView(context).apply {
        text = value; contentDescription = description; textSize = 16f; includeFontPadding = false
        gravity = Gravity.CENTER; setTextColor(ink); isClickable = true; isFocusable = true
        setOnClickListener { if (!busy && !submitting) click() }
    }
    private fun loadBooks() {
        busy = true; showMessage("正在打开本地书籍…")
        val token = ++generation
        taskScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { context.assets.open("book-quotes/index.tsv").reader(Charsets.UTF_8).use(BookQuoteCatalog::readIndex) } }
            if (!attached || token != generation) return@launch
            busy = false
            result.onSuccess { books = it; render() }.onFailure { showMessage("本地书籍无法读取，请重新打开"); renderNavigation() }
        }
    }
    private fun chooseBook(next: BookQuoteCatalog.Book) {
        book = next; chapter = 0; firstVerse = -1; lastVerse = -1; verses = emptyList()
        busy = true; showMessage("正在打开${next.name}…"); renderNavigation()
        val token = ++generation
        taskScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { context.assets.open("book-quotes/${next.id}.tsv").reader(Charsets.UTF_8).use { BookQuoteCatalog.readBook(it, next) } } }
            if (!attached || token != generation) return@launch
            busy = false
            result.onSuccess { verses = it; render() }.onFailure { showMessage("本地章节无法读取"); renderNavigation() }
        }
    }
    private fun chapterVerses() = verses.filter { it.chapter == chapter }
    private fun selectedPassage(): BookQuoteCatalog.Passage? {
        val selected = book ?: return null
        val list = chapterVerses()
        if (firstVerse !in list.indices || lastVerse !in list.indices) return null
        return BookQuoteCatalog.Passage(selected, list.subList(minOf(firstVerse,lastVerse), maxOf(firstVerse,lastVerse) + 1).toList())
    }
    private fun render() {
        grid.removeAllViews(); grid.columnCount = if (book == null) 7 else 8
        val entries: List<Pair<String, () -> Unit>> = when {
            book == null -> books.filter { it.testament == testament }.map { item -> item.shortName to { chooseBook(item) } }
            chapter == 0 -> (1..book!!.chapters).map { n -> n.toString() to { chapter = n; firstVerse = -1; lastVerse = -1; render(); scroll.scrollTo(0, 0) } }
            else -> chapterVerses().mapIndexed { index, verse -> verse.number.toString() to {
                if (firstVerse < 0 || firstVerse != lastVerse) { firstVerse = index; lastVerse = index } else lastVerse = index
                render()
            } }
        }
        entries.forEachIndexed { index, (value, action) ->
            val description = when { book == null -> books.first { it.shortName == value }.name; chapter == 0 -> "第${value}章"; else -> "第${value}节" }
            grid.addView(label(value, description, action).apply {
                if (chapter > 0 && firstVerse >= 0 && index in minOf(firstVerse,lastVerse)..maxOf(firstVerse,lastVerse)) {
                    val start = index == minOf(firstVerse,lastVerse) || index % grid.columnCount == 0
                    val end = index == maxOf(firstVerse,lastVerse) || index % grid.columnCount == grid.columnCount - 1
                    val l = if (start) px(9).toFloat() else 0f
                    val r = if (end) px(9).toFloat() else 0f
                    background = GradientDrawable().apply { setColor(blue); cornerRadii = floatArrayOf(l,l,r,r,r,r,l,l) }
                    setTextColor(Color.WHITE); isSelected = true
                }
            }, GridLayout.LayoutParams(GridLayout.spec(index / grid.columnCount), GridLayout.spec(index % grid.columnCount, 1f)).apply { width = 0; height = px(49) })
        }
        renderNavigation()
    }
    private fun renderNavigation() {
        navigation.removeAllViews()
        navigation.addView(icon("keyboard", "返回字母键盘") { windows.attachWindow(KeyboardWindow) }.apply { background = rounded(track) }, LinearLayout.LayoutParams(px(43), px(40)))
        val middle = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(5), 0, px(5), 0) }
        navigation.addView(middle, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = px(6); rightMargin = px(6) })
        if (book == null) {
            middle.background = rounded(track)
            listOf("旧约", "新约").forEachIndexed { index, name ->
                middle.addView(label(name) { testament = index; render(); scroll.scrollTo(0,0) }.apply {
                    if (index == testament) { background = rounded(ColorUtils.blendARGB(board,ink,.10f)); isSelected = true }
                }, LinearLayout.LayoutParams(0, px(40), 1f))
            }
        } else {
            middle.addView(label("‹", "返回书卷或章节") {
                if (chapter > 0) chapter = 0 else { book = null; verses = emptyList() }
                firstVerse = -1; lastVerse = -1; render(); scroll.scrollTo(0,0)
            }, LinearLayout.LayoutParams(px(37), px(40)))
            val passage = selectedPassage()
            val caption = (if (queue.size > 0) "+${queue.size} " else "") +
                (passage?.let { it.reference + "  " + it.verses.first().text } ?: (book!!.name + if (chapter > 0) " $chapter" else ""))
            middle.addView(label(caption, caption + "，长按清空引用") {}.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL; textSize = 13f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnLongClickListener { queue.clear(); firstVerse = -1; lastVerse = -1; render(); true }
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        navigation.addView(label(if (queue.size == 0) "+" else "+${queue.size}", "添加引用，长按清空") {
            val selection = selectedPassage() ?: return@label
            runCatching { queue.append(selection) }.onSuccess {
                firstVerse = -1; lastVerse = -1; book = null; chapter = 0; verses = emptyList(); render(); scroll.scrollTo(0,0)
                Toast.makeText(context, "已添加${queue.size}段引用", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
        }.apply {
            background = rounded(track); alpha = if (selectedPassage() == null) .5f else 1f
            textSize = 25f; setOnLongClickListener { queue.clear(); firstVerse = -1; lastVerse = -1; render(); true }
        }, LinearLayout.LayoutParams(px(42), px(40)).apply { rightMargin = px(8) })
        navigation.addView(label("插入", "插入引用") { insert() }.apply {
            background = rounded(blue); setTextColor(Color.WHITE)
            alpha = if (queue.size > 0 || selectedPassage() != null) 1f else .55f
        }, LinearLayout.LayoutParams(px(76), px(40)))
    }
    private fun insert() {
        val value = runCatching { queue.text(selectedPassage()) }.getOrElse { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show(); return }
        if (value.isEmpty() || submitting) return
        val editor = service.currentInputEditorInfo ?: return
        submitting = true
        val token = generation
        service.postRimeJob {
            if (!attached || token != generation || service.currentInputEditorInfo !== editor) { root.post { submitting = false }; return@postRimeJob }
            if (statusCached.isComposing) commitComposition()
            val success = attached && token == generation && service.currentInputEditorInfo === editor &&
                runCatching { service.currentInputConnection?.commitText(value, 1) == true }.getOrDefault(false)
            root.post {
                submitting = false
                if (!attached || token != generation) return@post
                if (success) { queue.clear(); firstVerse = -1; lastVerse = -1; render() }
                else Toast.makeText(context, "无法插入，请重新点选输入框", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun showMessage(message: String) {
        grid.removeAllViews(); grid.columnCount = 1
        grid.addView(label(message) {}, GridLayout.LayoutParams().apply { width = -1; height = dp(90) })
    }
    private fun icon(kind: String, description: String, click: () -> Unit) = object : View(context) {
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        init { contentDescription = description; isClickable = true; isFocusable = true; setOnClickListener { click() } }
        override fun onDraw(canvas: Canvas) {
            pen.color = ink; pen.strokeWidth = px(2).toFloat().coerceAtLeast(1f)
            val x = width / 2f; val y = height / 2f; val r = px(15).toFloat()
            when (kind) {
                "keyboard" -> {
                    canvas.drawRoundRect(RectF(x-r,y-r*.65f,x+r,y+r*.65f),px(3).toFloat(),px(3).toFloat(),pen)
                    for (row in 0..1) for (col in 0..5) canvas.drawPoint(x-r*.72f+col*r*.285f,y-r*.3f+row*r*.32f,pen)
                    canvas.drawLine(x-r*.5f,y+r*.35f,x+r*.5f,y+r*.35f,pen)
                }
                "globe" -> { canvas.drawCircle(x,y,r,pen);canvas.drawOval(RectF(x-r*.45f,y-r,x+r*.45f,y+r),pen);canvas.drawLine(x-r,y,x+r,y,pen);canvas.drawLine(x-r*.85f,y-r*.5f,x+r*.85f,y-r*.5f,pen);canvas.drawLine(x-r*.85f,y+r*.5f,x+r*.85f,y+r*.5f,pen) }
                else -> { canvas.drawRoundRect(RectF(x-r*.3f,y-r,x+r*.3f,y+r*.25f),r*.3f,r*.3f,pen);canvas.drawArc(RectF(x-r*.6f,y-r*.4f,x+r*.6f,y+r*.6f),0f,180f,false,pen);canvas.drawLine(x,y+r*.6f,x,y+r,pen);canvas.drawLine(x-r*.35f,y+r,x+r*.35f,y+r,pen) }
            }
        }
    }
    override fun onCreateView(): View = root.also { refreshColors(); renderNavigation() }
    override fun onAttached() {
        if (attached) return
        attached = true; taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        oldBar = inputBar.view.visibility; oldHeight = windows.view.layoutParams.height
        val extra = if (oldBar != View.GONE) context.dp(inputBar.themedHeight) else 0
        inputBar.view.visibility = View.GONE
        val target = if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) px(419) else 0
        windows.view.updateLayoutParams { height = maxOf(oldHeight + extra, target) }
        refreshColors(); if (books.isEmpty()) loadBooks() else render()
    }
    override fun onDetached() {
        generation++; taskScope.cancel(); submitting = false
        if (attached) { attached = false; inputBar.view.visibility = oldBar; windows.view.updateLayoutParams { height = oldHeight } }
    }
    override fun refreshColors() {
        root.setBackgroundColor(if (ColorUtils.calculateLuminance(board) > .4) 0xfff2f2f6.toInt() else Color.BLACK)
        val r = px(32).toFloat()
        contentRoot.background = GradientDrawable().apply { setColor(board); cornerRadii = floatArrayOf(r,r,r,r,0f,0f,0f,0f) }
        contentRoot.clipToOutline = true
        if (books.isNotEmpty() && !busy) render()
    }
}
