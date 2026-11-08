// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The six observed choices use exactly the same renderer as live correction metadata. */
class CorrectionStyleActivity : Activity() {
    private val store by lazy { getSharedPreferences("smart_input",MODE_PRIVATE) }
    private var scroll: ScrollView? = null
    private fun dp(v:Int)=(v*resources.displayMetrics.density+.5f).toInt()
    private fun rounded(color:Int,radius:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    private fun label(value:CharSequence,size:Float)=TextView(this).apply{text=value;textSize=size;setTextColor(0xff202126.toInt());includeFontPadding=false}
    override fun onCreate(state:Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(state);render()
    }
    @Suppress("DEPRECATION")
    private fun render() {
        val previousY=scroll?.scrollY?:0
        val selected=CorrectionStyleSpans.selected(this)
        window.statusBarColor=0xfff2f2f6.toInt();window.navigationBarColor=0xfff2f2f6.toInt()
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xfff2f2f6.toInt());fitsSystemWindows=true}
        val top=FrameLayout(this)
        top.addView(label("‹",31f).apply{gravity=Gravity.CENTER;background=rounded(0xffe4e4e9.toInt(),22);contentDescription="返回";setOnClickListener{finish()}},FrameLayout.LayoutParams(dp(42),dp(42),Gravity.START or Gravity.CENTER_VERTICAL).apply{leftMargin=dp(16)})
        top.addView(label("纠错样式",19f).apply{gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD},FrameLayout.LayoutParams(dp(150),-1,Gravity.CENTER))
        root.addView(top,LinearLayout.LayoutParams(-1,dp(58)))
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(20))}
        val container=ScrollView(this).apply{isFillViewport=true;addView(body)};scroll=container
        root.addView(container,LinearLayout.LayoutParams(-1,0,1f))
        val preview=FrameLayout(this).apply{background=rounded(0xffd1d3d9.toInt(),20);clipToOutline=true;contentDescription="纠错样式实时预览"}
        preview.addView(SmartKeyboardPreview(this).apply{isClickable=false},FrameLayout.LayoutParams(-1,-1))
        val header=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(2),dp(8),0);setBackgroundColor(0xffd1d3d9.toInt())}
        header.addView(label(CorrectionStyleSpans.render(CorrectionStyle.plan("womeitiandouyongshirifa","womeitiandouyongshurufa",selected)),10f),LinearLayout.LayoutParams(-1,dp(14)))
        header.addView(label("我每天都用输入法",17f).apply{setTextColor(0xff326ca9.toInt())},LinearLayout.LayoutParams(-1,dp(23)))
        preview.addView(header,FrameLayout.LayoutParams(-1,dp(37),Gravity.TOP))
        body.addView(preview,LinearLayout.LayoutParams(-1,dp(224)))
        val choices=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(4),dp(12),dp(4));background=rounded(Color.WHITE,22)}
        body.addView(choices,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(14)})
        CorrectionStyle.labels.forEach{(id,title)->
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;contentDescription=title;isClickable=true;isFocusable=true;isSelected=id==selected
                setOnClickListener{store.edit().putString("correction_style",id).apply();render()}}
            val words=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL}
            words.addView(label(title,17f),LinearLayout.LayoutParams(-1,dp(28)))
            words.addView(label(CorrectionStyleSpans.render(CorrectionStyle.plan("qugio","quguo",id)),23f).apply{typeface=Typeface.MONOSPACE},LinearLayout.LayoutParams(-1,dp(32)))
            row.addView(words,LinearLayout.LayoutParams(0,dp(80),1f))
            row.addView(label(if(id==selected)"●" else "○",25f).apply{gravity=Gravity.CENTER;setTextColor(if(id==selected)0xff486882.toInt() else 0xff95979b.toInt());contentDescription=if(id==selected)"已选择" else "未选择"},LinearLayout.LayoutParams(dp(34),dp(56)))
            choices.addView(row)
        }
        setContentView(root);container.post{container.scrollTo(0,previousY)}
    }
}
