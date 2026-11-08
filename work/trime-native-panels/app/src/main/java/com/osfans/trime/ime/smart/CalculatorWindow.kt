// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
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

/** Local calculator; expression drafts never enter the host editor. */
class CalculatorWindow(di: DI) : BoardWindow.NoBarBoardWindow(di) {
    private fun dp(value: Int): Int = context.dp(value)
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val windowManager: BoardWindowManager by instance()
    private val inputBar: InputBarDelegate by instance()
    private val common: CommonKeyboardActionListener by instance()
    private fun px(value: Int) = (minOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels) * value / 486f).toInt()
    private var attached = false
    private var oldHeight = 0
    private var oldBar = View.VISIBLE
    private var generation = 0
    private var committing = false
    private var expression = ""
    private var submitted = false
    private var resultShown = false
    private val keys = mutableListOf<Button>()
    private val expressionDisplay by lazy {
        TextView(context).apply {
            textSize = px(22) / resources.displayMetrics.scaledDensity
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(px(22), 0, px(16), 0)
            isSingleLine = true
            setHorizontallyScrolling(true)
            contentDescription = "计算算式"
        }
    }
    private val expressionScroll by lazy { HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(expressionDisplay, FrameLayout.LayoutParams(-2, -1))
    } }
    private val status by lazy {
        TextView(context).apply {
            textSize = 12f
            setPadding(dp(10), 0, dp(10), 0)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }
    private val surface by lazy {
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            addView(FrameLayout(context).apply {
                addView(expressionScroll, FrameLayout.LayoutParams(-1, -1))
                addView(status, FrameLayout.LayoutParams(-1, px(21), Gravity.BOTTOM))
            }, LinearLayout.LayoutParams(-1, px(76)))
            val keypad = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(px(4), 0, px(4), 0) }
            listOf(
                listOf("(", "7", "8", "9", "⌫", "%"),
                listOf(")", "4", "5", "6", "×", "÷"),
                listOf("√", "1", "2", "3", "+", "−"),
                listOf("ABC", ".", "0", "=", "空格", "换行"),
            ).forEach { row ->
                keypad.addView(LinearLayout(context).apply {
                    row.forEach { label ->
                        val button = ReferencePanelStyle.button(context, scope, label) { input(label) }.apply {
                            textSize = px(if (label in listOf("ABC", "空格", "换行")) 19 else if (label == "√") 32 else 25) / resources.displayMetrics.scaledDensity
                            stateListAnimator = null; elevation = 0f; translationZ = 0f
                            contentDescription = when (label) { "⌫" -> "删除一位，长按清空算式"; "=" -> "计算并上屏"; "ABC" -> "返回字母键盘"; else -> label }
                            if (label == "⌫") setOnLongClickListener { input("C"); true }
                        }
                        keys.add(button)
                        addView(button, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(px(4), px(5), px(4), px(5)) })
                    }
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            addView(keypad, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(px(27), 0, px(27), 0)
                addView(footerIcon(false), LinearLayout.LayoutParams(px(48), -1))
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(footerIcon(true), LinearLayout.LayoutParams(px(48), -1))
            }, LinearLayout.LayoutParams(-1, px(92)))
        }
    }
    private val root by lazy { FrameLayout(context).apply { addView(surface, FrameLayout.LayoutParams(-1, -1)) } }
    private fun footerIcon(microphone: Boolean) = object : View(context) {
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        init {
            contentDescription = if (microphone) "语音输入" else "切换中英文"
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (microphone) common.listener.onAction(scope.theme.resolveAction("VOICE_ASSIST"))
                else common.listener.onSmartAction("toggle_language")
            }
        }
        override fun onDraw(canvas: Canvas) {
            pen.color = scope.colors.keyTextColor; pen.strokeWidth = px(2).toFloat().coerceAtLeast(1f)
            val x = width / 2f; val y = height / 2f; val r = px(15).toFloat()
            if (microphone) {
                canvas.drawRoundRect(RectF(x-r*.3f,y-r,x+r*.3f,y+r*.25f),r*.3f,r*.3f,pen)
                canvas.drawArc(RectF(x-r*.6f,y-r*.4f,x+r*.6f,y+r*.6f),0f,180f,false,pen)
                canvas.drawLine(x,y+r*.6f,x,y+r,pen);canvas.drawLine(x-r*.35f,y+r,x+r*.35f,y+r,pen)
            } else {
                canvas.drawCircle(x,y,r,pen);canvas.drawOval(RectF(x-r*.45f,y-r,x+r*.45f,y+r),pen)
                canvas.drawLine(x-r,y,x+r,y,pen)
                for (side in listOf(-1f, 1f)) canvas.drawPath(android.graphics.Path().apply {
                    moveTo(x-r*.9f,y+side*r*.44f);quadTo(x,y+side*r*.2f,x+r*.9f,y+side*r*.44f)
                },pen)
            }
        }
    }
    private fun renderExpression() {
        expressionDisplay.text = expression.ifEmpty { "输入算式" }
        expressionDisplay.setTextColor(if (expression.isEmpty()) ColorUtils.blendARGB(scope.colors.keyTextColor, scope.colors.keyboardBackColor, .45f) else scope.colors.keyTextColor)
        expressionScroll.post { expressionScroll.fullScroll(View.FOCUS_RIGHT) }
    }
    private fun commit(text: String, onSuccess: () -> Unit = {}) {
        if (committing) return
        committing = true
        val token = generation
        service.commitPanelText(text, isActive = { attached && token == generation }) { success ->
            if (token == generation) committing = false
            if (attached && token == generation) {
                if (success) onSuccess() else { status.text = "未能上屏，请点选输入框后重试"; status.visibility = View.VISIBLE }
            }
        }
    }
    private fun input(label: String) {
        if (committing && label != "ABC" && label != "键盘") return
        status.visibility = View.GONE
        when (label) {
            "键盘", "ABC" -> { windowManager.attachWindow(KeyboardWindow); return }
            "空格", "换行" -> {
                val text = if (label == "空格") " " else "\n"
                commit(text)
                return
            }
            "C" -> { expression = ""; submitted = false; resultShown = false }
            "⌫" -> { expression = expression.dropLast(1); submitted = false; resultShown = false }
            "=" -> {
                if (submitted) return
                val result = runCatching { CalculatorEngine.evaluate(expression) }.getOrElse {
                    status.text = it.message ?: "算式无效"
                    status.visibility = View.VISIBLE
                    return
                }
                commit(result) {
                    expression = result; submitted = true; resultShown = true
                    renderExpression()
                }
                return
            }
            else -> {
                if (resultShown && (label.first().isDigit() || label == "." || label == "√" || label == "(")) expression = ""
                if (expression.length >= CalculatorEngine.MAX_LENGTH) { status.text = "算式过长"; status.visibility = View.VISIBLE; return }
                expression += label
                submitted = false
                resultShown = false
            }
        }
        renderExpression()
    }
    override fun onCreateView(): View = root.also { refreshColors(); renderExpression() }
    override fun onAttached() {
        if (!attached) {
            attached = true; oldBar = inputBar.view.visibility; oldHeight = windowManager.view.layoutParams.height
            val extra = if (oldBar != View.GONE) context.dp(inputBar.themedHeight) else 0
            inputBar.view.visibility = View.GONE
            val target = if (context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) px(419) else 0
            windowManager.view.updateLayoutParams { height = maxOf(oldHeight + extra, target) }
        }
        refreshColors()
    }
    override fun onDetached() {
        generation++; committing = false
        if (attached) { attached = false; inputBar.view.visibility = oldBar; windowManager.view.updateLayoutParams { height = oldHeight } }
    }
    override fun refreshColors() {
        val board = scope.colors.keyboardBackColor
        root.setBackgroundColor(if (ColorUtils.calculateLuminance(board) > .4) 0xfff2f2f6.toInt() else Color.BLACK)
        val r = px(32).toFloat()
        surface.background = GradientDrawable().apply { setColor(board); cornerRadii = floatArrayOf(r,r,r,r,0f,0f,0f,0f) }
        renderExpression()
        status.setTextColor(scope.colors.keyTextColor)
        keys.forEach { button ->
            ReferencePanelStyle.apply(button, scope, when (button.text.toString()) {
                "=" -> ReferencePanelStyle.Role.ACTIVE
                "⌫" -> ReferencePanelStyle.Role.DELETE
                "%" -> ReferencePanelStyle.Role.PERCENT
                "(", ")", "√", "×", "÷", "+", "−", "ABC", "空格", "换行" -> ReferencePanelStyle.Role.SUBTLE
                else -> ReferencePanelStyle.Role.KEY
            })
        }
    }
}
