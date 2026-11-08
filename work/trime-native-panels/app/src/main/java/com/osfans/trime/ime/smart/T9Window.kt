package com.osfans.trime.ime.smart

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.core.RimeKeyEvent
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.dimensions.dp

/** The same phonetic engine in the reference keyboard's compact nine-key geometry. */
class T9Window(di: DI) : BoardWindow.BarBoardWindow(di) {
    private fun dp(value: Int): Int = context.dp(value)
    private val service: TrimeInputMethodService by instance()
    private val manager: BoardWindowManager by instance()
    private val scope: ThemeScope by instance()
    private val common: CommonKeyboardActionListener by instance()
    private val displayPrefs by lazy { ReferenceDisplayPrefs(context) }
    private val liquid: LiquidWindow by instance()
    override val title = "九宫格拼音"
    override val allowCandidates = true
    private var digits = ""
    private val keys = mutableListOf<ReferenceKey>()
    private val choices by lazy { LinearLayout(context) }
    private val choicesScroller by lazy { HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled=false; addView(choices) } }
    private val preview by lazy { TextView(context).apply {
        textSize=13f; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(8),0,dp(8),0)
        contentDescription="九宫格当前数字码"
    } }

    private inner class ReferenceKey(val label: String, val hint: String = "", val function: Boolean = false, val footer: Boolean = false, val bindingId: String = label.lowercase()) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val flick = KeyFlickGesture(resources.displayMetrics.density)
        init { isClickable=true; isFocusable=true; contentDescription=if(hint.isEmpty()) label else "$hint $label" }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when(event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { isPressed=true;flick.down(event.x,event.y,event.eventTime,allowLongPress=false) }
                MotionEvent.ACTION_MOVE -> flick.move(event.x,event.y,event.eventTime)
                MotionEvent.ACTION_UP -> {
                    isPressed=false
                    when(val action=flick.up(event.x,event.y,event.eventTime)) {
                        KeyFlickGesture.Action.TAP -> performClick()
                        KeyFlickGesture.Action.FLICK_UP,KeyFlickGesture.Action.FLICK_DOWN -> {
                            val prefs=SmartInputPrefs(context)
                            val up=action==KeyFlickGesture.Action.FLICK_UP
                            if (!GlidePrefs(context).enabled && prefs.allowsFlick(up)) {
                                val binding=prefs.binding(bindingId,up) ?: KeyBindingLabels.defaultHint(bindingId,true).takeIf { it.isNotEmpty() }?.let { "text:$it" }
                                if(binding!=null) { common.listener.onSmartAction(binding);performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                            }
                        }
                        else -> Unit
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL -> { isPressed=false;flick.cancel() }
            }
            return true
        }
        override fun onDraw(canvas: Canvas) {
            val inset=dp(3).toFloat()
            paint.color=scope.color(if(footer) "keyboard_back_color" else if(isPressed) "hilited_key_back_color" else if(function) "off_key_back_color" else "key_back_color")
            canvas.drawRoundRect(RectF(inset,inset,width-inset,height-inset),dp(6).toFloat(),dp(6).toFloat(),paint)
            paint.color=scope.colors.keyTextColor
            if(footer) {
                val cx=width/2f;val cy=height/2f;val radius=dp(11).toFloat()
                paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2).toFloat()
                if(label=="◎") {
                    canvas.drawCircle(cx,cy,radius,paint)
                    canvas.drawOval(RectF(cx-radius/2,cy-radius,cx+radius/2,cy+radius),paint)
                    canvas.drawLine(cx-radius,cy,cx+radius,cy,paint)
                } else {
                    canvas.drawRoundRect(RectF(cx-dp(4),cy-dp(11),cx+dp(4),cy+dp(3)),dp(4).toFloat(),dp(4).toFloat(),paint)
                    canvas.drawArc(RectF(cx-dp(8),cy-dp(6),cx+dp(8),cy+dp(8)),0f,180f,false,paint)
                    canvas.drawLine(cx,cy+dp(8),cx,cy+dp(12),paint)
                    canvas.drawLine(cx-dp(5),cy+dp(12),cx+dp(5),cy+dp(12),paint)
                }
                paint.style=Paint.Style.FILL
                return
            }
            paint.typeface=if(displayPrefs.keyBold)android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            paint.textSize=(if(footer) 25f else if(label.length>3) 18f else 22f)*resources.displayMetrics.scaledDensity*displayPrefs.keyTextSize/24f
            paint.textAlign=Paint.Align.CENTER
            val metrics=paint.fontMetrics
            canvas.drawText(label,width/2f,height/2f-(metrics.ascent+metrics.descent)/2f,paint)
            val prefs=SmartInputPrefs(context)
            val binding=if(!GlidePrefs(context).enabled && prefs.flickDirection!=FlickDirection.OFF) prefs.binding(bindingId,prefs.flickDirection==FlickDirection.UP) else null
            val corner=if(binding!=null)KeyBindingLabels.corner(binding) else hint
            if(corner.isNotEmpty()) {
                paint.color=scope.color("key_symbol_color");paint.textSize=9f*resources.displayMetrics.scaledDensity;paint.textAlign=Paint.Align.RIGHT
                canvas.drawText(corner,width-dp(8).toFloat(),dp(12).toFloat(),paint)
            }
        }
        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    }
    private fun key(label:String,hint:String="",function:Boolean=false,footer:Boolean=false,bindingId:String=label.lowercase(),action:()->Unit): ReferenceKey =
        ReferenceKey(label,hint,function,footer,bindingId).apply { setOnClickListener { action() };keys.add(this) }
    private fun returnKeyboard() { SmartInputPrefs(context).layout="qwerty";manager.attachWindow(KeyboardWindow) }
    private fun switchLanguage() {
        if (digits.isNotEmpty()) {
            val syllable = T9PinyinEngine.candidates(digits).firstOrNull()
            if (syllable != null) choose(syllable)
            else {
                // An unfinished numeric code has no phonetic candidate; preserve it literally.
                val raw = digits
                digits = ""
                service.commitPanelText(raw, isActive = { true }) { }
            }
        }
        returnKeyboard()
        common.listener.onSmartAction("toggle_language")
    }
    private fun openEmoji() {
        common.listener.onSmartAction("emoji")
    }
    private val root by lazy { LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(3),0,dp(3),0)
        addView(preview,LinearLayout.LayoutParams(-1,dp(20)))
        addView(choicesScroller,LinearLayout.LayoutParams(-1,dp(34)))
        addView(LinearLayout(context).apply {
            addView(LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                listOf("，","。","？","！").forEach { punctuation ->
                    addView(key(punctuation,function=true) { common.listener.onSmartAction("text:$punctuation") },LinearLayout.LayoutParams(-1,0,1f))
                }
            },LinearLayout.LayoutParams(0,-1,8f))
            addView(LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                listOf(listOf("@/." to "1","ABC" to "2","DEF" to "3"),listOf("GHI" to "4","JKL" to "5","MNO" to "6"),listOf("PQRS" to "7","TUV" to "8","WXYZ" to "9")).forEach { row ->
                    addView(LinearLayout(context).apply { row.forEach { (letters,digit) ->
                        addView(key(letters,digit) {
                            if(digit=="1") common.listener.onSmartAction("symbols") else input(digit)
                        },LinearLayout.LayoutParams(0,-1,1f))
                    } },LinearLayout.LayoutParams(-1,0,1f))
                }
            },LinearLayout.LayoutParams(0,-1,77f))
            addView(LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL
                addView(key("⌫",function=true,bindingId="backspace") { input("⌫") },LinearLayout.LayoutParams(-1,0,1f))
                addView(key("分词",function=true) { input("选字") },LinearLayout.LayoutParams(-1,0,1f))
                addView(key("0",function=true) { common.listener.onSmartAction("text:0") },LinearLayout.LayoutParams(-1,0,1f))
            },LinearLayout.LayoutParams(0,-1,15f))
        },LinearLayout.LayoutParams(-1,0,1f))
        addView(LinearLayout(context).apply {
            addView(key("ABC",function=true,bindingId="qwerty") { returnKeyboard() },LinearLayout.LayoutParams(0,-1,13f))
            addView(key("☻",function=true,bindingId="emoji") { openEmoji() },LinearLayout.LayoutParams(0,-1,13f))
            addView(key("空格",bindingId="space") { input("选字") },LinearLayout.LayoutParams(0,-1,46f))
            addView(key("↵",function=true,bindingId="enter") {
                if(digits.isNotEmpty()) T9PinyinEngine.candidates(digits).firstOrNull()?.let { choose(it) }
                service.postRimeJob { processKey(RimeKeyEvent.getKeycodeByName("Return")) }
            },LinearLayout.LayoutParams(0,-1,28f))
        },LinearLayout.LayoutParams(-1,dp(47)))
        addView(LinearLayout(context).apply {
            addView(key("◎",footer=true,bindingId="globe") { switchLanguage() }.apply { contentDescription="切换中英文" },LinearLayout.LayoutParams(0,-1,14f))
            addView(View(context),LinearLayout.LayoutParams(0,-1,72f))
            addView(key("语音",footer=true,bindingId="voice") { common.listener.onAction(scope.theme.resolveAction("VOICE_ASSIST")) },LinearLayout.LayoutParams(0,-1,14f))
        },LinearLayout.LayoutParams(-1,dp(36)))
    } }
    private fun input(label:String) {
        when(label) {
            "⌫" -> if(digits.isNotEmpty()) digits=digits.dropLast(1) else service.postRimeJob { processKey(RimeKeyEvent.getKeycodeByName("BackSpace")) }
            "选字" -> if(digits.isNotEmpty()) T9PinyinEngine.candidates(digits).firstOrNull()?.let { choose(it) }
                else service.postRimeJob { processKey(RimeKeyEvent.getKeycodeByName("space")) }
            else -> if(digits.length<6) digits+=label.first()
        }
        render()
    }
    private fun choose(syllable:String) {
        digits=""
        service.postRimeJob {
            setRuntimeOption("ascii_mode",false)
            if(getRawInput().isNotEmpty()) simulateKeySequence("'")
            simulateKeySequence(syllable)
        }
        render()
    }
    private fun render() {
        preview.text=digits
        preview.visibility=if(digits.isEmpty()) View.GONE else View.VISIBLE
        choicesScroller.visibility=if(digits.isEmpty()) View.GONE else View.VISIBLE
        choices.removeAllViews()
        T9PinyinEngine.candidates(digits).forEach { syllable ->
            choices.addView(TextView(context).apply {
                text=syllable;textSize=19f;gravity=Gravity.CENTER;setTextColor(scope.colors.keyTextColor)
                setOnClickListener { choose(syllable) }
            },LinearLayout.LayoutParams(dp(58),-1))
        }
        if(digits.isNotEmpty()&&choices.childCount==0) choices.addView(TextView(context).apply {
            text="继续输入拼音，或退格";textSize=14f;setTextColor(scope.colors.keyTextColor)
        })
    }
    override fun onCreateView(): View = root.also { render();refreshColors() }
    override fun onAttached() { refreshColors() }
    override fun onDetached() { digits="" }
    override fun refreshColors() { root.background=scope.drawable("keyboard_back_color");preview.setTextColor(scope.colors.keyTextColor);keys.forEach { it.invalidate() } }
}

