// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Light counterpart of the reference's grouped sound/haptics page. */
class FeedbackTuningActivity : Activity() {
    private lateinit var prefs: FeedbackTuningPrefs
    private lateinit var body: LinearLayout
    private var backspace = false
    private var picker = false
    private var favoriteOnly = false
    private var toneCategory = 0
    private var previewGeneration = 0
    private val ink = 0xff161618.toInt()
    private val muted = 0xff818186.toInt()
    private val pageColor = 0xfff2f2f6.toInt()
    private val track = 0xffe4e4e9.toInt()
    private val blue = 0xff219bd8.toInt()
    private fun dp(n:Int) = (n*resources.displayMetrics.density+.5f).toInt()
    private fun round(color:Int,radius:Int=22) = GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun text(s:String,size:Float=18f,color:Int=ink) = TextView(this).apply { text=s;textSize=size;setTextColor(color);gravity=Gravity.CENTER_VERTICAL }
    override fun onCreate(state:Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar);super.onCreate(state)
        prefs=FeedbackTuningPrefs(this);backspace=state?.getBoolean("backspace")?:false
        picker=state?.getBoolean("picker")?:false;favoriteOnly=state?.getBoolean("favoriteOnly")?:false
        FeedbackTuningRuntime.prepare(applicationContext);render()
    }
    override fun onSaveInstanceState(out:Bundle) { super.onSaveInstanceState(out);out.putBoolean("backspace",backspace);out.putBoolean("picker",picker);out.putBoolean("favoriteOnly",favoriteOnly) }
    override fun onStop() { previewGeneration++;super.onStop() }
    @Suppress("DEPRECATION") override fun onBackPressed() { if(picker){picker=false;render()}else finish() }
    @Suppress("DEPRECATION") private fun render() {
        window.statusBarColor=pageColor;window.navigationBarColor=pageColor
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(pageColor);fitsSystemWindows=true }
        val header=FrameLayout(this).apply { setPadding(dp(16),dp(7),dp(16),dp(7)) }
        header.addView(text("‹ 返回",19f).apply { gravity=Gravity.CENTER;background=round(track);contentDescription="返回";setOnClickListener{onBackPressed()} },FrameLayout.LayoutParams(dp(83),dp(40),Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(text(if(picker)"音色" else "声音与触感",19f).apply { gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD },FrameLayout.LayoutParams(dp(160),dp(44),Gravity.CENTER))
        root.addView(header,LinearLayout.LayoutParams(-1,dp(58)))
        val scroll=ScrollView(this).apply { setPadding(dp(16),dp(22),dp(16),dp(24));isFillViewport=true }
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        if(picker)renderPicker() else renderSettings()
    }
    private fun space(h:Int=20) { body.addView(View(this),LinearLayout.LayoutParams(1,dp(h))) }
    private fun title(s:String) { body.addView(text(s,16f,muted).apply { setPadding(dp(16),dp(7),0,dp(7)) }) }
    private fun group() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;background=round(Color.WHITE);clipToOutline=true;body.addView(this,LinearLayout.LayoutParams(-1,-2)) }
    private fun row(parent:LinearLayout,label:String,value:String="",click:(()->Unit)?=null):LinearLayout {
        val r=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(16),0) }
        r.addView(text(label),LinearLayout.LayoutParams(0,dp(54),1f))
        if(value.isNotEmpty())r.addView(text(value,17f,muted),LinearLayout.LayoutParams(-2,dp(54)))
        if(click!=null){r.addView(text(" ›",22f,muted));r.setOnClickListener{click()}}
        parent.addView(r);return r
    }
    private fun toggle(parent:LinearLayout,label:String,value:Boolean,change:(Boolean)->Unit) {
        row(parent,label).addView(Switch(this).apply { isChecked=value;showText=false;thumbTintList=ColorStateList.valueOf(Color.WHITE)
            trackTintList=ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(0xff30d958.toInt(),track))
            setOnCheckedChangeListener{_,v->change(v)} })
    }
    private fun segment(parent:LinearLayout,labels:List<String>,selected:Int,change:(Int)->Unit) {
        val line=LinearLayout(this).apply { background=round(track);setPadding(dp(3),dp(3),dp(3),dp(3)) }
        labels.forEachIndexed{i,s->line.addView(text(s,17f).apply { gravity=Gravity.CENTER;background=if(i==selected)round(Color.WHITE,20)else null;isSelected=i==selected;setOnClickListener{change(i)} },LinearLayout.LayoutParams(0,dp(36),1f))}
        parent.addView(line,LinearLayout.LayoutParams(-1,dp(42)).apply{setMargins(dp(12),dp(8),dp(12),dp(6))})
    }
    private fun slider(parent:LinearLayout,label:String,value:Int,max:Int,unit:String="%",minimum:Int=0,change:(Int)->Unit) {
        val r=row(parent,label,"$value$unit");val output=r.getChildAt(1) as TextView
        parent.addView(SeekBar(this).apply { this.max=max-minimum;progress=value-minimum;contentDescription=label
            progressTintList=ColorStateList.valueOf(blue);thumbTintList=ColorStateList.valueOf(Color.WHITE);progressBackgroundTintList=ColorStateList.valueOf(track)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,n:Int,fromUser:Boolean) { if(fromUser){change(n+minimum);output.text="${n+minimum}$unit"} }
                override fun onStartTrackingTouch(bar:SeekBar?){}
                override fun onStopTrackingTouch(bar:SeekBar?){}
            })
        },LinearLayout.LayoutParams(-1,dp(32)).apply { setMargins(dp(12),0,dp(12),dp(12)) })
    }
    private fun renderSettings() {
        val switches=group();toggle(switches,"键盘触感",prefs.hapticEnabled){prefs.hapticEnabled=it};toggle(switches,"键盘声音",prefs.soundEnabled){prefs.soundEnabled=it}
        space(8);body.addView(text("遵循系统静音与触感设置。",13f,muted).apply {setPadding(dp(16),0,dp(16),0)})
        space(20);title("音色与音量");val sound=group()
        segment(sound,listOf("普通按键","退格"),if(backspace)1 else 0){backspace=it==1;render()}
        val p=prefs.profile(backspace)
        row(sound,"音色",FeedbackTuningProfile.TONES[p.tone]?:"系统"){picker=true;favoriteOnly=false;render()}
        slider(sound,"音量",p.volume,100){prefs.save(backspace,prefs.profile(backspace).copy(volume=it))}
        row(sound,"筛选音色","已收藏 ${prefs.favorites.size} 个"){picker=true;favoriteOnly=true;render()}
        toggle(sound,"松手声音",p.releaseSound){prefs.save(backspace,prefs.profile(backspace).copy(releaseSound=it))}
        space(22);title("触感");val haptic=group()
        row(haptic,"按下","${if(backspace)"退格" else "普通按键"}独立设置")
        row(haptic,"触感类型",FeedbackTuningProfile.HAPTICS[p.hapticStyle].orEmpty()) {
            val styles=FeedbackTuningProfile.HAPTICS
            android.app.AlertDialog.Builder(this).setTitle("触感类型")
                .setSingleChoiceItems(styles.values.toTypedArray(),styles.keys.indexOf(prefs.profile(backspace).hapticStyle)){dialog,index->
                    prefs.save(backspace,prefs.profile(backspace).copy(hapticStyle=styles.keys.elementAt(index)))
                    dialog.dismiss();render()
                }.setNegativeButton("取消",null).show()
        }
        slider(haptic,"力度",p.pressStrength*100/255,100){prefs.save(backspace,prefs.profile(backspace).copy(pressStrength=it*255/100))}
        slider(haptic,"按下时长",p.pressDuration,40," ms",1){prefs.save(backspace,prefs.profile(backspace).copy(pressDuration=it))}
        slider(haptic,"松手",p.releaseStrength*100/255,100){prefs.save(backspace,prefs.profile(backspace).copy(releaseStrength=it*255/100))}
        slider(haptic,"松手时长",p.releaseDuration,40," ms",1){prefs.save(backspace,prefs.profile(backspace).copy(releaseDuration=it))}
        val sample=row(haptic,"试听与触感预览","▶")
        sample.setOnClickListener{preview(sample)}
        space();val typing=group();val editor=EditText(this).apply {hint="在这里试打";textSize=18f;setSingleLine(false);setTextColor(ink);setHintTextColor(muted);visibility=View.GONE;setPadding(dp(16),dp(12),dp(16),dp(12));minLines=2}
        row(typing,"试用键盘","⌨") { editor.visibility=View.VISIBLE;editor.requestFocus();(getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT) }
        typing.addView(editor,LinearLayout.LayoutParams(-1,-2))
        space(12);row(group(),"音效来源","查看"){android.app.AlertDialog.Builder(this).setTitle("音效来源").setMessage("机械轴与静电容录音：Mechvibes，MIT 许可。另有本项目合成音，CC0。\n\nhttps://github.com/hainguyents13/mechvibes").setPositiveButton("完成",null).show()}
    }
    private fun preview(view:View,tone:String?=null) {
        val key=if(backspace)KeyEvent.KEYCODE_DEL else KeyEvent.KEYCODE_A
        val generation=++previewGeneration
        FeedbackTuningRuntime.sound(this,key,previewTone=tone);FeedbackTuningRuntime.haptic(view,key)
        view.postDelayed({if(generation==previewGeneration && !isFinishing && !isDestroyed){FeedbackTuningRuntime.sound(this,key,true,previewTone=tone);FeedbackTuningRuntime.haptic(view,key,true)}},100)
        if(!prefs.soundEnabled && !prefs.hapticEnabled)Toast.makeText(this,"声音与触感均已关闭",Toast.LENGTH_SHORT).show()
    }
    private fun renderPicker() {
        val g=group();segment(g,listOf("全部","已收藏"),if(favoriteOnly)1 else 0){favoriteOnly=it==1;render()}
        segment(g,listOf("全部音色","机械轴","键盘","合成"),toneCategory){toneCategory=it;render()}
        val entries=FeedbackTuningProfile.TONES.filterKeys{(!favoriteOnly||it in prefs.favorites) && when(toneCategory){
            1->it in FeedbackTuningProfile.RECORDINGS && it!="topre"
            2->it=="system" || it=="topre"
            3->it in listOf("crisp","soft","deep")
            else->true
        }}
        if(entries.isEmpty())row(g,"暂无收藏")
        entries.forEach{(id,name)->
            val line=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(6),dp(12),dp(6))}
            line.addView(text((if(id==prefs.profile(backspace).tone)"✓ " else "")+name,16f).apply{setPadding(dp(5),0,0,0);setOnClickListener{prefs.save(backspace,prefs.profile(backspace).copy(tone=id));picker=false;render()};contentDescription="选择 $name"},LinearLayout.LayoutParams(0,dp(58),1f))
            line.addView(text("▶",21f,blue).apply{gravity=Gravity.CENTER;contentDescription="试听 $name";setOnClickListener{preview(this,id)}},LinearLayout.LayoutParams(dp(43),dp(50)))
            line.addView(text(if(id in prefs.favorites)"★" else "☆",25f,blue).apply{gravity=Gravity.CENTER;contentDescription="收藏 $name";isSelected=id in prefs.favorites;setOnClickListener{prefs.favorites=if(id in prefs.favorites)prefs.favorites-id else prefs.favorites+id;render()}},LinearLayout.LayoutParams(dp(43),dp(50)))
            g.addView(line)
        }
    }
}
