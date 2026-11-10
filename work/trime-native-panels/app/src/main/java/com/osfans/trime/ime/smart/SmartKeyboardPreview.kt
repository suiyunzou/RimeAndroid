package com.osfans.trime.ime.smart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager

/** Live, local settings preview. It reads the same preferences as the real keyboard. */
class SmartKeyboardPreview(context: Context) : View(context) {
    private val prefs = SmartInputPrefs(context)
    private val displayPrefs = ReferenceDisplayPrefs(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelInk = Rect()
    private val bindingHint = KeyBindingHint(context)
    private val boxes = mutableListOf<Pair<String, RectF>>()
    var dark = false
    var gestureUp: Boolean? = null
    var onKey: ((String) -> Unit)? = null
    var onKeyAnchor: ((String, RectF) -> Unit)? = null
    var layoutOverride: String? = null
    private val t9 get() = (layoutOverride ?: prefs.layout) == "t9"
    fun keyBounds(key: String): RectF? = boxes.firstOrNull { it.first == key }?.second?.let {
        RectF(it.left*drawingScale,it.top*drawingScale,it.right*drawingScale,it.bottom*drawingScale)
    }
    var dimmed = false
    var showGestureHints = true
    var showCandidates = true
        set(value) { field=value; requestLayout(); invalidate() }
    var showBottomBar = false
        set(value) { field=value; requestLayout(); invalidate() }
    var keyboardBodyHeight = context.getSharedPreferences("smart_input",Context.MODE_PRIVATE).getInt("reference_keyboard_height",212).coerceIn(144,320)
        set(value) { field=value.coerceIn(144,320); requestLayout(); invalidate() }
    private var drawingScale=1f
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    init { isClickable = true; contentDescription = "键盘实时预览" }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val measuredWidth=resolveSize(dp(384f).toInt(),widthMeasureSpec)
        val naturalHeight=((keyboardBodyHeight+(if(showCandidates)43 else 0)+if(showBottomBar)36 else 0)*measuredWidth/384f).toInt()
        setMeasuredDimension(measuredWidth,resolveSize(naturalHeight,heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawingScale=width/dp(384f)
        if(drawingScale<=0f)return
        val designWidth=dp(384f)
        val designHeight=height/drawingScale
        val checkpoint=canvas.save()
        canvas.scale(drawingScale,drawingScale)
        val themePalette=ThemeManager.prefs.selectedTheme.getValue()=="rerime-reference.trime"
        fun color(key:String,fallback:Int)=if(themePalette)runCatching{ColorManager.getColor(key)}.getOrDefault(fallback) else fallback
        val ink = color("key_text_color",if (dark) Color.WHITE else Color.BLACK)
        val keyColor = color("key_back_color",if (dark) 0xff626262.toInt() else Color.WHITE)
        val control = color("off_key_back_color",if (dark) 0xff454545.toInt() else 0xffb9bdc3.toInt())
        paint.color = color("keyboard_back_color",if (dark) 0xff292929.toInt() else 0xffd1d3d9.toInt())
        canvas.drawRoundRect(RectF(0f,0f,designWidth.toFloat(),designHeight.toFloat()),dp(18f),dp(18f),paint)
        val top = if (showCandidates) dp(43f) else 0f
        if (showCandidates) {
        paint.textAlign = Paint.Align.LEFT
        paint.color = color("candidate_text_color",ink)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = dp(10f)
        canvas.drawText("ni'hao",dp(8f),dp(11f),paint)
        paint.textSize = dp(when(prefs.candidateSize) { 16 -> 14f; 24,28,32 -> 19f; else -> 17f })
        paint.typeface = if (prefs.candidateBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val candidates=if(prefs.candidateEmoji)listOf("你好","👋","你号","拟好","你","好","号") else listOf("你好","你号","拟好","你","好","号")
        val limit=designWidth-dp(30f)
        var candidateX=dp(8f)
        val spacing=dp(16f)
        for(candidate in candidates){
            val candidateWidth=paint.measureText(candidate)
            if(candidateX+candidateWidth>limit)break
            canvas.drawText(candidate,candidateX,dp(31f),paint)
            candidateX+=candidateWidth+spacing
        }
        paint.textAlign=Paint.Align.CENTER
        canvas.drawText("⌄",designWidth-dp(14f),dp(31f),paint)
        }
        paint.textAlign=Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT
        boxes.clear()
        val gap = dp(6f)
        val footer = if(showBottomBar)dp(36f) else 0f
        val rowHeight = (designHeight-top-footer)/4f
        fun draw(key:String,left:Float,y:Float,w:Float,h:Float){
            drawKey(canvas,key,RectF(left+gap/2,y,left+w-gap/2,y+h-gap),keyColor,control,ink)
        }
        if(t9){
            val rail=designWidth*0.08f
            val center=designWidth*0.77f
            val right=designWidth*0.15f
            val grid=listOf("@/.","ABC","DEF","GHI","JKL","MNO","PQRS","TUV","WXYZ")
            grid.forEachIndexed{i,key->draw(key,rail+(i%3)*center/3,top+(i/3)*rowHeight,center/3,rowHeight)}
            listOf("，","。","？","！").forEachIndexed{i,key->draw(key,0f,top+i*rowHeight*3/4,rail,rowHeight*3/4)}
            listOf("backspace","分词","0").forEachIndexed{i,key->draw(key,rail+center,top+i*rowHeight,right,rowHeight)}
            var x=0f
            listOf("qwerty","emoji","space","enter").zip(listOf(13f,13f,46f,28f)).forEach{(key,weight)->val w=designWidth*weight/100;draw(key,x,top+rowHeight*3,w,rowHeight);x+=w}
        }else{
            val rows=listOf("qwertyuiop".map{it.toString()},"asdfghjkl".map{it.toString()},listOf("shift")+"zxcvbnm".map{it.toString()}+"backspace",listOf("123","emoji","space","enter"))
            rows.forEachIndexed { row,keys ->
                val indent=if(row==1)designWidth*0.05f else 0f
                val weights=if(row==3)listOf(13f,13f,46f,28f) else keys.map{if(it=="shift"||it=="backspace")1.5f else 1f}
                val unit=(designWidth-2*indent)/weights.sum()
                var x=indent
                keys.forEachIndexed{i,key->draw(key,x,top+row*rowHeight,unit*weights[i],rowHeight);x+=unit*weights[i]}
            }
        }
        if(showBottomBar){
        paint.color=ink;paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1.2f)
        val cy=designHeight-footer/2f
        val globe=dp(18f)
        canvas.drawCircle(globe,cy,dp(8f),paint)
        canvas.drawOval(RectF(globe-dp(3.5f),cy-dp(8f),globe+dp(3.5f),cy+dp(8f)),paint)
        canvas.drawLine(globe-dp(8f),cy,globe+dp(8f),cy,paint)
        val mic=designWidth-dp(20f)
        canvas.drawRoundRect(RectF(mic-dp(3f),cy-dp(8f),mic+dp(3f),cy+dp(3f)),dp(3f),dp(3f),paint)
        canvas.drawArc(RectF(mic-dp(6f),cy-dp(2f),mic+dp(6f),cy+dp(7f)),0f,180f,false,paint)
        canvas.drawLine(mic,cy+dp(7f),mic,cy+dp(10f),paint)
        paint.style=Paint.Style.FILL
        }
        if(dimmed){paint.color=if(dark)0xbb000000.toInt() else 0xbbffffff.toInt();canvas.drawRoundRect(RectF(0f,0f,designWidth.toFloat(),designHeight.toFloat()),dp(18f),dp(18f),paint)}
        canvas.restoreToCount(checkpoint)
    }
    private fun drawKey(canvas:Canvas,key:String,rect:RectF,keyColor:Int,control:Int,ink:Int){
        val bindingKey=key.lowercase()
        boxes.add(bindingKey to rect)
        val faceColor = if(key.length>1 && key.lowercase() !in listOf("abc","def","ghi","jkl","mno","pqrs","tuv","wxyz")) control else keyColor
        paint.color = faceColor
        canvas.drawRoundRect(rect,dp(6f),dp(6f),paint)
        paint.color=ink
        paint.textAlign=Paint.Align.CENTER
        paint.typeface=if(displayPrefs.keyBold)Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textSize=dp((if(key.length>1 && key !in listOf("shift","backspace","enter","emoji")) 15f else 21f)*displayPrefs.keyTextSize/24f)
        val label=when(key){ "shift"->"⇧";"backspace"->"⌫";"enter"->"↵";"emoji"->"☻";"space"->"";"qwerty"->"ABC";else->if(prefs.uppercaseLabels)key.uppercase() else key }
        val baseline = rect.centerY() - (paint.ascent()+paint.descent())/2
        paint.getTextBounds(label, 0, label.length, labelInk)
        val labelTop = if (label.isEmpty()) Float.POSITIVE_INFINITY else baseline + labelInk.top
        if (key == "enter") ReferenceReturnIcon.draw(canvas,rect.centerX(),rect.centerY(),minOf(dp(29f),rect.width()*.7f,rect.height()*.76f),ink)
        else canvas.drawText(label,rect.centerX(),baseline,paint)
        if (key == "space") {
            paint.textSize=dp(12f);paint.textAlign=Paint.Align.RIGHT
            canvas.drawText("中",rect.right-dp(10f),rect.bottom-dp(6f)-paint.descent(),paint)
        }
        if (showGestureHints && (gestureUp != null || !GlidePrefs(context).enabled)) {
            val binding = gestureUp?.let { prefs.binding(bindingKey, it) }
            val corner = KeyBindingLabels.defaultHint(bindingKey, t9)
            if (binding != null || corner.isNotEmpty()) {
                val area = RectF(rect.left + dp(3f), rect.top + dp(1.5f), rect.right - dp(3f),
                    minOf(rect.top + rect.height() * .27f, labelTop - dp(2f)))
                val hintColor = androidx.core.graphics.ColorUtils.blendARGB(ink, faceColor, .3f)
                bindingHint.draw(canvas, binding, corner, area, dp(10f), hintColor, ascii = false)
            }
        }
    }
    private var pressedKey: String? = null
    private var downX=0f
    private var downY=0f
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){
            downX=event.x;downY=event.y
            pressedKey=boxes.firstOrNull{it.second.contains(event.x/drawingScale,event.y/drawingScale)}?.first
        } else if(event.actionMasked==MotionEvent.ACTION_MOVE && kotlin.math.hypot(event.x-downX,event.y-downY)>android.view.ViewConfiguration.get(context).scaledTouchSlop){
            pressedKey=null
        } else if(event.actionMasked==MotionEvent.ACTION_CANCEL || event.actionMasked==MotionEvent.ACTION_POINTER_DOWN){
            pressedKey=null
        } else if(event.actionMasked==MotionEvent.ACTION_UP){
            performClick()
            val key=pressedKey;pressedKey=null
            if(key!=null && keyBounds(key)?.contains(event.x,event.y)==true){
                onKey?.invoke(key)
                keyBounds(key)?.let { onKeyAnchor?.invoke(key,it) }
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick();return true }
}
