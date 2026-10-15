package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Presentation of the original settings adapter; every property and click remains Sony-owned. */
public final class V3Settings {
    private static final int PAPER=0xfff7f7f4,INK=0xff181919,MUTED=0xff70716f;
    private static final WeakHashMap<Activity,Binding> BINDINGS=new WeakHashMap<Activity,Binding>();
    private V3Settings(){}
    public static void apply(Activity host){
        if(host==null||!host.getClass().getName().equals("com.sony.playmemories.mobile.settings.SettingsActivity"))return;
        Binding b=BINDINGS.get(host);View raw=find(host,"settingslistview");
        if(!(raw instanceof ListView))return;
        if(b==null||b.list.get()!=raw){if(b!=null)b.stop();b=new Binding(host,(ListView)raw);BINDINGS.put(host,b);}
        b.start();
    }
    public static void pause(Activity host){Binding b=BINDINGS.get(host);if(b!=null)b.stop();}
    public static void destroy(Activity host){pause(host);BINDINGS.remove(host);}
    public static boolean onBack(Activity host){return false;}
    private static View find(Activity host,String name){int id=host.getResources().getIdentifier(name,"id",host.getPackageName());return id==0?null:host.findViewById(id);}
    private static final class Binding implements ViewTreeObserver.OnPreDrawListener {
        final WeakReference<Activity> activity;final WeakReference<ListView> list;
        final WeakHashMap<View,String> rows=new WeakHashMap<View,String>();boolean observing;
        Binding(Activity host,ListView source){activity=new WeakReference<Activity>(host);list=new WeakReference<ListView>(source);}
        int dp(int n){Activity host=activity.get();return Math.round(n*(host==null?1:host.getResources().getDisplayMetrics().density));}
        void start(){ListView source=list.get();if(source==null)return;if(!observing){source.getViewTreeObserver().addOnPreDrawListener(this);observing=true;}style();}
        void stop(){ListView source=list.get();if(source!=null&&observing&&source.getViewTreeObserver().isAlive())source.getViewTreeObserver().removeOnPreDrawListener(this);observing=false;}
        public boolean onPreDraw(){style();return true;}
        boolean section(ListAdapter adapter,int at){return at>=0&&at<adapter.getCount()&&adapter.getItem(at)!=null&&adapter.getItem(at).getClass().getName().endsWith(".SectionTitleProperty");}
        void style(){
            Activity host=activity.get();ListView source=list.get();if(host==null||source==null)return;
            View page=find(host,"settings_list");if(page!=null)page.setBackgroundColor(PAPER);
            View toolbar=find(host,"toolbar");if(toolbar!=null){toolbar.setBackgroundColor(PAPER);toolbar.setElevation(0);try{toolbar.getClass().getMethod("setTitle",CharSequence.class).invoke(toolbar,"设置");}catch(Exception ignored){}}
            if(source.getPaddingLeft()!=dp(20)){source.setPadding(dp(20),dp(4),dp(20),dp(24));source.setClipToPadding(false);source.setDivider(null);source.setDividerHeight(0);}
            source.setBackgroundColor(PAPER);
            View footer=find(host,"listview_hooter");if(footer instanceof TextView){((TextView)footer).setTextColor(MUTED);((TextView)footer).setTextSize(10);footer.setBackgroundColor(PAPER);}
            ListAdapter adapter=source.getAdapter();if(adapter==null)return;
            for(int i=0;i<source.getChildCount();i++){
                int at=source.getFirstVisiblePosition()+i;if(at>=adapter.getCount())break;
                View row=source.getChildAt(i);boolean heading=section(adapter,at);
                boolean first=at==0||section(adapter,at-1),last=at==adapter.getCount()-1||section(adapter,at+1);
                String key=at+"|"+heading+"|"+first+"|"+last;
                if(key.equals(rows.get(row)))continue;rows.put(row,key);
                TextView title=(TextView)row.findViewById(host.getResources().getIdentifier("settings_menu_text","id",host.getPackageName()));
                if(title==null)continue;
                title.setTypeface(Typeface.create(heading?"sans-serif-medium":"sans-serif",Typeface.NORMAL));title.setTextSize(heading?12:16);title.setTextColor(heading?MUTED:INK);title.setIncludeFontPadding(false);
                if(heading){
                    row.setBackgroundColor(PAPER);ViewGroup.LayoutParams params=row.getLayoutParams();if(params!=null){params.height=dp(48);row.setLayoutParams(params);}
                    title.setText(shortHeading(title.getText().toString()));
                }else{
                    GradientDrawable bg=new GradientDrawable();bg.setColor(0xffffffff);float r=dp(16);bg.setCornerRadii(new float[]{first?r:0,first?r:0,first?r:0,first?r:0,last?r:0,last?r:0,last?r:0,last?r:0});row.setBackground(bg);row.setMinimumHeight(dp(70));
                    title.setSingleLine(false);title.setMaxLines(2);
                    if(title.getLayoutParams() instanceof RelativeLayout.LayoutParams){RelativeLayout.LayoutParams p=(RelativeLayout.LayoutParams)title.getLayoutParams();p.width=-1;p.leftMargin=dp(16);p.rightMargin=dp(28);p.topMargin=dp(13);title.setLayoutParams(p);}
                    int valueId=host.getResources().getIdentifier("settings_menu_current_value","id",host.getPackageName());View value=row.findViewById(valueId);
                    if(value instanceof TextView){TextView text=(TextView)value;text.setTextSize(12);text.setTextColor(MUTED);text.setIncludeFontPadding(false);}
                    int dividerId=host.getResources().getIdentifier("settings_menu_divider","id",host.getPackageName());View divider=row.findViewById(dividerId);if(divider!=null)divider.setBackgroundColor(last?0x00ffffff:0xffeeeee9);
                }
            }
        }
        String shortHeading(String original){
            if(original.startsWith("从拍摄装置导入"))return "照片与存储";
            if(original.startsWith("多摄像机控制"))return "多相机 · 兼容模式";
            if(original.equals("拍摄装置列表"))return "相机管理";
            if(original.equals("应用程序设置"))return "应用";
            return original;
        }
    }
}
