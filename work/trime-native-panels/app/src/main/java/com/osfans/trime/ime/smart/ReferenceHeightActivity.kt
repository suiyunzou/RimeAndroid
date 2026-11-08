package com.osfans.trime.ime.smart

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** Height belongs to the letter keys; symbol and emoji browsers retain their own surface. */
class ReferenceHeightActivity : Activity() {
    private val prefs by lazy { SmartInputPrefs(this) }
    private lateinit var preview: SmartKeyboardPreview
    private lateinit var valueLabel: TextView
    private val presets=mutableListOf<Pair<Int,TextView>>()
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
    private fun shape(color:Int,radius:Int=23)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun text(value:String,size:Float=18f)=TextView(this).apply{text=value;textSize=size;setTextColor(0xff17171a.toInt());gravity=Gravity.CENTER_VERTICAL;includeFontPadding=false}
    override fun onCreate(state:Bundle?){
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(state)
        window.statusBarColor=0xfff2f2f6.toInt();window.navigationBarColor=0xfff2f2f6.toInt()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xfff2f2f6.toInt());fitsSystemWindows=true};setContentView(root)
        val heading=FrameLayout(this)
        heading.addView(text("‹",31f).apply{gravity=Gravity.CENTER;background=shape(0xffe4e4e9.toInt());contentDescription="返回";setOnClickListener{finish()}},FrameLayout.LayoutParams(dp(42),dp(42),Gravity.START or Gravity.CENTER_VERTICAL).apply{leftMargin=dp(16)})
        heading.addView(text("键盘高度",19f).apply{gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD},FrameLayout.LayoutParams(dp(160),-1,Gravity.CENTER))
        root.addView(heading,LinearLayout.LayoutParams(-1,dp(58)))
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(24))}
        root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        val handle=FrameLayout(this).apply{contentDescription="拖动调整键盘高度";isClickable=true}
        handle.addView(View(this).apply{background=shape(0xff909096.toInt(),2)},FrameLayout.LayoutParams(dp(38),dp(4),Gravity.CENTER))
        body.addView(handle,LinearLayout.LayoutParams(-1,dp(40)))
        preview=SmartKeyboardPreview(this).apply{dark=false;showGestureHints=false;keyboardBodyHeight=prefs.referenceKeyboardHeight;isClickable=false}
        body.addView(preview,LinearLayout.LayoutParams(-1,-2))
        var downY=0f;var startHeight=212
        handle.setOnTouchListener{v,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{downY=event.rawY;startHeight=prefs.referenceKeyboardHeight;v.parent.requestDisallowInterceptTouchEvent(true)}
                MotionEvent.ACTION_MOVE->update((startHeight+(downY-event.rawY)/resources.displayMetrics.density).toInt(),false)
                MotionEvent.ACTION_UP->{update(preview.keyboardBodyHeight,true);v.parent.requestDisallowInterceptTouchEvent(false);v.performClick()}
                MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{update(startHeight,false);v.parent.requestDisallowInterceptTouchEvent(false)}
            };true
        }
        val line=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(16),0,dp(8))}
        valueLabel=text("");line.addView(valueLabel,LinearLayout.LayoutParams(0,dp(42),1f))
        val stepper=LinearLayout(this).apply{background=shape(0xffe4e4e9.toInt(),18);gravity=Gravity.CENTER_VERTICAL}
        listOf("−" to -4,"+" to 4).forEach{(name,delta)->stepper.addView(text(name,25f).apply{gravity=Gravity.CENTER;contentDescription=if(delta<0)"降低键盘高度" else "增加键盘高度";setOnClickListener{update(prefs.referenceKeyboardHeight+delta,true)}},LinearLayout.LayoutParams(dp(42),dp(34)))}
        line.addView(stepper);body.addView(line)
        val choices=LinearLayout(this)
        listOf("紧凑" to 176,"标准" to 212,"加高" to 244).forEach{(name,height)->val button=text(name,19f).apply{gravity=Gravity.CENTER;setOnClickListener{update(height,true)}};presets.add(height to button);choices.addView(button,LinearLayout.LayoutParams(0,dp(54),1f).apply{setMargins(dp(3),0,dp(3),0)})}
        body.addView(choices)
        body.addView(text("拖动预览上边缘调整高度，松手保存。下次打开键盘生效。",16f).apply{setTextColor(0xff818186.toInt());setPadding(0,dp(18),0,0)})
        body.addView(text("仅调整字母按键区。表情、符号、剪贴板和底部区域保持原高度。",16f).apply{setTextColor(0xff818186.toInt());setPadding(0,dp(16),0,0)})
        update(prefs.referenceKeyboardHeight,false)
    }
    private fun update(height:Int,save:Boolean){
        val value=height.coerceIn(144,320)
        if(save)prefs.referenceKeyboardHeight=value
        preview.keyboardBodyHeight=value
        valueLabel.text=String.format(Locale.getDefault(),"按键高度  %.1f dp",value/4f)
        presets.forEach{(preset,view)->view.background=shape(if(preset==value)0xffcce6fb.toInt() else 0xffe4e4e9.toInt());view.setTextColor(if(preset==value)0xff1976c8.toInt() else 0xff6b6b72.toInt());view.isSelected=preset==value}
    }
}
