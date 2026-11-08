package com.osfans.trime.ime.smart

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** Key-anchored binding menu. All demonstrations remain inside this popup. */
class KeyBindingPopup(
    private val activity: Activity,
    private val dark: Boolean,
    private val prefs: SmartInputPrefs,
    private val key: String,
    private val up: Boolean,
    private val editPhrase: () -> Unit,
    private val changed: () -> Unit,
    private val dismissed: () -> Unit,
) {
    private val ink = if(dark) Color.WHITE else 0xff17171a.toInt()
    private val muted = if(dark) 0xffb1b1b8.toInt() else 0xff6e6e77.toInt()
    private val card = if(dark) 0xff353539.toInt() else 0xfff4f4f8.toInt()
    private val track = if(dark) 0xff48484e.toInt() else 0xffe2e3e9.toInt()
    private val blue = 0xff168fdf.toInt()
    private fun dp(v:Int) = (v*activity.resources.displayMetrics.density+.5f).toInt()
    private fun rounded(color:Int,radius:Int=18) = GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun text(value:String,size:Float=17f,color:Int=ink) = TextView(activity).apply{text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER_VERTICAL}
    private val panel = LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(8));background=rounded(card,24)}
    private val content = LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
    private val popup = PopupWindow(activity)
    private var symbols=true
    private var tabButtons=mutableListOf<TextView>()
    private var pointerX=0f
    private var above=true

    fun show(anchor: View, bounds: RectF) {
        val visible=Rect();anchor.getWindowVisibleDisplayFrame(visible)
        val frame=FrameLayout(activity)
        val header=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(text("${KeyBindingLabels.keyName(key)} ${if(up)"↑" else "↓"}",20f),LinearLayout.LayoutParams(0,dp(42),1f))
        val tabs=LinearLayout(activity).apply{background=rounded(track,20);setPadding(dp(3),dp(3),dp(3),dp(3))}
        listOf("符号","功能").forEachIndexed { index,label ->
            val button=text(label,14f).apply{gravity=Gravity.CENTER;setOnClickListener{symbols=index==0;renderOptions()}}
            tabButtons.add(button);tabs.addView(button,LinearLayout.LayoutParams(dp(48),dp(30)))
        }
        header.addView(tabs);panel.addView(header)
        val scroller=ScrollView(activity).apply{isFillViewport=true;addView(content)}
        panel.addView(scroller,LinearLayout.LayoutParams(-1,minOf(dp(250),(visible.height()-dp(112)).coerceAtLeast(dp(96)))))
        val footer=LinearLayout(activity)
        footer.addView(text("恢复此键",14f,muted).apply{gravity=Gravity.CENTER;setOnClickListener{prefs.setBinding(key,up,null);changed();popup.dismiss()}},LinearLayout.LayoutParams(0,dp(40),1f))
        footer.addView(text("完成",16f,blue).apply{gravity=Gravity.CENTER;setOnClickListener{popup.dismiss()}},LinearLayout.LayoutParams(0,dp(40),1f))
        panel.addView(footer)
        val location=IntArray(2);anchor.getLocationOnScreen(location)
        val anchorTop=location[1]+bounds.top.toInt();val anchorBottom=location[1]+bounds.bottom.toInt()
        val width=minOf(dp(330),visible.width()-dp(24))
        panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val panelHeight=panel.measuredHeight
        above=anchorTop-visible.top>=panelHeight+dp(12) || visible.bottom-anchorBottom<panelHeight+dp(12)
        val x=(location[0]+bounds.centerX().toInt()-width/2).coerceIn(visible.left+dp(12),visible.right-width-dp(12))
        val y=(if(above)anchorTop-panelHeight-dp(10) else anchorBottom).coerceIn(visible.top, maxOf(visible.top,visible.bottom-panelHeight-dp(10)))
        pointerX=(location[0]+bounds.centerX()-x).coerceIn(dp(24).toFloat(),width-dp(24).toFloat())
        frame.addView(panel,FrameLayout.LayoutParams(-1,panelHeight).apply{topMargin=if(above)0 else dp(10)})
        frame.addView(object:View(activity){
            override fun onDraw(canvas:Canvas){
                val path=Path().apply{moveTo(pointerX-dp(12),if(above)0f else height.toFloat());lineTo(pointerX+dp(12),if(above)0f else height.toFloat());lineTo(pointerX,if(above)height.toFloat() else 0f);close()}
                canvas.drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply{color=card})
            }
        },FrameLayout.LayoutParams(-1,dp(11),if(above)Gravity.BOTTOM else Gravity.TOP))
        popup.contentView=frame;popup.width=width;popup.height=panelHeight+dp(10)
        popup.isFocusable=true;popup.isOutsideTouchable=true;popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));popup.elevation=dp(10).toFloat()
        popup.setOnDismissListener{dismissed()}
        renderOptions()
        popup.showAtLocation(anchor,Gravity.TOP or Gravity.LEFT,x,y)
    }
    private fun renderOptions() {
        tabButtons.forEachIndexed { index,button -> button.background=if((index==0)==symbols)rounded(if(dark)0xff66666c.toInt() else Color.WHITE,18) else null }
        content.removeAllViews()
        if(symbols) {
            listOf("1234567890","!?.,:;@#$%","()[]{}<>/\\","，。？！；：、…—～").forEach { row ->
                val line=LinearLayout(activity)
                row.forEach { symbol -> line.addView(text(symbol.toString(),19f).apply{gravity=Gravity.CENTER;background=rounded(track,6);setOnClickListener{prefs.setBinding(key,up,"text:$symbol");changed();popup.dismiss()}},LinearLayout.LayoutParams(0,dp(38),1f).apply{setMargins(dp(1),dp(2),dp(1),dp(2))}) }
                content.addView(line)
            }
            content.addView(text("自定义短语…",17f,blue).apply{gravity=Gravity.CENTER;setOnClickListener{popup.dismiss();editPhrase()}},LinearLayout.LayoutParams(-1,dp(44)))
        } else {
            KeyBindingLabels.actions.forEach { (id,label) ->
                val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;contentDescription=label;setPadding(dp(8),0,dp(8),0)}
                row.addView(actionIcon(id),LinearLayout.LayoutParams(dp(30),dp(30)))
                row.addView(text(label,17f).apply{setPadding(dp(10),0,0,0)},LinearLayout.LayoutParams(-1,-1))
                row.setOnClickListener{prefs.setBinding(key,up,"action:$id");changed();showActionDemo(id)}
                content.addView(row,LinearLayout.LayoutParams(-1,dp(41)))
            }
        }
    }
    private fun showActionDemo(id:String) {
        content.removeAllViews()
        val initial=android.text.SpannableString("明天下午三点开会。").apply{
            if(id in listOf("cut","copy","copy_all","clear_all","select_all"))setSpan(android.text.style.BackgroundColorSpan(0x553993ee),if(id=="cut"||id=="copy")4 else 0,if(id=="cut"||id=="copy")6 else length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val sample=text("",16f).apply{text=initial;gravity=Gravity.CENTER;background=rounded(track,10)}
        content.addView(sample,LinearLayout.LayoutParams(-1,dp(58)).apply{topMargin=dp(6)})
        val label=KeyBindingLabels.actions.getValue(id)
        val button=text("${KeyBindingLabels.corner("action:$id")}\n$label",20f,blue).apply{gravity=Gravity.CENTER;background=rounded(track,14)}
        val box=FrameLayout(activity)
        box.addView(button,FrameLayout.LayoutParams(dp(96),dp(85),Gravity.CENTER))
        content.addView(box,LinearLayout.LayoutParams(-1,dp(102)))
        val status=text("已保存 · ${if(up)"上拨" else "下拨"}试用",13f,muted).apply{gravity=Gravity.CENTER}
        content.addView(status,LinearLayout.LayoutParams(-1,dp(28)))
        fun demonstrate(){
            sample.text=when(id){"cut"->"明天下午开会。";"clear_all"->"";"paste"->"明天下午三点开会。已粘贴";else->"明天下午三点开会。"}
            status.text="预览：${when(id){"cut"->"已剪切“三点”";"copy_all"->"已复制全文";"copy"->"已复制“三点”";"clear_all"->"已清空全文";"paste"->"已粘贴示例文字";else->"已全选"}}"
        }
        button.setOnClickListener{demonstrate()}
        var startY=0f
        button.setOnTouchListener{_,event->when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{startY=event.y;true}
            MotionEvent.ACTION_UP->{if((if(up)startY-event.y else event.y-startY)>dp(12))demonstrate() else button.performClick();true}
            else->true
        }}
        content.addView(text("‹ 返回功能列表",14f,blue).apply{setOnClickListener{renderOptions()}},LinearLayout.LayoutParams(-1,dp(42)))
    }
    private fun actionIcon(id:String)=object:View(activity){
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas){
            canvas.save();canvas.scale(width/30f,height/30f)
            paint.color=if(id=="clear_all")0xffdc5264.toInt() else blue
            paint.strokeWidth=1.7f;paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.ROUND
            when(id){
                "cut"->{canvas.drawCircle(7f,8f,3f,paint);canvas.drawCircle(7f,22f,3f,paint);canvas.drawLine(10f,10f,25f,23f,paint);canvas.drawLine(10f,20f,25f,6f,paint)}
                "paste"->{canvas.drawRoundRect(RectF(8f,6f,24f,27f),2f,2f,paint);canvas.drawRoundRect(RectF(12f,3f,20f,9f),2f,2f,paint);canvas.drawLine(12f,15f,20f,15f,paint);canvas.drawLine(12f,20f,20f,20f,paint)}
                "clear_all"->{canvas.drawCircle(15f,15f,11f,paint);canvas.drawLine(11f,11f,19f,19f,paint);canvas.drawLine(19f,11f,11f,19f,paint)}
                "select_all"->{canvas.drawRoundRect(RectF(4f,4f,26f,26f),2f,2f,paint);canvas.drawLine(8f,11f,22f,11f,paint);canvas.drawLine(8f,18f,22f,18f,paint)}
                else->{canvas.drawRoundRect(RectF(5f,3f,20f,23f),2f,2f,paint);canvas.drawRoundRect(RectF(10f,8f,25f,28f),2f,2f,paint)}
            }
            canvas.restore()
        }
    }
}
