package com.osfans.trime.ui.height;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.util.HashMap;
import java.util.Locale;

/** Read the resolved theme generated from the same layout configuration as Trime. */
final class KeyboardPreview {
    final Activity activity;
    final boolean dark,ascii,traditional,caps;
    final float density,scaled;
    final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
    final Typeface font;
    final JSONObject palette,board;
    final JSONArray toolbar,rows;
    final float[] weights;
    final HashMap<String,Drawable> vectors=new HashMap<>();
    private String param(String k,String fallback){
        Object extra=activity.getIntent().getExtras()==null?null:activity.getIntent().getExtras().get(k);
        String s=extra==null?null:String.valueOf(extra);
        Uri data=activity.getIntent().getData();
        if(s==null && data!=null && data.isHierarchical())s=data.getQueryParameter(k);
        return s==null?fallback:s;
    }
    private boolean truth(String k,boolean fallback){
        String v=param(k,"");return v.isEmpty()?fallback:"true".equalsIgnoreCase(v)||"1".equals(v);
    }
    KeyboardPreview(Activity a, boolean wide){
        activity=a;density=a.getResources().getDisplayMetrics().density;scaled=a.getResources().getDisplayMetrics().scaledDensity;
        dark=(a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        ascii=truth("ascii",false);traditional=truth("traditional",false);caps=truth("caps",false);
        try(InputStream in=a.getAssets().open("height-editor/keyboard-preview.json"); ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] b=new byte[8192]; int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);
            JSONObject data=new JSONObject(bytes.toString("UTF-8"));
            font=Typeface.createFromAsset(a.getAssets(),"height-editor/"+data.getString("font"));
            palette=data.getJSONObject("palette").getJSONObject(dark?"dark":"light");toolbar=data.getJSONArray("toolbar");
            JSONObject boards=data.getJSONObject("boards");
            String base=param("board","default").replaceFirst("^\\.","");
            if(base.equals("ascii") || base.isEmpty())base="default";
            String match=base;
            if(wide && !base.startsWith("compact_")){
                String neutral=base.replaceFirst("^hand_(?:left|right)_","");
                if(boards.has("compact_"+neutral))match="compact_"+neutral;
            }else if(!wide && match.startsWith("compact_"))match=match.substring(8);
            if(!boards.has(match))match=wide?"compact_default":"default";
            if(ascii && match.endsWith("default")){
                String letter=match.substring(0,match.length()-"default".length())+"letter";
                if(boards.has(letter))match=letter;
            }
            board=boards.getJSONObject(match);rows=board.getJSONArray("rows");
            JSONArray list=board.optJSONArray("rowWeights");weights=new float[rows.length()];float sum=0;
            for(int j=0;j<weights.length;j++){weights[j]=list==null?1:(float)list.optDouble(j,1);sum+=weights[j];}
            if(sum<=0)throw new IOException("invalid row heights");
            for(int j=0;j<weights.length;j++)weights[j]/=sum;
            for(int j=0;j<toolbar.length();j++){
                JSONObject item=toolbar.getJSONObject(j);String icon=item.optString("icon");
                if(icon.matches("[a-z_]+")){
                    int id=a.getResources().getIdentifier("trime_toolbar_"+icon,"drawable",a.getPackageName());
                    if(id!=0)vectors.put(icon,a.getDrawable(id).mutate());
                }
            }
        } catch(Exception e){throw new IllegalStateException("无法加载键盘预览资源",e);}
    }
    int color(String role,int fallback){String value=palette.optString(role);if(value.isEmpty())return fallback;
        try{return Color.parseColor(value.replaceFirst("^0x","#"));}catch(IllegalArgumentException e){return fallback;}}
    int background(){return color("bg",dark?0xff121212:0xffd1d3d9);}
    int ink(){return color("text",dark?Color.WHITE:Color.BLACK);}
    int grip(){return color("hint",dark?0xffb4b4bc:0xff737378);}
    int control(){return dark?0xff3b4654:0xffd5e0ed;}
    int readout(){return dark?0xff292f38:0xffe5eaf0;}
    float bottomPadding(){return f(board,"padBottom",0);}
    float f(JSONObject o,String k,float d){return (float)o.optDouble(k,d);}
    float dp(float n){return n*density;}
    void box(Canvas c,float l,float t,float r,float b,float radius,int color){
        paint.setStyle(Paint.Style.FILL);paint.setColor(color);c.drawRoundRect(l,t,r,b,radius,radius,paint);
    }
    void word(Canvas c,String value,float x,float baseline,float size,int col){
        if(value.isEmpty())return;paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(size*scaled);
        paint.setTypeface(font);paint.setColor(col);c.drawText(value,x,baseline,paint);
    }
    float base(float center,float size){paint.setTextSize(size*scaled);paint.setTypeface(font);return center-(paint.ascent()+paint.descent())/2;}
    void draw(Canvas c,RectF frame,float barHeight,int bottomReservePx){
        box(c,frame.left,frame.top,frame.right,frame.bottom,0,background());
        int bar=color("toolbar",background());
        box(c,frame.left,frame.top,frame.right,frame.top+barHeight,0,bar);
        float left=frame.left+dp(20),right=frame.right-dp(20);
        int ri=0,li=0;
        for(int i=0;i<toolbar.length();i++){JSONObject s=toolbar.optJSONObject(i);if(s!=null && "right".equals(s.optString("side")))ri++;}
        for(int i=0;i<toolbar.length();i++){
            JSONObject s=toolbar.optJSONObject(i);if(s==null)continue;
            boolean rhs="right".equals(s.optString("side"));
            float cx=rhs?right-dp(40)*(--ri):left+dp(40)*li++,cy=frame.top+barHeight/2;
            String icon=s.optString("icon"),glyph=s.optString("glyph","");
            if(!s.optBoolean("visible",true))continue;  // no editor history is shared with an external Activity
            int col=color("barIcon",ink());Drawable art=vectors.get(icon);
            if(art!=null){
                float sz=dp(19)/2;art.setTint(col);art.setBounds(Math.round(cx-sz),Math.round(cy-sz),Math.round(cx+sz),Math.round(cy+sz));art.draw(c);
            }else{
                if(glyph.isEmpty()){if(icon.equals("settings"))glyph="\ue010";else if(icon.equals("hide"))glyph="\ue007";}
                word(c,glyph,cx,base(cy,19),19,col);
            }
        }
        int separator=color("separator",grip());paint.setColor(separator);c.drawRect(frame.left,frame.top+barHeight-1,frame.right,frame.top+barHeight,paint);
        float leftKey=frame.left+dp(f(board,"padLeft",0)),rightKey=frame.right-dp(f(board,"padRight",0)),w=rightKey-leftKey;
        float y=frame.top+barHeight,range=frame.bottom-bottomReservePx-y;
        float gx=dp(f(board,"gapX",6))/2,gy=dp(f(board,"gapY",6))/2;
        for(int row=0;row<rows.length();row++){
            JSONArray arr=rows.optJSONArray(row);if(arr==null)continue;float h=range*weights[row],x=leftKey;
            for(int i=0;i<arr.length();i++){
                JSONObject k=arr.optJSONObject(i);if(k==null)continue;
                float width=w*f(k,"w",10)/100, l=x+gx,r=x+width-gx,t=y+gy,b=y+h-gy;
                String role=k.optString("role","key");float cx=(l+r)/2,cy=(t+b)/2;
                if(!role.equals("spacer")){
                    box(c,l,t,r,b,dp(f(k,"radius",f(board,"radius",6))),color(role,color("key",background())));
                    String label=k.optString("label",""),icon=k.optString("icon","");
                    if(k.has("asciiLabel")&&ascii)label=k.optString("asciiLabel",label);
                    if(k.has("traditionalLabel")&&traditional)label=k.optString("traditionalLabel",label);
                    if(k.has("upperLabel")&&caps)label=k.optString("upperLabel",label);
                    if(label.equals("中") || label.equals("EN"))label=ascii?"EN":"中";
                    float sz=f(k,"fontSize",19),hs=f(k,"hintSize",9);
                    float tx=cx+f(k,"textOffsetX",0)*scaled,ty=cy+f(k,"textOffsetY",0)*scaled;
                    word(c,label,tx,base(ty,sz),sz,ink());
                    String hint=k.optString("hint",""),bottom=k.optString("bottomHint","");
                    paint.setTypeface(font);paint.setTextSize(hs*scaled);Paint.FontMetrics fm=paint.getFontMetrics();
                    word(c,hint,cx+f(k,"hintOffsetX",0)*scaled,t-fm.top+f(k,"hintOffsetY",0)*scaled,hs,color(k.optString("hintColor","hint"),grip()));
                    word(c,bottom,cx+f(k,"bottomHintOffsetX",0)*scaled,b-fm.bottom+f(k,"bottomHintOffsetY",0)*scaled,hs,color(k.optString("hintColor","hint"),grip()));
                } x+=width;
            } y+=h;
        }
    }
}
