// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.smart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

/** Four-row, horizontally scrolling local emoji browser. All glyphs come from Android's font. */
class ReferenceEmojiWindow(di: DI, private val emoticons: Boolean = false) : BoardWindow.NoBarBoardWindow(di) {
    private val service: TrimeInputMethodService by instance()
    private val scope: ThemeScope by instance()
    private val windows: BoardWindowManager by instance()
    private val bar: InputBarDelegate by instance()
    private val common: CommonKeyboardActionListener by instance()
    private val store = context.getSharedPreferences("reference_emoji", Context.MODE_PRIVATE)
    private val catalog by lazy { context.assets.open("emoji-reference.tsv").reader(Charsets.UTF_8).use(ReferenceEmojiCatalog::read) }
    private val recent get() = store.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }.take(64)
    private var category = "faces"
    private var items = emptyList<String>()
    private val positions = mutableMapOf<String, android.os.Parcelable?>()
    private val layout = GridLayoutManager(context,4,RecyclerView.HORIZONTAL,false)
    private var attached = false
    private var oldHeight = 0
    private var oldBar = View.VISIBLE
    private val ink get() = scope.colors.keyTextColor
    private val board get() = scope.colors.keyboardBackColor
    private fun px(v:Int) = (minOf(context.resources.displayMetrics.widthPixels,context.resources.displayMetrics.heightPixels)*v/486f).toInt()
    private fun round(color:Int, radius:Int=18)=GradientDrawable().apply{setColor(color);cornerRadius=px(radius).toFloat()}
    private class Holder(val label:TextView):RecyclerView.ViewHolder(label)
    private val adapter=object:RecyclerView.Adapter<Holder>(){
        override fun getItemCount()=items.size
        override fun onCreateViewHolder(parent:ViewGroup,type:Int)=Holder(TextView(context).apply{gravity=Gravity.CENTER;includeFontPadding=false})
        override fun onBindViewHolder(holder:Holder,index:Int){
            val value=items[index]
            holder.label.apply {
                text=value;contentDescription=value;textSize=if(emoticons)16f else 27f;setTextColor(ink)
                layoutParams=RecyclerView.LayoutParams(if(emoticons)px(145) else px(62),-1)
                isClickable=true;isFocusable=true
                setOnClickListener { commit(value) }
                setOnLongClickListener {
                    val variants=if(emoticons) emptyList() else ReferenceEmojiCatalog.variants(value)
                    if(variants.isEmpty())false else {parent?.requestDisallowInterceptTouchEvent(true);SymbolVariantPopup.show(this,variants,::commit);true}
                }
                setOnTouchListener { v,e -> when(e.actionMasked){
                    MotionEvent.ACTION_MOVE->SymbolVariantPopup.move(v,e.x,e.y)
                    MotionEvent.ACTION_UP->SymbolVariantPopup.release(v).also{v.parent?.requestDisallowInterceptTouchEvent(false)}
                    MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN->SymbolVariantPopup.cancel(v).also{v.parent?.requestDisallowInterceptTouchEvent(false)}
                    else->false
                } }
            }
        }
        override fun onViewRecycled(holder:Holder){SymbolVariantPopup.cancel(holder.label);super.onViewRecycled(holder)}
    }
    private val grid=RecyclerView(context).apply {
        layoutManager=layout;adapter=this@ReferenceEmojiWindow.adapter;itemAnimator=null
        isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER
        setPadding(px(12),px(16),px(12),0);clipToPadding=true
        contentDescription=if(emoticons)"颜文字内容，四行横向滚动" else "表情内容，四行横向滚动"
    }
    private val nav=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(px(12),0,px(12),0)}
    private val footer=LinearLayout(context).apply{
            gravity=Gravity.CENTER_VERTICAL;setPadding(px(28),0,px(28),0)
            addView(icon("globe","切换中英文") {common.listener.onSmartAction("toggle_language")},LinearLayout.LayoutParams(px(48),-1))
            addView(View(context),LinearLayout.LayoutParams(0,1,1f))
            addView(icon("microphone","语音输入"){common.listener.onAction(scope.theme.resolveAction("VOICE_ASSIST"))},LinearLayout.LayoutParams(px(48),-1))
    }
    private val content=LinearLayout(context).apply{
        orientation=LinearLayout.VERTICAL
        addView(grid,LinearLayout.LayoutParams(-1,0,1f))
        addView(nav,LinearLayout.LayoutParams(-1,px(44)))
        addView(footer,LinearLayout.LayoutParams(-1,context.dp(36)))
    }
    private val root=FrameLayout(context).apply{addView(content,FrameLayout.LayoutParams(-1,-1))}
    private fun select(id:String){
        grid.stopScroll();positions[category]=layout.onSaveInstanceState();category=id
        items=if(emoticons) ReferenceEmojiCatalog.emoticons else if(id=="recent")recent else catalog[id].orEmpty()
        adapter.notifyDataSetChanged()
        positions[id]?.let{layout.onRestoreInstanceState(it)}?:layout.scrollToPositionWithOffset(0,0)
        renderNavigation()
    }
    private fun renderNavigation(){
        nav.removeAllViews()
        nav.addView(icon("keyboard","返回字母键盘"){windows.attachWindow(KeyboardWindow)},LinearLayout.LayoutParams(0,-1,1f))
        if(emoticons)nav.addView(TextView(context).apply{text="颜文字";textSize=16f;setTextColor(ink);gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-1,6f))
        else ReferenceEmojiCatalog.groups.forEach { group ->
            nav.addView(icon(group.id,"表情分类 ${group.label}"){select(group.id)}.apply{
                isSelected=group.id==category
                background=if(isSelected)round(ColorUtils.blendARGB(board,ink,.12f),20) else null
            },LinearLayout.LayoutParams(0,px(38),1f))
        }
        nav.addView(icon("delete","表情退格"){common.listener.onAction(scope.theme.resolveAction("BackSpace"))},LinearLayout.LayoutParams(0,-1,1.3f))
    }
    private fun commit(value:String){
        val editor=service.currentInputEditorInfo?:return
        service.postRimeJob {
            if(!attached || service.currentInputEditorInfo!==editor)return@postRimeJob
            if(statusCached.isComposing)commitComposition()
            if(!attached || service.currentInputEditorInfo!==editor)return@postRimeJob
            if(service.currentInputConnection?.commitText(value,1)==true && !emoticons && !OfflinePrivacyPolicy.isSensitive(editor.inputType,editor.imeOptions)) {
                store.edit().putString("recent",(listOf(value)+recent).distinct().take(64).joinToString("\n")).apply()
            }
        }
    }
    private fun icon(kind:String,label:String,click:()->Unit)=object:View(context){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        init{contentDescription=label;isClickable=true;isFocusable=true;setOnClickListener{click()}}
        override fun onDraw(c:Canvas){
            val x=width/2f;val y=height/2f;val r=px(if(kind=="globe"||kind=="microphone")16 else 10).toFloat();p.color=ink;p.strokeWidth=px(1).coerceAtLeast(1).toFloat();p.style=Paint.Style.STROKE
            when(kind){
                "keyboard"->{c.drawRoundRect(RectF(x-r,y-r*.65f,x+r,y+r*.65f),r*.18f,r*.18f,p);for(a in 0..1)for(b in 0..4)c.drawPoint(x-r*.65f+b*r*.33f,y-r*.25f+a*r*.4f,p);c.drawLine(x-r*.45f,y+r*.35f,x+r*.45f,y+r*.35f,p)}
                "delete"->{val path=Path().apply{moveTo(x-r,y);lineTo(x-r*.3f,y-r*.7f);lineTo(x+r,y-r*.7f);lineTo(x+r,y+r*.7f);lineTo(x-r*.3f,y+r*.7f);close()};c.drawPath(path,p);c.drawLine(x-r*.1f,y-r*.3f,x+r*.5f,y+r*.3f,p);c.drawLine(x-r*.1f,y+r*.3f,x+r*.5f,y-r*.3f,p)}
                "globe"->{c.drawCircle(x,y,r,p);c.drawOval(RectF(x-r*.45f,y-r,x+r*.45f,y+r),p);c.drawLine(x-r,y,x+r,y,p);for(side in listOf(-1,1))c.drawArc(RectF(x-r,y+side*r*.4f-r*.2f,x+r,y+side*r*.4f+r*.2f),0f,180f,false,p)}
                "microphone"->{c.drawRoundRect(RectF(x-r*.3f,y-r,x+r*.3f,y+r*.2f),r*.3f,r*.3f,p);c.drawArc(RectF(x-r*.6f,y-r*.4f,x+r*.6f,y+r*.6f),0f,180f,false,p);c.drawLine(x,y+r*.6f,x,y+r,p);c.drawLine(x-r*.35f,y+r,x+r*.35f,y+r,p)}
                "recent"->{c.drawCircle(x,y,r*.85f,p);c.drawLine(x,y-r*.5f,x,y,p);c.drawLine(x,y,x-r*.4f,y,p)}
                "faces"->{c.drawCircle(x,y,r*.85f,p);c.drawPoint(x-r*.3f,y-r*.2f,p);c.drawPoint(x+r*.3f,y-r*.2f,p);c.drawArc(RectF(x-r*.4f,y-r*.2f,x+r*.4f,y+r*.5f),0f,180f,false,p)}
                "people"->{for(i in 0..3)c.drawLine(x-r*.6f+i*r*.35f,y+r*.2f,x-r*.6f+i*r*.35f,y-r*(if(i==1)1f else .7f),p);c.drawArc(RectF(x-r*.7f,y-r*.1f,x+r*.65f,y+r),0f,180f,false,p)}
                "nature"->{c.drawOval(RectF(x-r*.5f,y,x+r*.5f,y+r*.7f),p);for(i in 0..3)c.drawOval(RectF(x-r+i*r*.5f,y-r*.7f,x-r*.65f+i*r*.5f,y-r*.15f),p)}
                "food"->{c.drawLine(x-r*.5f,y-r,x-r*.5f,y+r,p);for(i in -1..1)c.drawLine(x-r*.5f+i*r*.22f,y-r,x-r*.5f+i*r*.22f,y-r*.25f,p);c.drawLine(x+r*.5f,y-r,x+r*.5f,y+r,p);c.drawLine(x+r*.2f,y-r,x+r*.2f,y+r*.1f,p);c.drawLine(x+r*.2f,y+r*.1f,x+r*.5f,y+r*.1f,p)}
                "travel"->{c.drawRoundRect(RectF(x-r,y-r*.4f,x+r,y+r*.4f),r*.25f,r*.25f,p);c.drawLine(x-r*.65f,y-r*.4f,x-r*.4f,y-r*.85f,p);c.drawLine(x-r*.4f,y-r*.85f,x+r*.4f,y-r*.85f,p);c.drawLine(x+r*.4f,y-r*.85f,x+r*.65f,y-r*.4f,p);c.drawCircle(x-r*.6f,y+r*.6f,r*.2f,p);c.drawCircle(x+r*.6f,y+r*.6f,r*.2f,p)}
                "activity"->{c.drawRect(x-r,y-r*.65f,x+r,y+r*.65f,p);c.drawLine(x,y-r*.65f,x,y+r*.65f,p);c.drawCircle(x,y,r*.3f,p)}
                "objects"->{c.drawOval(RectF(x-r*.6f,y-r,x+r*.6f,y+r*.25f),p);c.drawLine(x-r*.3f,y+r*.25f,x-r*.2f,y+r*.75f,p);c.drawLine(x+r*.3f,y+r*.25f,x+r*.2f,y+r*.75f,p);c.drawLine(x-r*.2f,y+r*.75f,x+r*.2f,y+r*.75f,p)}
                "flags"->{c.drawLine(x-r*.6f,y-r,x-r*.6f,y+r,p);c.drawRect(x-r*.6f,y-r,x+r*.8f,y+r*.05f,p)}
                else->{p.style=Paint.Style.FILL;p.textSize=r*2.3f;p.textAlign=Paint.Align.CENTER;c.drawText("#",x,y-(p.ascent()+p.descent())/2,p)}
            }
        }
    }
    override fun onCreateView():View=root.also{refreshColors();select(category)}
    override fun onAttached(){if(attached)return;attached=true;oldBar=bar.view.visibility;oldHeight=windows.view.layoutParams.height
        // The emoji panel replaces the board and candidate bar inside the same IME bounds.
        // A video-pixel minimum made a tap resize the host editor and move the bottom controls.
        val extra=if(oldBar!=View.GONE)bar.view.height.takeIf{it>0}?:context.dp(bar.themedHeight) else 0
        val boardHeight=oldHeight.takeIf{it>0}?:windows.view.height
        footer.updateLayoutParams{height=KeyboardWindow.currentKeyboard.keys.lastOrNull()?.height?:context.dp(36)}
        bar.view.visibility=View.GONE
        windows.view.updateLayoutParams{height=boardHeight+extra};refreshColors()
    }
    override fun onDetached(){grid.stopScroll();for(i in 0 until grid.childCount)SymbolVariantPopup.cancel(grid.getChildAt(i));if(attached){attached=false;bar.view.visibility=oldBar;windows.view.updateLayoutParams{height=oldHeight}}}
    override fun refreshColors(){
        root.setBackgroundColor(if(ColorUtils.calculateLuminance(board)>.4)0xfff2f2f6.toInt() else Color.BLACK)
        val r=px(32).toFloat();content.background=GradientDrawable().apply{setColor(board);cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)};content.clipToOutline=true
        adapter.notifyDataSetChanged();renderNavigation()
    }
}
