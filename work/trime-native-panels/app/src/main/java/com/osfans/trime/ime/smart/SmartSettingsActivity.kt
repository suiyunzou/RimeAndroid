package com.osfans.trime.ime.smart

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.SmartSettingsFacade
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.ui.settingskit.Palette
import com.osfans.trime.ui.settingskit.SettingsBuilder
import com.osfans.trime.ui.settingskit.SettingsPalette
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Full-screen settings matching the reference flow; editing always belongs to this Activity. */
class SmartSettingsActivity : Activity() {
    private lateinit var prefs: SmartInputPrefs
    private val extra by lazy { getSharedPreferences("smart_input",MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var builder:SettingsBuilder
    private lateinit var facade:SmartSettingsFacade
    private var page="home"
    private var renderedPage="home"
    private var pageScrollView:ScrollView?=null
    private val pageScrollPositions=mutableMapOf<String,Int>()
    private var readingThemes=false
    private var wheelTab=0
    private var gestureUp=false
    private var keyPreviewLayout="qwerty"
    private var bindingPreview:SmartKeyboardPreview?=null
    private var modelLabelView:TextView?=null
    private var heightExpanded=false
    private val activityScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var dark=false
    private val palette get():Palette=builder.palette
    private val ink get()=palette.ink
    private val muted get()=palette.muted
    private val backgroundColor get()=palette.background
    private val groupColor get()=palette.group
    private val selectedColor get()=palette.selected
    private val trackColor get()=palette.track
    private fun dp(v:Int)=SettingsPalette.dp(this,v)
    private fun rounded(color:Int,radius:Int=SettingsPalette.RADIUS_GROUP)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat()}
    /** White (dark: #2C2C2E) capsule with a hairline border, as in the reference top bar. */
    private fun pillBackground():GradientDrawable=rounded(if(dark)0xff2c2c2e.toInt() else 0xffffffff.toInt(),24).apply{
        setStroke(dp(1),if(dark)0xff3a3a3c.toInt() else 0xffe4e4e9.toInt())
    }
    private fun text(value:String,size:Float=18f,color:Int=ink)=TextView(this).apply{text=value;textSize=size;setTextColor(color);gravity=Gravity.CENTER_VERTICAL}
    override fun onCreate(state:Bundle?){
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(state)
        prefs=SmartInputPrefs(this)
        facade=SmartSettingsFacade.fromDefault(this)
        page=state?.getString("page")?:intent.getStringExtra("page")?:"home"
        wheelTab=state?.getInt("wheelTab")?:0
        gestureUp=state?.getBoolean("gestureUp")?:false
        keyPreviewLayout=state?.getString("keyPreviewLayout")?:prefs.layout
        heightExpanded=state?.getBoolean("heightExpanded")?:false
        state?.getBundle("pageScrollPositions")?.let{saved->saved.keySet().forEach{key->pageScrollPositions[key]=saved.getInt(key)}}
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        render()
    }
    override fun onSaveInstanceState(out:Bundle){savePageScroll();super.onSaveInstanceState(out);out.putString("page",page);out.putInt("wheelTab",wheelTab);out.putBoolean("gestureUp",gestureUp);out.putString("keyPreviewLayout",keyPreviewLayout);out.putBoolean("heightExpanded",heightExpanded);out.putBundle("pageScrollPositions",Bundle().apply{pageScrollPositions.forEach{(key,value)->putInt(key,value)}})}
    override fun onDestroy(){activityScope.cancel();super.onDestroy()}
    override fun onResume(){
        super.onResume()
        if(::prefs.isInitialized){
            render()
            activityScope.launch {
                withContext(Dispatchers.IO) {
                    runCatching { ModelManagerStore(DataManager.userDataDir) { a,b -> android.system.Os.rename(a.absolutePath,b.absolutePath) }.identify() }
                }
                if(!isDestroyed)modelLabelView?.text=ModelManagerStore.currentLabel(DataManager.userDataDir)
            }
        }
    }
    override fun onPause(){savePageScroll();super.onPause()}
    @Suppress("DEPRECATION")
    override fun onBackPressed(){if(page=="home")finish() else open("home")}
    private fun open(next:String){page=next;render()}
    private fun savePageScroll(){pageScrollView?.takeIf{it.parent!=null}?.let{pageScrollPositions[renderedPage]=it.scrollY}}
    // Light/dark has one authoritative source: AppPrefs.Advanced.uiMode, reached through the
    // facade. The old smart_input.reference_settings_theme copy is no longer read or written.
    private fun selectedUiMode():AppPrefs.Advanced.UiMode=facade.uiMode()
    private fun setUiMode(mode:AppPrefs.Advanced.UiMode){
        facade.setUiMode(mode)
        AppCompatDelegate.setDefaultNightMode(when(mode){AppPrefs.Advanced.UiMode.DARK->AppCompatDelegate.MODE_NIGHT_YES;AppPrefs.Advanced.UiMode.LIGHT->AppCompatDelegate.MODE_NIGHT_NO;else->AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM})
        applyKeyboardAppearance(when(mode){AppPrefs.Advanced.UiMode.LIGHT->1;AppPrefs.Advanced.UiMode.DARK->2;else->0})
    }
    private fun resolvedDark(mode:AppPrefs.Advanced.UiMode):Boolean=when(mode){AppPrefs.Advanced.UiMode.DARK->true;AppPrefs.Advanced.UiMode.LIGHT->false;else->resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES}
    @Suppress("DEPRECATION")
    private fun render(){
        savePageScroll()
        dark=resolvedDark(selectedUiMode())
        // The palette/ink/muted/... getters all read builder.palette, so the builder must exist
        // before any of them is touched. Initialising it after the status-bar colours used to
        // crash on launch with "lateinit property builder has not been initialized".
        builder=SettingsBuilder(this,dark);body=builder.body
        window.statusBarColor=backgroundColor;window.navigationBarColor=backgroundColor
        window.decorView.systemUiVisibility=if(!dark && Build.VERSION.SDK_INT>=26) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(backgroundColor);fitsSystemWindows=true}
        setContentView(root)
        val heading=when(page){"display"->"显示";"keys"->"按键";"wheel"->"空格轮盘";"input"->"输入";else->"键盘设置"}
        val top=FrameLayout(this).apply{setPadding(dp(16),dp(7),dp(16),dp(7))}
        val back=text("‹ 返回",19f).apply{gravity=Gravity.CENTER;background=pillBackground();setOnClickListener{onBackPressed()};contentDescription="返回"}
        top.addView(back,FrameLayout.LayoutParams(dp(83),dp(40),Gravity.START or Gravity.CENTER_VERTICAL))
        top.addView(text(heading,19f).apply{gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD},FrameLayout.LayoutParams(dp(150),dp(44),Gravity.CENTER))
        if(page=="wheel")top.addView(text("↶",27f).apply{gravity=Gravity.CENTER;background=rounded(trackColor,22);contentDescription="恢复默认轮盘";setOnClickListener{prefs.wheelSlots=listOf("time","traditional","symbols","clipboard","settings");prefs.wheelStyle="rainbow";render()}},FrameLayout.LayoutParams(dp(42),dp(42),Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(top,LinearLayout.LayoutParams(-1,dp(58)))
        val scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false;isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false;setPadding(dp(16),dp(7),dp(16),dp(24))}
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        when(page){"display"->displayPage();"keys"->keysPage();"wheel"->wheelPage();"input"->inputPage();else->homePage()}
        pageScrollView=scroll;renderedPage=page
        val position=pageScrollPositions[page]?:0
        scroll.post{if(pageScrollView===scroll && scroll.parent!=null)scroll.scrollTo(0,position)}
    }
    private fun space(h:Int=16){builder.space(h)}
    private fun section(title:String){builder.section(title)}
    private fun group():LinearLayout=builder.group()
    private fun row(group:LinearLayout?,title:String,value:String="",action:(()->Unit)?=null):LinearLayout=builder.row(group,title,value,onClick=action)
    private fun toggle(group:LinearLayout?,title:String,value:Boolean,action:(Boolean)->Unit){builder.toggle(group,title,value,action=action)}
    private fun preview(touch:((String)->Unit)?=null,up:Boolean?=null):SmartKeyboardPreview{
        val p=SmartKeyboardPreview(this).apply{dark=this@SmartSettingsActivity.dark;onKey=touch;gestureUp=up}
        p.keyboardBodyHeight=keyboardHeight();body.addView(p,LinearLayout.LayoutParams(-1,-2));return p
    }
    private fun keyboardHeight():Int=extra.getInt("reference_keyboard_height",212).coerceIn(144,320)
    private fun homePage(){
        space(7)
        homeCard("input","输入","拼音纠错与中英文混输",0xff169bdd.toInt()){open("input")}
        homeCard("display","显示","候选字号与键盘外观",0xff8b75db.toInt()){open("display")}
        homeCard("layout","键盘布局","全键盘或九宫格",0xff8b8e96.toInt()){
            choose("键盘布局",listOf("全键盘","九宫格"),if(prefs.layout=="t9")1 else 0){prefs.layout=if(it==1)"t9" else "qwerty";render()}
        }
        homeCard("keys","按键","拨动输入的符号与功能",0xff19bcbf.toInt()){open("keys")}
        homeCard("wheel","空格轮盘","快捷功能与轮盘外观",0xffe19843.toInt()){open("wheel")}
        homeCard("feedback","声音与触感","试听音色与调节按键反馈",0xffd17e9c.toInt()){startActivity(Intent(this,FeedbackTuningActivity::class.java))}
        homeCard("dictionary","词库","下载万象词库与添加自己的词条",0xff5c9a75.toInt()){startActivity(Intent(this,WordLibraryActivity::class.java))}
        homeCard("advanced","高级","方案、部署与数据备份",0xff7b8ea8.toInt()){openAdvancedSettings()}
        homeCard("privacy","隐私","离线运行与本地数据",0xff5d92be.toInt()){startActivity(Intent(this,OfflinePrivacyActivity::class.java))}
    }
    /** Hands the native Advanced page its route the same way AppUtils.launchMainToDest does. */
    private fun openAdvancedSettings(){
        startActivity(Intent(this,MainActivity::class.java).apply{
            action=Intent.ACTION_RUN
            putExtra(MainActivity.EXTRA_SETTINGS_ROUTE,NavigationRoute.Advanced as android.os.Parcelable)
        })
    }
    private fun showInputTrial(){
        val dialog=Dialog(this).apply{requestWindowFeature(Window.FEATURE_NO_TITLE)}
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(16));background=rounded(groupColor,24)}
        panel.addView(text("试用输入",19f).apply{typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(-1,dp(42)))
        val editor=EditText(this).apply{hint="在这里试用输入法";textSize=19f;setTextColor(ink);setHintTextColor(muted);setPadding(dp(12),dp(10),dp(12),dp(10));background=rounded(trackColor,12);gravity=Gravity.TOP;minLines=3;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;contentDescription="试用输入框"}
        panel.addView(editor,LinearLayout.LayoutParams(-1,dp(118)))
        panel.addView(text("完成",18f,0xff178ed0.toInt()).apply{gravity=Gravity.CENTER;setOnClickListener{dialog.dismiss()}},LinearLayout.LayoutParams(-1,dp(48)))
        dialog.setContentView(panel);dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnDismissListener{editor.text.clear()}
        dialog.show();dialog.window?.setLayout(resources.displayMetrics.widthPixels-dp(40),-2);editor.requestFocus()
        editor.post{if(dialog.isShowing)(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(editor,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}
    }
    private fun homeCard(id:String,title:String,subtitle:String,tint:Int,action:()->Unit){
        val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(15),dp(16),dp(14));background=rounded(groupColor,23);setOnClickListener{action()};contentDescription="$title，$subtitle"}
        val icon=object:View(this){
            private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas:Canvas){
                val scale=width/40f;canvas.save();canvas.scale(scale,scale)
                pen.color=(tint and 0x00ffffff) or if(dark)0x33000000 else 0x18000000
                canvas.drawRoundRect(RectF(0f,0f,40f,40f),10f,10f,pen)
                pen.color=tint;pen.strokeWidth=1.8f;pen.strokeCap=Paint.Cap.ROUND;pen.strokeJoin=Paint.Join.ROUND
                when(id){
                    "input"->{pen.textSize=22f;pen.typeface=Typeface.DEFAULT;canvas.drawText("字",7f,28f,pen);pen.style=Paint.Style.STROKE;canvas.drawLine(31f,9f,31f,31f,pen);canvas.drawLine(28f,9f,34f,9f,pen);canvas.drawLine(28f,31f,34f,31f,pen)}
                    "display"->{pen.textSize=18f;pen.typeface=Typeface.DEFAULT_BOLD;canvas.drawText("大",4f,27f,pen);pen.textSize=13f;canvas.drawText("小",23f,27f,pen)}
                    "layout"->{pen.style=Paint.Style.STROKE;canvas.drawRoundRect(RectF(7f,11f,33f,29f),3f,3f,pen);pen.style=Paint.Style.FILL;for(r in 0..1)for(c in 0..5)canvas.drawRect(10f+c*3.6f,15f+r*4f,11.7f+c*3.6f,16.7f+r*4f,pen);canvas.drawRoundRect(RectF(13f,24f,27f,26f),1f,1f,pen)}
                    "keys"->{pen.style=Paint.Style.STROKE;val finger=Path().apply{moveTo(17f,31f);lineTo(11f,22f);quadTo(9f,18f,13f,19f);lineTo(17f,23f);lineTo(15f,11f);quadTo(15f,7f,18f,9f);lineTo(21f,20f);quadTo(26f,15f,29f,22f);lineTo(31f,29f);quadTo(27f,36f,20f,33f);close()};canvas.drawPath(finger,pen);canvas.drawArc(RectF(8f,4f,25f,22f),170f,180f,false,pen)}
                    "feedback"->{pen.style=Paint.Style.STROKE;val speaker=Path().apply{moveTo(7f,16f);lineTo(13f,16f);lineTo(21f,9f);lineTo(21f,31f);lineTo(13f,24f);lineTo(7f,24f);close()};canvas.drawPath(speaker,pen);canvas.drawArc(RectF(19f,12f,31f,28f),-65f,130f,false,pen);canvas.drawArc(RectF(19f,7f,37f,33f),-65f,130f,false,pen)}
                    "dictionary"->{pen.style=Paint.Style.STROKE;canvas.drawRoundRect(RectF(9f,7f,31f,33f),3f,3f,pen);canvas.drawLine(14f,7f,14f,33f,pen);canvas.drawLine(19f,15f,27f,15f,pen);canvas.drawLine(19f,21f,27f,21f,pen)}
                    "advanced"->{pen.style=Paint.Style.STROKE;canvas.drawCircle(20f,19f,7f,pen);for(i in 0..7){val angle=Math.toRadians(i*45.0);val cx=cos(angle).toFloat();val cy=sin(angle).toFloat();canvas.drawLine(20f+cx*9f,19f+cy*9f,20f+cx*13f,19f+cy*13f,pen)};canvas.drawCircle(20f,19f,2.6f,pen)}
                    "privacy"->{pen.style=Paint.Style.STROKE;val shield=Path().apply{moveTo(20f,6f);lineTo(32f,11f);lineTo(30f,24f);quadTo(28f,30f,20f,35f);quadTo(12f,30f,10f,24f);lineTo(8f,11f);close()};canvas.drawPath(shield,pen);canvas.drawLine(14f,20f,18f,24f,pen);canvas.drawLine(18f,24f,27f,15f,pen)}
                    else->{pen.style=Paint.Style.STROKE;canvas.drawArc(RectF(8f,12f,32f,36f),190f,160f,false,pen);pen.style=Paint.Style.FILL;canvas.drawCircle(20f,30f,2f,pen);for(i in 0..4){val angle=Math.toRadians(168.0-i*39.0);canvas.drawCircle(20f+16f*cos(angle).toFloat(),30f-16f*sin(angle).toFloat(),2f,pen)}}
                }
                pen.style=Paint.Style.FILL;canvas.restore()
            }
        }
        card.addView(icon,LinearLayout.LayoutParams(dp(40),dp(40)))
        card.addView(text(title,19f).apply{setPadding(0,dp(9),0,0)},LinearLayout.LayoutParams(-1,dp(36)))
        card.addView(text(subtitle,14f,muted),LinearLayout.LayoutParams(-1,dp(24)))
        body.addView(card,LinearLayout.LayoutParams(-1,dp(136)));space(12)
    }
    private fun inputPage(){
        space(14)
        val sms=group();row(sms,"短信验证码",if(SmsCodeInbox.isAvailable(this))"已开启" else "未开启"){startActivity(Intent(this,SmsCodeSettingsActivity::class.java))}
        space()
        val chinese=group();toggle(chinese,"拼音纠错",prefs.correction){prefs.correction=it};toggle(chinese,"候选中显示表情",prefs.candidateEmoji){prefs.candidateEmoji=it}
        row(chinese,"纠错样式",CorrectionStyle.labels[CorrectionStyleSpans.selected(this)].orEmpty()){startActivity(Intent(this,CorrectionStyleActivity::class.java))}
        row(chinese,"整句模型","切换"){startActivity(Intent(this,ModelManagerActivity::class.java))}
        row(chinese,"语音模型",if(com.osfans.trime.ime.voice.VoiceModelStore.status(this)==com.osfans.trime.ime.voice.VoiceModelStore.Status.READY)"已下载" else "未下载"){startActivity(Intent(this,com.osfans.trime.ime.voice.VoiceModelActivity::class.java))}
        chinese.addView(text(ModelManagerStore.currentLabel(DataManager.userDataDir),14f,muted).apply{
            modelLabelView=this
            setPadding(dp(16),0,dp(16),dp(12));setOnClickListener{startActivity(Intent(this@SmartSettingsActivity,ModelManagerActivity::class.java))}
        })
        space();section("实验性功能")
        val glide=GlidePrefs(this)
        val experimental=group();toggle(experimental,"滑行输入",glide.enabled){glide.enabled=it;render()}
        row(experimental,"拨动输入",if(glide.enabled)"滑行开启时暂停" else when(prefs.flickDirection){FlickDirection.OFF->"已关闭";FlickDirection.UP->"上拨";FlickDirection.DOWN->"下拨"}){open("keys")}
        space();section("拼音与光标")
        val info=group()
        val pinyinGesture=PinyinGesturePrefs(this);toggle(info,"空格滑动编辑拼音",pinyinGesture.enabled){pinyinGesture.enabled=it}
        val cursorGesture=CursorGesturePrefs(this);toggle(info,"长按字母移动光标",cursorGesture.enabled){cursorGesture.enabled=it}
        space();val mixed=group();row(mixed,"中英文混输","自动识别");row(mixed,"成对符号","居中 · 成对删除")
    }
    private fun displayPage(){
        val scroll=root.getChildAt(1) as ScrollView
        root.removeView(scroll)
        val keyboardPreview=SmartKeyboardPreview(this).apply{dark=this@SmartSettingsActivity.dark;keyboardBodyHeight=keyboardHeight();showGestureHints=false}
        root.addView(keyboardPreview,LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(16),dp(7),dp(16),dp(10))})
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val g=group()
        // The builder's segments() attaches to body; this row needs the control inline,
        // so an unattached track is built here and handed to rowWithControl instead.
        val candidateCurrent=when(prefs.candidateSize){16->0;24,28,32->2;else->1}
        val candidateTrack=LinearLayout(this).apply{
            gravity=Gravity.CENTER_VERTICAL;background=rounded(trackColor,SettingsPalette.RADIUS_PILL);setPadding(dp(3),dp(3),dp(3),dp(3))
            listOf("小","标准","大").forEachIndexed{i,s->addView(text(s,14f,if(i==candidateCurrent)ink else muted).apply{gravity=Gravity.CENTER;background=if(i==candidateCurrent)rounded(selectedColor,SettingsPalette.RADIUS_SEGMENT) else null;setOnClickListener{prefs.candidateSize=listOf(16,20,24)[i];render()}},LinearLayout.LayoutParams(0,dp(30),1f))}
            layoutParams=LinearLayout.LayoutParams(dp(172),dp(36))
        }
        builder.rowWithControl(g,"候选文字",candidateTrack)
        toggle(g,"候选中显示表情",prefs.candidateEmoji){prefs.candidateEmoji=it;keyboardPreview.invalidate()}
        val displayPrefs=ReferenceDisplayPrefs(this)
        row(g,"键面文字",when(displayPrefs.keyTextSize){20->"小";28->"大";else->"标准"}){choose("键面文字",listOf("小","标准","大"),listOf(20,24,28).indexOf(displayPrefs.keyTextSize)){displayPrefs.keyTextSize=listOf(20,24,28)[it];render()}}
        row(g,"粗细",if(displayPrefs.keyBold)"粗体" else "普通"){choose("键面文字粗细",listOf("普通","粗体"),if(displayPrefs.keyBold)1 else 0){displayPrefs.keyBold=it==1;render()}}
        row(g,"候选粗细",if(prefs.candidateBold)"粗体" else "普通"){choose("候选文字粗细",listOf("普通","粗体"),if(prefs.candidateBold)1 else 0){prefs.candidateBold=it==1;render()}}
        row(g,"字母显示",if(prefs.uppercaseLabels)"始终大写" else "随输入状态"){choose("字母显示",listOf("随输入状态","始终大写"),if(prefs.uppercaseLabels)1 else 0){prefs.uppercaseLabels=it==1;render()}}
        row(g,"高度",if(keyboardHeight()==212)"标准" else "自定义"){startActivity(Intent(this,ReferenceHeightActivity::class.java))}
        row(g,"布局",if(prefs.layout=="t9")"九宫格" else "全键盘"){choose("键盘布局",listOf("全键盘","九宫格"),if(prefs.layout=="t9")1 else 0){prefs.layout=if(it==1)"t9" else "qwerty";render()}}
        space();val theme=group();val mode=selectedUiMode()
        row(theme,"键盘主题",if(ThemeManager.prefs.selectedTheme.getValue()=="rerime-reference.trime")"默认主题" else "原有主题"){selectKeyboardTheme()}
        row(theme,"主题",when(mode){AppPrefs.Advanced.UiMode.LIGHT->"浅色";AppPrefs.Advanced.UiMode.DARK->"深色";else->"跟随系统"}){
            choose("主题",listOf("跟随系统","浅色","深色"),when(mode){AppPrefs.Advanced.UiMode.LIGHT->1;AppPrefs.Advanced.UiMode.DARK->2;else->0}){ index->
                setUiMode(listOf(AppPrefs.Advanced.UiMode.AUTO,AppPrefs.Advanced.UiMode.LIGHT,AppPrefs.Advanced.UiMode.DARK)[index])
                render()
            }
        }
        row(theme,"配色","候选高亮与按键"){
            val schemes=ThemeManager.activeTheme.presetColorSchemes.toList()
            if(schemes.isNotEmpty())choose("配色",schemes.map{it.second["name"]?:it.first},0){ColorManager.setColorScheme(schemes[it].first);render()}
        }
        space();val icon=group();row(icon,"应用图标",iconName()){
            choose("应用图标",listOf("默认","蓝色","绿色"),listOf("默认","蓝色","绿色").indexOf(iconName()).coerceAtLeast(0)){if(!SmartIconSwitcher.select(this,listOf("default","blue","green")[it]))Toast.makeText(this,"图标切换失败",Toast.LENGTH_SHORT).show();render()}
        }
        space(7);builder.note("预览随显示设置即时更新。应用图标由桌面刷新。")
    }
    private fun applyKeyboardAppearance(index:Int){
        val schemes=ThemeManager.activeTheme.presetColorSchemes
        val currentId=ThemeManager.prefs.normalModeColor.getValue()
        val current=schemes[currentId]?:schemes["default"]?:schemes.values.firstOrNull()
        ThemeManager.prefs.followSystemDayNight.setValue(index==0)
        if(index!=0){
            val link=if(index==2)"dark_scheme" else "light_scheme"
            val linked=current?.get(link)?:schemes["default"]?.get(link)
            if(linked!=null && linked in schemes)ColorManager.setColorScheme(linked)
        }
        val night=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        ColorManager.onSystemNightModeChange(if(index==0)night else index==2)
    }
    private fun selectKeyboardTheme(){
        if(readingThemes)return
        readingThemes=true
        Toast.makeText(this,"正在读取主题…",Toast.LENGTH_SHORT).show()
        activityScope.launch {
        val available=try { withContext(Dispatchers.IO) { ThemeManager.getAllThemes().distinctBy{it.configId} } } finally { readingThemes=false }
        if(isFinishing || page!="display")return@launch
        val ids=(listOf("rerime-reference.trime","wechat-clean.trime")+available.map{it.configId}).distinct()
        val names=ids.map{id->when(id){"rerime-reference.trime"->"默认主题";"wechat-clean.trime"->"原有微信风格";else->available.firstOrNull{it.configId==id}?.name?:id}}
        choose("键盘主题",names,ids.indexOf(ThemeManager.prefs.selectedTheme.getValue()).coerceAtLeast(0)){index->
            val target=ids[index]
            if(available.none{it.configId==target}){Toast.makeText(this@SmartSettingsActivity,"此主题暂不可用",Toast.LENGTH_SHORT).show();return@choose}
            activityScope.launch{
                val previous=ThemeManager.prefs.selectedTheme.getValue()
                runCatching{ThemeManager.selectTheme(target)}.onSuccess{actual->
                    if(actual!=target){val restored=runCatching{ThemeManager.selectTheme(previous)}.getOrNull()==previous;Toast.makeText(this@SmartSettingsActivity,if(restored)"主题暂不可用，已恢复原主题" else "主题不可用，请重新选择",Toast.LENGTH_SHORT).show()}
                    applyKeyboardAppearance(when(selectedUiMode()){AppPrefs.Advanced.UiMode.LIGHT->1;AppPrefs.Advanced.UiMode.DARK->2;else->0})
                    if(!isFinishing)render()
                }.onFailure{Toast.makeText(this@SmartSettingsActivity,"主题切换失败",Toast.LENGTH_SHORT).show()}
            }
        }
    }
    }
    private fun iconName():String=runCatching{
        when{packageManager.getComponentEnabledSetting(ComponentName(packageName,"com.osfans.trime.BlueLauncherAlias"))==PackageManager.COMPONENT_ENABLED_STATE_ENABLED->"蓝色";packageManager.getComponentEnabledSetting(ComponentName(packageName,"com.osfans.trime.GreenLauncherAlias"))==PackageManager.COMPONENT_ENABLED_STATE_ENABLED->"绿色";else->"默认"}
    }.getOrDefault("默认")
    private fun keysPage(){
        section("拨动输入")
        val direction=prefs.flickDirection
        gestureUp=direction==FlickDirection.UP
        val demoCard=group().apply{setPadding(dp(12),dp(12),dp(12),dp(12))}
        demoCard.addView(builder.segmentsView(listOf("关闭","上拨","下拨"),direction.ordinal){prefs.flickDirection=FlickDirection.entries[it];render()})
        val demonstration=FrameLayout(this).apply{background=rounded(trackColor,16);contentDescription=if(gestureUp)"向上拨动 Q 预览" else "向下拨动 Q 预览"}
        val binding=prefs.binding("q",gestureUp)
        val value=if(binding==null)"1" else KeyBindingLabels.label(binding)
        val demoKey=text("Q",27f).apply{gravity=Gravity.CENTER}
        val cap=FrameLayout(this).apply{background=rounded(selectedColor,10);addView(demoKey,FrameLayout.LayoutParams(-1,-1))}
        cap.addView(text(KeyBindingLabels.corner(binding).ifEmpty{if(binding=="text:")"" else "1"},11f,muted).apply{gravity=Gravity.TOP or Gravity.END;setPadding(0,dp(3),dp(4),0)},FrameLayout.LayoutParams(-1,-1))
        demonstration.addView(cap,FrameLayout.LayoutParams(dp(54),dp(62),Gravity.CENTER))
        val demoResult=text(if(direction==FlickDirection.OFF)"已关闭" else "",15f,0xff169bdd.toInt()).apply{gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        demonstration.addView(demoResult,FrameLayout.LayoutParams(-1,dp(30),if(gestureUp)Gravity.TOP else Gravity.BOTTOM))
        var startY=0f
        demonstration.setOnTouchListener{_,event->
            // Only a gesture starting near Q belongs to the demo; the surrounding
            // card remains a scroll surface, especially on a short landscape screen.
            if(event.actionMasked==MotionEvent.ACTION_DOWN &&
                (kotlin.math.abs(event.x-demonstration.width/2f)>dp(48) || kotlin.math.abs(event.y-demonstration.height/2f)>dp(48))) return@setOnTouchListener false
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{startY=event.y;demonstration.parent?.requestDisallowInterceptTouchEvent(true);true}
                MotionEvent.ACTION_MOVE->{val moved=if(gestureUp)startY-event.y else event.y-startY;val selected=direction!=FlickDirection.OFF&&moved>dp(16);demoKey.text=if(selected)KeyBindingLabels.corner(binding).ifEmpty{if(binding=="text:")"" else "1"} else "Q";demoResult.text=if(selected)value else "";cap.translationY=(event.y-startY).coerceIn(-dp(12).toFloat(),dp(12).toFloat());true}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{demoKey.text="Q";demoResult.text=if(direction==FlickDirection.OFF)"已关闭" else "";cap.translationY=0f;demonstration.parent?.requestDisallowInterceptTouchEvent(false);true}
                else->false
            }
        }
        demoCard.addView(demonstration,LinearLayout.LayoutParams(-1,dp(128)).apply{topMargin=dp(12)})
        body.addView(text("快拨输入 · 按住再拨锁定  ⓘ",13f,muted).apply{setPadding(dp(12),dp(10),dp(12),0);contentDescription="拨动手势说明";setOnClickListener{android.app.AlertDialog.Builder(this@SmartSettingsActivity).setTitle("拨动与长按").setMessage("快拨：输入一次角标。\n按住 350 毫秒再拨：输入并锁定角标。\n原地按住 800 毫秒：移动光标。\n\n上拨和下拨的内容分别保存。").setPositiveButton("完成",null).show()}})
        if(direction==FlickDirection.OFF){bindingPreview=null;return}
        if(GlidePrefs(this).enabled){
            space(8)
            val conflict=group()
            row(conflict,"滑行输入已开启","关闭并使用拨动"){GlidePrefs(this).enabled=false;render()}
        }
        space(12);section(if(gestureUp)"↑ 上拨内容" else "↓ 下拨内容")
        val card=group().apply{setPadding(dp(8),dp(10),dp(8),dp(8))}
        card.addView(builder.segmentsView(listOf("全键盘","九宫格"),if(keyPreviewLayout=="t9")1 else 0){keyPreviewLayout=if(it==1)"t9" else "qwerty";render()})
        val keyboard=SmartKeyboardPreview(this).apply{
            dark=this@SmartSettingsActivity.dark;gestureUp=this@SmartSettingsActivity.gestureUp
            layoutOverride=keyPreviewLayout;showCandidates=false;keyboardBodyHeight=212
            contentDescription="按键绑定预览"
            onKeyAnchor={key,_->chooseBinding(key)}
        }
        bindingPreview=keyboard
        card.addView(keyboard,LinearLayout.LayoutParams(if(resources.configuration.orientation==Configuration.ORIENTATION_LANDSCAPE)dp(384) else -1,-2).apply{topMargin=dp(12);gravity=Gravity.CENTER_HORIZONTAL})
        card.addView(text("恢复默认内容",16f,0xff168fdf.toInt()).apply{
            setPadding(dp(10),0,0,0);setOnClickListener{
                android.app.AlertDialog.Builder(this@SmartSettingsActivity)
                    .setTitle("恢复${if(gestureUp)"上拨" else "下拨"}默认内容？")
                    .setMessage("清除这个方向的自定义绑定，另一方向保留。")
                    .setNegativeButton("取消",null).setPositiveButton("恢复"){_,_->prefs.resetBindings(gestureUp);render()}.show()
            }
        },LinearLayout.LayoutParams(-1,dp(48)))
        space(8);body.addView(text("点按预览中的按键，设置符号、短语或编辑指令。",13f,muted).apply{setPadding(dp(10),dp(6),dp(10),dp(6))})
        space();val g=group();row(g,"数字与符号按键","编辑"){choose("选择按键",("1234567890,.!?;:'\"()[]{}-_/@".map{it.toString()}),0){index->chooseBinding("1234567890,.!?;:'\"()[]{}-_/@"[index].toString())}}
    }
    private fun chooseBinding(key:String){
        val anchor=bindingPreview?:return
        val bounds=anchor.keyBounds(key)?:RectF(anchor.width/2f,0f,anchor.width/2f+1,dp(40).toFloat())
        KeyBindingPopup(this,dark,prefs,key,gestureUp,
            editPhrase={editBinding(key)},changed={anchor.invalidate()},dismissed={render()}).show(anchor,bounds)
    }
    private fun editBinding(key:String){
        val original=prefs.binding(key,gestureUp)
        val dialog=Dialog(this).apply{requestWindowFeature(Window.FEATURE_NO_TITLE)}
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(12));background=rounded(if(dark)0xff38383b.toInt() else 0xfff2f2f6.toInt(),28)}
        panel.addView(text("${KeyBindingLabels.keyName(key)} ${if(gestureUp)"↑" else "↓"}",20f).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(42)))
        val input=EditText(this).apply{setSingleLine(false);maxLines=3;filters=arrayOf(android.text.InputFilter.LengthFilter(251));hint="自定义";setHintTextColor(muted);setTextColor(ink);textSize=20f;setPadding(dp(14),dp(7),dp(14),dp(7));background=rounded(selectedColor,24);setText(original?.takeIf{it.startsWith("text:")}?.removePrefix("text:")?:"");contentDescription="手势自定义短语"}
        panel.addView(input,LinearLayout.LayoutParams(-1,dp(50)).apply{topMargin=dp(10);bottomMargin=dp(12)})
        val footer=LinearLayout(this)
        footer.addView(text("取消",19f).apply{gravity=Gravity.CENTER;background=rounded(selectedColor,25);setOnClickListener{dialog.dismiss()}},LinearLayout.LayoutParams(0,dp(44),1f).apply{rightMargin=dp(4)})
        val done=text("完成",19f).apply{gravity=Gravity.CENTER;background=rounded(selectedColor,25);setOnClickListener{val value=input.text.toString();if(value.isNotEmpty()){prefs.setBinding(key,gestureUp,"text:$value");dialog.dismiss();render()}}}
        fun updateDone(){done.isEnabled=input.text.isNotEmpty();done.setTextColor(if(done.isEnabled)ink else muted);done.alpha=if(done.isEnabled)1f else 0.55f}
        input.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){updateDone()}
            override fun afterTextChanged(s:android.text.Editable?){}
        });updateDone()
        footer.addView(done,LinearLayout.LayoutParams(0,dp(44),1f).apply{leftMargin=dp(4)})
        panel.addView(footer);dialog.setContentView(panel);dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.show();dialog.window?.setLayout((resources.displayMetrics.widthPixels-dp(64)).coerceAtLeast(dp(260)),-2);input.requestFocus()
        input.post{if(dialog.isShowing)(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}
    }
    private var wheelSettingsScroll=0
    private fun wheelPage(){
        // The reference keeps its live preview and segmented control visible
        // while only the lower settings area scrolls.
        (root.getChildAt(root.childCount-1) as? android.view.ViewGroup)?.let{old->
            root.removeView(old)
            // Keep builder.body attached so group()/row()/toggle() in wheelFunctions and
            // wheelAppearance still append to the visible settings area. Creating a fresh
            // LinearLayout here made all wheel settings render into a detached view.
            old.removeView(body)
        }
        val frame=FrameLayout(this)
        val keyboard=SmartKeyboardPreview(this).apply{dark=this@SmartSettingsActivity.dark;dimmed=true;showBottomBar=false;isClickable=false}
        frame.addView(keyboard,FrameLayout.LayoutParams(-1,-1))
        val wheel=RadialMenuView(this).apply{previewDark=this@SmartSettingsActivity.dark}
        frame.addView(wheel,FrameLayout.LayoutParams(-1,-1))
        val previewHeight=(resources.displayMetrics.widthPixels*310f/486f).toInt().coerceIn(dp(150),dp(270))
        root.addView(frame,LinearLayout.LayoutParams(-1,previewHeight))
        wheel.setPreviewMode(true){action->Toast.makeText(this,SmartInputPrefs.LABELS[action]?:RadialMenuGeometry.TIME_LABELS.getOrNull(RadialMenuGeometry.TIME_ACTIONS.indexOf(action))?:action,Toast.LENGTH_SHORT).show()}
        wheel.configurePreview(prefs.wheelSlots,prefs.wheelStyle,prefs.originGlow,prefs.followFinger)
        wheel.configureAppearance(prefs.wheelIconVisibility,prefs.wheelFocusStyle,prefs.wheelCursorPlacement,prefs.wheelMotionStyle)
        root.addView(builder.segmentsView(listOf("功能","外观"),wheelTab){wheelTab=it;wheelSettingsScroll=0;render()},LinearLayout.LayoutParams(-1,dp(34)).apply{setMargins(dp(20),dp(10),dp(20),dp(10))})
        val restoreScroll=wheelSettingsScroll
        val scroll=ScrollView(this).apply{isFillViewport=false;clipToPadding=false;isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false;setPadding(dp(20),0,dp(20),dp(20))}
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        if(wheelTab==0){installWheelDrag(frame,wheel);wheelFunctions()}else wheelAppearance()
        scroll.setOnScrollChangeListener{_,_,y,_,_->wheelSettingsScroll=y}
        scroll.post{scroll.scrollTo(0,restoreScroll)}
    }
    @Suppress("DEPRECATION", "ClickableViewAccessibility")
    private fun installWheelDrag(frame:FrameLayout,wheel:RadialMenuView){
        var downX=0f;var downY=0f;var candidate:String?=null;var iconTouch=false
        var dragStarted=false
        var original=prefs.wheelSlots
        var previewOrder=original
        val hold=Runnable{
            candidate?.let{id->
                val shadow=object:View.DragShadowBuilder(wheel){
                    override fun onProvideShadowMetrics(size:Point,touch:Point){size.set(dp(56),dp(56));touch.set(dp(28),dp(28))}
                    override fun onDrawShadow(canvas:Canvas){
                        val p=Paint(Paint.ANTI_ALIAS_FLAG);p.color=trackColor;canvas.drawCircle(dp(28).toFloat(),dp(28).toFloat(),dp(25).toFloat(),p)
                        wheel.drawIcon(canvas,id,dp(28).toFloat(),dp(28).toFloat(),dp(12).toFloat(),ink)
                    }
                }
                val data=ClipData.newPlainText("wheel",id)
                dragStarted=if(Build.VERSION.SDK_INT>=24)wheel.startDragAndDrop(data,shadow,id,0)else wheel.startDrag(data,shadow,id,0)
                if(dragStarted){
                    wheel.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    wheel.editingAction=id
                }
            }
        }
        wheel.setOnTouchListener{_,event->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{
                    downX=event.x;downY=event.y;dragStarted=false
                    candidate=wheel.previewActionAt(event.x,event.y)
                    iconTouch=candidate!=null
                    if(iconTouch){wheel.parent.requestDisallowInterceptTouchEvent(true);wheel.postDelayed(hold,350)}
                }
                // Allow the small drift of a held finger. Once lifted into a drag,
                // system drag events, rather than touch MOVE, own the gesture.
                MotionEvent.ACTION_MOVE->if(!dragStarted&&hypot(event.x-downX,event.y-downY)>dp(18)){candidate=null;wheel.removeCallbacks(hold)}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->{
                    candidate=null;wheel.removeCallbacks(hold)
                    if(!dragStarted)wheel.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            iconTouch
        }
        // Register on the actual visible preview, not only its parent. All hit
        // testing and drawing now use the renderer's own origin and icon centers.
        wheel.setOnDragListener{_,event->
            val id=event.localState as? String
            if(id !in SmartInputPrefs.ACTIONS)return@setOnDragListener false
            when(event.action){
                DragEvent.ACTION_DRAG_STARTED->{original=prefs.wheelSlots;previewOrder=original;true}
                DragEvent.ACTION_DRAG_ENTERED->{wheel.editingAction=id;true}
                DragEvent.ACTION_DRAG_LOCATION->{
                    val fraction=wheel.previewDropFraction(event.x,event.y)
                    if(fraction!=null&&id!=null){
                        val next=WheelSlotOrder.drop(original,id,fraction)
                        if(next!=previewOrder){
                            previewOrder=next
                            wheel.configurePreview(next,prefs.wheelStyle,prefs.originGlow,prefs.followFinger)
                            wheel.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                    true
                }
                DragEvent.ACTION_DROP->{
                    val fraction=wheel.previewDropFraction(event.x,event.y)
                    if(id!=null&&fraction!=null){
                        val order=WheelSlotOrder.drop(original,id,fraction)
                        if(order!=original){prefs.wheelSlots=order;frame.post{render()}}
                        true
                    }else false
                }
                DragEvent.ACTION_DRAG_EXITED->{
                    previewOrder=original
                    wheel.configurePreview(original,prefs.wheelStyle,prefs.originGlow,prefs.followFinger)
                    true
                }
                DragEvent.ACTION_DRAG_ENDED->{
                    candidate=null;iconTouch=false;dragStarted=false;wheel.editingAction=null
                    wheel.removeCallbacks(hold);wheel.parent?.requestDisallowInterceptTouchEvent(false)
                    wheel.configurePreview(prefs.wheelSlots,prefs.wheelStyle,prefs.originGlow,prefs.followFinger)
                    true
                }
                else->true
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun wheelFunctions(){
        val available=SmartInputPrefs.ACTIONS.filter{it !in prefs.wheelSlots}
        val panel=group()
        val entries=available.map{it as String?}+listOf(null)
        entries.chunked(4).forEach{part->
            val r=LinearLayout(this).apply{setPadding(dp(8),dp(8),dp(8),dp(8))}
            part.forEach{id->
                val tile=if(id==null)wheelRemovalTarget()else wheelTile(id,"+"){
                    if(prefs.wheelSlots.size<6){prefs.wheelSlots=prefs.wheelSlots+id;render()}
                    else Toast.makeText(this,"轮盘最多保留 6 项，请先移出一项",Toast.LENGTH_SHORT).show()
                }.apply{
                    setOnLongClickListener{source->
                        val data=ClipData.newPlainText("wheel",id)
                        if(Build.VERSION.SDK_INT>=24)source.startDragAndDrop(data,View.DragShadowBuilder(source),id,0)else source.startDrag(data,View.DragShadowBuilder(source),id,0)
                    }
                }
                r.addView(tile,LinearLayout.LayoutParams(0,dp(82),1f))
            }
            repeat(4-part.size){r.addView(View(this),LinearLayout.LayoutParams(0,dp(82),1f))}
            panel.addView(r)
        }
    }
    private fun wheelRemovalTarget():View=object:View(this){
        private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas){
            val cx=width/2f;val cy=dp(28).toFloat();val radius=dp(20).toFloat()
            pen.color=0xffd34f70.toInt();pen.style=Paint.Style.STROKE;pen.strokeWidth=dp(1).toFloat()
            pen.pathEffect=android.graphics.DashPathEffect(floatArrayOf(dp(3).toFloat(),dp(3).toFloat()),0f)
            canvas.drawCircle(cx,cy,radius,pen);pen.pathEffect=null;pen.strokeWidth=dp(2).toFloat()
            canvas.drawLine(cx-dp(6),cy,cx+dp(6),cy,pen)
        }
    }.apply{
        contentDescription="拖到此处移出轮盘"
        setOnDragListener{view,event->
            val id=event.localState as? String
            if(id !in prefs.wheelSlots)return@setOnDragListener false
            when(event.action){
                DragEvent.ACTION_DRAG_ENTERED->{view.alpha=.5f;true}
                DragEvent.ACTION_DROP->{
                    val next=WheelSlotOrder.remove(prefs.wheelSlots,id!!)
                    if(next!=prefs.wheelSlots){prefs.wheelSlots=next;view.post{render()}}
                    else Toast.makeText(this@SmartSettingsActivity,"轮盘至少保留 1 项",Toast.LENGTH_SHORT).show()
                    true
                }
                DragEvent.ACTION_DRAG_EXITED,DragEvent.ACTION_DRAG_ENDED->{view.alpha=1f;true}
                else->true
            }
        }
    }
    private fun glyph(id:String)=when(id){"time"->"◷";"traditional"->"繁";"symbols"->"#";"clipboard"->"▤";"settings"->"☷";"calculator"->"▦";"quotes"->"▱";else->id}
    private fun wheelTile(id:String,badge:String,action:()->Unit):View=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setOnClickListener{action()};contentDescription="${SmartInputPrefs.LABELS[id]} 添加"
        val renderer=RadialMenuView(this@SmartSettingsActivity)
        addView(object:View(this@SmartSettingsActivity){
            private val pen=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas:Canvas){
                val cx=width/2f;val cy=height/2f
                pen.color=trackColor;canvas.drawCircle(cx,cy,dp(19).toFloat(),pen)
                renderer.drawIcon(canvas,id,cx,cy,dp(9).toFloat(),ink)
                pen.color=0xff30d958.toInt();canvas.drawCircle(cx+dp(15),cy-dp(15),dp(7).toFloat(),pen)
                pen.color=Color.WHITE;pen.strokeWidth=dp(1).toFloat()
                canvas.drawLine(cx+dp(12),cy-dp(15),cx+dp(18),cy-dp(15),pen)
                canvas.drawLine(cx+dp(15),cy-dp(18),cx+dp(15),cy-dp(12),pen)
            }
        },LinearLayout.LayoutParams(-1,dp(49)))
        val title=when(id){"traditional"->"简繁切换";"calculator"->"计算器";else->SmartInputPrefs.LABELS[id]?:id}
        addView(text(title,14f,muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(26)))
    }
    private fun wheelAppearance(){
        val styles=listOf("beads" to "珠点","arc" to "弧段","band" to "光带","none" to "无记号","rainbow" to "流光","dewdrop" to "露珠")
        val styleGroup=group()
        styles.chunked(3).forEach{items->
            val r=LinearLayout(this).apply{setPadding(dp(5),dp(4),dp(5),dp(4))}
            items.forEach{(id,name)->r.addView(wheelChoice("wheel_style",id,name,prefs.wheelStyle==id){prefs.wheelStyle=id;render()},LinearLayout.LayoutParams(0,dp(83),1f))}
            styleGroup.addView(r)
        }
        appearanceChoice("wheel_icon_visibility",listOf("selected" to "只显选中","all" to "全部显示","hint" to "打开时提示","nearby" to "只显邻近"),"all")
        appearanceChoice("wheel_focus_style",listOf("lens" to "透镜","origin" to "原地点亮","spotlight" to "聚光"),"lens")
        appearanceChoice("wheel_cursor_placement",listOf("finger" to "跟着手指","above" to "停在上方"),"finger")
        appearanceChoice("wheel_origin_placement",listOf("finger" to "手指下","space_center" to "空格正中"),"finger")
        appearanceChoice("wheel_motion_style",listOf("snappy" to "利落","soft" to "柔和","spring" to "弹性"),"snappy")
        space(14);val g=group();toggle(g,"原点点亮",prefs.originGlow){prefs.originGlow=it;render()};toggle(g,"光标跟随",prefs.followFinger){prefs.followFinger=it;render()}
    }
    private fun wheelChoice(key:String,id:String,name:String,selected:Boolean,action:()->Unit):View=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;background=if(selected)rounded(trackColor,18)else null
        isSelected=selected;contentDescription=name;setOnClickListener{action()}
        addView(WheelAppearancePreview(this@SmartSettingsActivity,prefs,dark,key,id),LinearLayout.LayoutParams(-1,dp(47)))
        addView(text(name,if(key=="wheel_icon_visibility")12f else 15f,if(selected)ink else muted).apply{gravity=Gravity.CENTER;isSingleLine=true;typeface=if(selected)Typeface.DEFAULT_BOLD else Typeface.DEFAULT},LinearLayout.LayoutParams(-1,dp(29)))
    }
    private fun appearanceChoice(key:String,items:List<Pair<String,String>>,fallback:String){
        space(14);val g=group();val r=LinearLayout(this).apply{setPadding(dp(5),dp(4),dp(5),dp(4))};val current=extra.getString(key,fallback)
        items.forEach{(id,name)->r.addView(wheelChoice(key,id,name,current==id){extra.edit().putString(key,id).apply();render()},LinearLayout.LayoutParams(0,dp(83),1f))};g.addView(r)
    }
    private fun choose(title:String,items:List<String>,selected:Int,action:(Int)->Unit){
        val dialog=Dialog(this).apply{requestWindowFeature(Window.FEATURE_NO_TITLE)}
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(10));background=rounded(groupColor,26)}
        panel.addView(text(title,18f).apply{gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD},LinearLayout.LayoutParams(-1,dp(44)))
        val scroll=ScrollView(this).apply{isVerticalScrollBarEnabled=false};val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};items.forEachIndexed{i,value->list.addView(text(value+if(i==selected)"  ✓" else "",18f).apply{gravity=Gravity.CENTER;setOnClickListener{dialog.dismiss();action(i)}},LinearLayout.LayoutParams(-1,dp(48)))};scroll.addView(list);panel.addView(scroll,LinearLayout.LayoutParams(-1,if(items.size>8)dp(360) else -2))
        panel.addView(text("取消",18f,muted).apply{gravity=Gravity.CENTER;setOnClickListener{dialog.dismiss()}},LinearLayout.LayoutParams(-1,dp(45)));dialog.setContentView(panel);dialog.window?.setBackgroundDrawableResource(android.R.color.transparent);dialog.show();dialog.window?.setLayout(resources.displayMetrics.widthPixels-dp(48),-2)
    }
}
