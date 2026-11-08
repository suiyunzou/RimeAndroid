package com.osfans.trime.ime.smart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.KeyEvent
import androidx.core.graphics.ColorUtils
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.keyboard.Key
import com.osfans.trime.ime.keyboard.KeyBehavior

/** Keyboard-local text lens. It never owns an editor or writes a draft to the host app. */
class ReferenceGestureOverlay(context: Context, private val keys: List<Key>) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var text = ""
    private var caret = 0
    private var pinyin = false
    private var pinyinSuggestion: String? = null
    private var lensX = 0f
    private var lensY = 0f
    private var cursorViewportTop: Float? = null
    private var cursorLayout: StaticLayout? = null
    private var cursorLayoutText: String? = null
    private var cursorLayoutWidth = 0

    init { visibility = GONE; isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun showCursor(value: String, index: Int, x: Float, y: Float) {
        if (pinyin || visibility != VISIBLE) cursorViewportTop = null
        pinyin = false; text = value; caret = CursorLensText.safeCaret(value, index)
        lensX = x; lensY = y; visibility = VISIBLE; bringToFront(); invalidate()
    }

    fun showPinyin(value: String, correction: CorrectionPresentation.Hint? = null) {
        pinyin = true; text = value
        pinyinSuggestion = PinyinLensText.matchingSuggestion(value, correction)
        visibility = VISIBLE; bringToFront(); invalidate()
    }

    fun close() {
        visibility = GONE; text = ""; pinyinSuggestion = null; caret = 0; lensX = 0f; lensY = 0f
        cursorViewportTop = null; cursorLayout = null; cursorLayoutText = null
    }

    override fun onDraw(canvas: Canvas) {
        val board = ColorManager.currentScope()?.colors?.keyboardBackColor ?: Color.rgb(220,221,223)
        val dark = ColorUtils.calculateLuminance(board) < .3
        if (!pinyin) {
            paint.color = board
            canvas.drawRect(0f, 0f, width.toFloat(), cursorBottom(), paint)
        }
        if (pinyin) drawPinyin(canvas, dark) else drawCursor(canvas, dark)
    }

    private fun drawPinyin(canvas: Canvas, dark: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = ColorManager.currentScope()?.colors?.keyBackColor ?: if (dark) 0xff555555.toInt() else Color.WHITE
        // Hide only letter labels. Shift, delete, the entire bottom row and the
        // globe/microphone remain the real underlying keyboard, as in the video.
        keys.filter { it.getCode(KeyBehavior.CLICK) in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z }.forEach { key ->
            canvas.drawRoundRect(RectF(key.x + 3*dp, key.y + 4*dp, key.x + key.width - 3*dp, key.y + key.height - 4*dp), 5*dp, 5*dp, paint)
        }
        val box = RectF(18*dp, 2*dp, width-18*dp, minOf(height-18*dp, 103*dp))
        paint.color = if (dark) 0xe86b6b6b.toInt() else 0xebeeeef1.toInt()
        canvas.drawRoundRect(box, 22*dp, 22*dp, paint)
        paint.color = if (dark) Color.WHITE else Color.rgb(45,45,48)
        paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.MONOSPACE
        paint.textSize = 29*dp
        val label = PinyinLensText.window(text)
        while (paint.measureText(label) > box.width()-20*dp && paint.textSize > 14*dp) paint.textSize -= dp
        canvas.drawText(label, width/2f, box.top+41*dp, paint)
        paint.typeface = Typeface.DEFAULT; paint.textSize = 14*dp
        paint.color = if (dark) 0xffd0d0d0.toInt() else 0xff747474.toInt()
        val suggestion = pinyinSuggestion
        if (suggestion != null) {
            val prefix = "建议："
            val shown = TextUtils.ellipsize(suggestion, TextPaint(paint), box.width()-paint.measureText(prefix)-20*dp, TextUtils.TruncateAt.END).toString()
            paint.textAlign = Paint.Align.LEFT
            val start = width/2f - paint.measureText(prefix + shown)/2f
            canvas.drawText(prefix, start, box.top+66*dp, paint)
            paint.color = 0xff258bd0.toInt()
            canvas.drawText(shown, start+paint.measureText(prefix), box.top+66*dp, paint)
            paint.textAlign = Paint.Align.CENTER
            paint.color = if (dark) 0xffd0d0d0.toInt() else 0xff747474.toInt()
        }
        paint.textSize = 13f*dp
        canvas.drawText(if (suggestion != null) "左右微调" else "左右微调 · 上到开头 · 下到末尾", width/2f, box.top+88*dp, paint)
    }

    @Suppress("DEPRECATION")
    private fun drawCursor(canvas: Canvas, dark: Boolean) {
        val bottom = cursorBottom()
        val margin = 18*dp
        val lensWidth = width * .42f
        val lensHeight = minOf(width * .132f, bottom - 8*dp).coerceAtLeast(20*dp)
        val halfHeight = lensHeight/2
        val minY = halfHeight + 4*dp
        val maxY = maxOf(minY, bottom-halfHeight-4*dp)
        // The context stays at ordinary reading size. Only the clipped optical
        // window is scaled, using precisely the same line wrapping and baseline.
        textPaint.textSize = 19*dp; textPaint.typeface = Typeface.DEFAULT
        textPaint.color = if (dark) 0xff858585.toInt() else 0xff85878b.toInt()
        val wrapWidth = (width-36*dp).toInt().coerceAtLeast(1)
        val layout = cursorLayout?.takeIf { cursorLayoutText == text && cursorLayoutWidth == wrapWidth }
            ?: StaticLayout(text, textPaint, wrapWidth, Layout.Alignment.ALIGN_NORMAL, 1.18f, 0f, false).also {
                cursorLayout = it; cursorLayoutText = text; cursorLayoutWidth = wrapWidth
            }
        val currentLine = layout.getLineForOffset(caret)
        val lineCenter = layout.getLineBaseline(currentLine)+(textPaint.ascent()+textPaint.descent())/2
        var blockTop = cursorViewportTop ?: ((lensY-22*dp).coerceIn(minY,maxY)-lineCenter)
        // Keep the document stationary while navigating within the viewport.
        // Scroll only when the actual caret reaches a visible edge.
        val unclampedY = blockTop+lineCenter
        val cy = unclampedY.coerceIn(minY,maxY)
        blockTop += cy-unclampedY
        cursorViewportTop = blockTop
        val caretX = margin+layout.getPrimaryHorizontal(caret)
        val cx = caretX.coerceIn(lensWidth/2+6*dp, width-lensWidth/2-6*dp)
        val box = RectF(cx-lensWidth/2,cy-halfHeight,cx+lensWidth/2,cy+halfHeight)
        val clip = Path().apply { addRoundRect(box,halfHeight,halfHeight,Path.Direction.CW) }
        canvas.save()
        canvas.clipRect(12*dp,4*dp,width-12*dp,bottom-4*dp)
        canvas.translate(margin,blockTop)
        layout.draw(canvas)
        canvas.restore()

        paint.style = Paint.Style.FILL
        paint.color = if (dark) 0xff171717.toInt() else 0xfffcfcfd.toInt()
        canvas.drawPath(clip,paint)
        canvas.save()
        canvas.clipPath(clip)
        // Zoom around the real caret, not a concatenated excerpt recentered
        // beneath the finger. Near the edges the caret remains near that edge.
        val zoom = 1.4f
        textPaint.color = if (dark) Color.WHITE else 0xff202124.toInt()
        canvas.translate(caretX,cy)
        canvas.scale(zoom,zoom)
        canvas.translate(margin-caretX,blockTop-cy)
        // A one-line optical strip prevents neighboring rows being enlarged
        // with the focus text when the capsule crosses a line boundary.
        canvas.clipRect(0f,layout.getLineTop(currentLine).toFloat(),wrapWidth.toFloat(),layout.getLineBottom(currentLine).toFloat())
        layout.draw(canvas)
        canvas.restore()
        paint.color = if (dark) Color.WHITE else 0xff202124.toInt()
        paint.strokeWidth = 1.3f*dp
        canvas.drawLine(caretX,cy-halfHeight*.67f,caretX,cy+halfHeight*.67f,paint)
    }
    private fun cursorBottom(): Float = keys.filter {
        it.getCode(KeyBehavior.CLICK) == KeyEvent.KEYCODE_LANGUAGE_SWITCH || it.getCode(KeyBehavior.CLICK) == KeyEvent.KEYCODE_VOICE_ASSIST
    }.minOfOrNull { it.y }?.toFloat()?.coerceIn(0f,height.toFloat()) ?: height.toFloat()
}
