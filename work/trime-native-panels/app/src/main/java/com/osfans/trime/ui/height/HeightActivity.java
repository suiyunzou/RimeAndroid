package com.osfans.trime.ui.height;

import android.app.Activity;
import android.os.Bundle;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.Build;
import android.util.AtomicFile;
import com.osfans.trime.core.data.DataManager;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;

public final class HeightActivity extends Activity {
    private int portrait=200, landscape=176;
    private Editor view;
    private boolean wide;
    private AtomicFile profile;
    private byte[] readBytes() throws IOException {
        try(InputStream in=profile.openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            if(in==null)throw new IOException();
            byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1){if(bytes.size()+n>512*1024)throw new IOException();bytes.write(buf,0,n);}
            return bytes.toByteArray();
        }
    }
    private void readProfile() throws IOException {
        if (!profile.getBaseFile().exists() && !new File(profile.getBaseFile()+".bak").exists()) return;
        int[] heights=HeightProfileCodec.parse(readBytes());
        portrait=heights[0];landscape=heights[1];
    }
    private byte[] buildProfile() throws IOException {
        try(InputStream fields=getAssets().open("height-editor/height-fields.tsv")) {
            return HeightProfileCodec.build(portrait,landscape,wide,fields);
        }
    }
    private void writeBytes(byte[] bytes) throws IOException {
        FileOutputStream out=null;
        try {
            out=profile.startWrite();out.write(bytes);
            profile.finishWrite(out);
        } catch(Exception e) {
            if(out!=null)profile.failWrite(out);
            throw new IOException("无法写入高度配置",e);
        }
    }
    private boolean saveProfile(){
        try{
            byte[] bytes=buildProfile();
            byte[] previous=profile.getBaseFile().exists()?readBytes():null;
            try {
                writeBytes(bytes);
                if(!java.util.Arrays.equals(bytes,readBytes()))throw new IOException("高度配置回读不一致");
            } catch(Exception error) {
                if(previous!=null) {
                    try { writeBytes(previous); }
                    catch(Exception recovery) { error.addSuppressed(recovery); }
                }
                throw error;
            }
            return true;
        }catch(Exception e){Toast.makeText(this,"高度未保存，请重试",Toast.LENGTH_LONG).show();return false;}
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        profile=new AtomicFile(new File(DataManager.INSTANCE.getUserDataDir(),"clean_height_profile.yaml"));
        openEditor(state);
    }
    private void openEditor(Bundle state){
        try{ readProfile(); }catch(Exception ex){Toast.makeText(this,"无法读取键盘高度配置文件",Toast.LENGTH_LONG).show();finish();return;}
        if(state!=null){portrait=state.getInt("p",portrait);landscape=state.getInt("l",landscape);}
        wide=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        view=new Editor(); setContentView(view);
    }
    @Override public void onSaveInstanceState(Bundle b) {super.onSaveInstanceState(b);b.putInt("p",portrait);b.putInt("l",landscape);}
    private int current(){return wide?landscape:portrait;}
    private void setCurrent(int v){if(wide)landscape=v;else portrait=v;}
    private static float bound(float x,float lo,float hi){return Math.max(lo,Math.min(x,hi));}

    final class Editor extends FrameLayout {
        final float density=getResources().getDisplayMetrics().density;
        final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        final RectF frame=new RectF();
        final View upper,lower,reset,done;
        final KeyboardPreview preview;
        final int ink,tint,muted;
        int drag,pending,activePointer=-1; float downX,downY; int startDp;
        final int slop=ViewConfiguration.get(HeightActivity.this).getScaledTouchSlop();
        final int holdMs=Math.max(300,Math.min(450,ViewConfiguration.getLongPressTimeout()));
        final Runnable activate=new Runnable(){public void run(){
            if(pending==0 || activePointer<0)return;
            drag=pending;pending=0;performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }};
        Editor(){
            super(HeightActivity.this); setWillNotDraw(false); setMotionEventSplittingEnabled(false);
            preview=new KeyboardPreview(HeightActivity.this,wide);
            ink=preview.ink();tint=preview.control();muted=preview.grip();
            getWindow().setNavigationBarColor(preview.background());
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            if(Build.VERSION.SDK_INT>=26){
                View decor=getWindow().getDecorView();
                int flags=decor.getSystemUiVisibility();
                decor.setSystemUiVisibility(preview.dark?flags&~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:flags|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
            upper=target("上横线，按住上下拖动调整键盘高度",1);
            lower=target("下横线，按住上下拖动调整键盘高度",2);
            reset=target("重置当前方向的键盘高度",3);

            done=target("完成并应用键盘高度",5);
        }
        int px(float v){return Math.round(v*density);}
        float margin(){return 0;}
        float toolbar(){return px(40);}
        float ratio(){return preview.f(preview.board,"baseHeight",wide?176:200)/(wide?176f:200f);}
        int shown(){return Math.round(current()*ratio());}
        void sizeTo(int dp){setCurrent((int)bound(Math.round(dp/ratio()),wide?128:144,wide?248:320));}
        int bottomReservePx;
        void measureBottomReserve(){
            Point real=new Point();
            getWindowManager().getDefaultDisplay().getRealSize(real);
            int[] origin=new int[2];getLocationOnScreen(origin);
            int avoided=Math.max(0,real.y-(origin[1]+getHeight()));
            WindowInsets insets=getRootWindowInsets();
            if(insets!=null){
                int nav=Build.VERSION.SDK_INT>=30
                    ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : insets.getStableInsetBottom();
                // A right/left navigation rail or a freeform/window edge is not bottom padding.
                avoided=Math.min(avoided,Math.max(0,nav));
            }
            bottomReservePx=Math.max(0,px(preview.bottomPadding())-avoided);
        }
        int maximum(){return Math.max(1,Math.min(Math.round((wide?248:320)*ratio()),(int)((getHeight()-toolbar()-bottomReservePx)/density)));}
        int minimum(){return Math.min(Math.round((wide?128:144)*ratio()),maximum());}
        float heightPx(){return toolbar()+px(shown())+bottomReservePx;}
        void center(){
            if(getHeight()<=0)return;
            measureBottomReserve();
            if(shown()<minimum() || shown()>maximum())sizeTo((int)bound(shown(),minimum(),maximum()));
            frame.set(0,getHeight()-heightPx(),getWidth(),getHeight()); requestLayout();invalidate();
        }
        View target(String desc, final int id) {
            View v=new View(HeightActivity.this);
            v.setFocusable(true); v.setClickable(true); v.setContentDescription(desc);
            if(id==3 || id==5) v.setOnClickListener(x->{
                if(id==3){setCurrent(wide?176:200);center();announceForAccessibility("已恢复默认高度");}
                else if(saveProfile()){setResult(RESULT_OK);finish();}
            });
            if(id==1 || id==2) {
                v.setOnTouchListener((x,e)->touch(id,e));
                v.setAccessibilityDelegate(new View.AccessibilityDelegate(){
                    @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo n){
                        super.onInitializeAccessibilityNodeInfo(host,n);
                        n.setClassName("android.widget.SeekBar"); n.setScrollable(true);
                        n.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,minimum(),maximum(),shown()));
                        n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                        n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                    }
                    @Override public boolean performAccessibilityAction(View host,int action,Bundle args){
                        if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){
                            sizeTo((int)bound(shown()+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?4:-4),minimum(),maximum()));
                            center();host.announceForAccessibility(shown()+" dp");return true;
                        }
                        return super.performAccessibilityAction(host,action,args);
                    }
                });
            }
            addView(v,new FrameLayout.LayoutParams(1,1));return v;
        }
        void place(View c,float x,float y,float w,float h){
            int l=Math.round(x),t=Math.round(y);
            c.layout(l,t,l+Math.round(w),t+Math.round(h));
        }
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);center();}
        @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
            measureBottomReserve();
            if(frame.isEmpty())center();
            else frame.set(0,getHeight()-heightPx(),getWidth(),getHeight());
            float mid=frame.centerX(),cy=frame.centerY();
            float size=Math.min(px(wide?37:44),frame.height()*.22f), gap=px(6),bw=Math.min(px(84),frame.width()*.24f), readW=px(80);
            float gh=Math.min(px(44),frame.height()*.25f),gw=Math.min(frame.width()*.55f,px(88));
            place(upper,mid-gw/2,frame.top,gw,gh);
            place(lower,mid-gw/2,frame.bottom-gh,gw,gh);

            place(reset,mid-readW/2-gap-bw,cy-size/2,bw,size);
            place(done,mid+readW/2+gap,cy-size/2,bw,size);
        }
        void endGesture(){
            removeCallbacks(activate);drag=0;pending=0;activePointer=-1;invalidate();
        }
        @Override protected void onDetachedFromWindow(){endGesture();super.onDetachedFromWindow();}
        boolean touch(int id,MotionEvent e){
            int action=e.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                endGesture();
                activePointer=e.getPointerId(0);downX=e.getRawX();downY=e.getRawY();
                startDp=shown();
                pending=id;postDelayed(activate,holdMs);
                return true;
            }
            if(action==MotionEvent.ACTION_POINTER_DOWN || action==MotionEvent.ACTION_POINTER_UP ||
                action==MotionEvent.ACTION_CANCEL || e.getPointerCount()!=1 ||
                e.getPointerId(0)!=activePointer){endGesture();return true;}
            if(action==MotionEvent.ACTION_MOVE || action==MotionEvent.ACTION_UP){
                float dx=e.getRawX()-downX, delta=e.getRawY()-downY;
                if(pending==id && dx*dx+delta*delta>slop*slop){endGesture();return true;}
                if(drag==id){
                    // The real IME is anchored above Android navigation. Both grips resize
                    // toward the editor on an upward motion; the bottom edge never moves.
                    sizeTo((int)bound(Math.round(startDp-delta/density),minimum(),maximum()));
                    frame.set(0,getHeight()-heightPx(),getWidth(),getHeight());
                    requestLayout();invalidate();
                }
                if(action==MotionEvent.ACTION_UP)endGesture();
            }
            return true;
        }
        void fill(int col){pen.setStyle(Paint.Style.FILL);pen.setColor(col);}
        void round(Canvas c,float l,float t,float r,float b,float rad,int color){
            fill(color);c.drawRoundRect(l,t,r,b,rad,rad,pen);
        }
        void text(Canvas c,String s,float x,float cy,float sp,int col){
            fill(col);pen.setTextAlign(Paint.Align.CENTER);pen.setTextSize(px(sp));pen.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
            c.drawText(s,x,cy-(pen.ascent()+pen.descent())*.5f,pen);
        }
        float vc(View v){return (v.getTop()+v.getBottom())*.5f;}
        void button(Canvas c,View v,String label,int col){
            round(c,v.getLeft(),v.getTop(),v.getRight(),v.getBottom(),v.getHeight()/2f,col);
            text(c,label,(v.getLeft()+v.getRight())*.5f,vc(v),wide?14:16,ink);
        }
        void grip(Canvas c,View v,boolean active,boolean top){
            float cx=frame.centerX(),y=top?v.getTop()+px(8):v.getBottom()-px(8);
            round(c,cx-px(active?23:19),y-px(2),cx+px(active?23:19),y+px(2),px(3),active?0xff245b91:muted);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c); if(frame.isEmpty())return;
            float rad=px(12),mid=frame.centerX(),cy=frame.centerY();
            c.save(); Path clip=new Path();clip.addRoundRect(frame,rad,rad,Path.Direction.CW);c.clipPath(clip);
            preview.draw(c,frame,toolbar(),bottomReservePx);c.restore();
            float stroke=px(1);
            RectF edge=new RectF(frame);edge.inset(stroke/2,stroke/2);
            pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(stroke);pen.setColor(muted);c.drawRoundRect(edge,rad,rad,pen);pen.setStyle(Paint.Style.FILL);
            grip(c,upper,drag==1,true);grip(c,lower,drag==2,false);
            float ry=reset.getHeight()/2f;
            round(c,mid-px(40),cy-ry,mid+px(40),cy+ry,ry,preview.readout());
            text(c,"↕ "+shown()+" dp",mid,cy,wide?13:15,ink);
            button(c,reset,"↶  重置",tint);button(c,done,"✓  完成",tint);
        }
    }
}
