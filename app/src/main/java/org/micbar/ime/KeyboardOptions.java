package org.micbar.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Shared controls for the in-keyboard preferences and the setup screen. */
final class KeyboardOptions {
    final SharedPreferences prefs;
    private final Context context;
    private final Runnable changed;
    boolean dark;
    int base, key, function, primary, onPrimary, ink, secondary;

    KeyboardOptions(Context context,Runnable changed) {
        this.context=context; this.changed=changed;
        prefs=context.getSharedPreferences("micbar",Context.MODE_PRIVATE);
        refresh();
    }

    void refresh() {
        String theme=prefs.getString("keyTheme","深色");
        boolean night=(context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
        dark=theme.equals("深色") || (theme.equals("跟随系统") && night);
        base=dark?0xff191a21:0xffe1e3eb;
        key=dark?0xff2b2d36:0xfffafaff;
        function=dark?0xff363b4e:0xffcbd0df;
        primary=dark?0xffb5c7ff:0xffc1d6ff;
        onPrimary=0xff233653;
        ink=dark?0xffececf5:0xff20232b;
        secondary=dark?0xffbdc2d2:0xff50576a;
    }
    int dp(int n) { return Math.round(n*context.getResources().getDisplayMetrics().density); }
    boolean phonePortrait() {
        Configuration c=context.getResources().getConfiguration();
        return c.orientation==Configuration.ORIENTATION_PORTRAIT && c.screenWidthDp>0 && c.screenWidthDp<600;
    }
    boolean shortLandscape() {
        Configuration c=context.getResources().getConfiguration();
        return c.orientation==Configuration.ORIENTATION_LANDSCAPE && c.screenHeightDp>0 && c.screenHeightDp<=480;
    }
    int rowHeight() {
        String h=prefs.getString("keyHeight","标准");
        int normal=h.equals("紧凑")?42:h.equals("宽松")?58:50;
        if(phonePortrait()) {
            // Ten letter columns determine the pitch. Keep large phones from growing extra-tall keys.
            int column=(context.getResources().getConfiguration().screenWidthDp-8)/10;
            int standard=Math.max(40,Math.min(48,Math.round(column*1.18f)));
            return standard+(h.equals("紧凑")?-4:h.equals("宽松")?6:0);
        }
        if(!shortLandscape()) return normal;
        // Four letter rows share the height left after the toolbar and gaps.
        int budget=context.getResources().getConfiguration().screenHeightDp*44/100;
        return Math.min(normal,Math.max(24,(budget-47)/4));

    }
    boolean numberRow() { return !shortLandscape() && prefs.getBoolean("keyNumbers",!phonePortrait()); }
    boolean borders() { return prefs.getBoolean("keyBorders",true); }
    GradientDrawable shape(int color,int radius) {
        GradientDrawable g=new GradientDrawable(); g.setColor(color);g.setCornerRadius(dp(radius));return g;
    }
    RippleDrawable face(int color,int radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark?0x42cbd5fb:0x337080ae),shape(color,radius),null);
    }
    private TextView text(String label,int size) {
        TextView t=new TextView(context);t.setText(label); t.setTextSize(size);t.setTextColor(ink); return t;
    }
    private void section(LinearLayout box,String title) {
        TextView t=text(title,13); t.setTextColor(secondary);t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setPadding(dp(12),dp(10),0,dp(4));box.addView(t);
    }
    private void choice(LinearLayout box,String title,String name,String fallback,String... values) {
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12),0,dp(6),0);
        row.addView(text(title,16),new LinearLayout.LayoutParams(0,-2,1));
        String current=prefs.getString(name,fallback);
        for(String value:values) {
            TextView t=text(value,14);t.setGravity(Gravity.CENTER);t.setSelected(value.equals(current));
            t.setTextColor(t.isSelected()?onPrimary:ink);
            t.setBackground(face(t.isSelected()?primary:key,20));t.setPadding(dp(10),0,dp(10),0);
            t.setContentDescription(title+"，"+value);t.setOnClickListener(v->{
                prefs.edit().putString(name,value).apply();changed.run();
            });
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(40));lp.leftMargin=dp(3);row.addView(t,lp);
        }
        box.addView(row,new LinearLayout.LayoutParams(-1,dp(50)));
    }
    private void toggle(LinearLayout box,String title,String name,boolean initial) {
        Switch s=new Switch(context);s.setText(title);s.setTextColor(ink);s.setTextSize(16);
        s.setPadding(dp(12),0,dp(12),0); s.setChecked(prefs.getBoolean(name,initial));
        s.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{primary,secondary}));
        s.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{dark?0xff566588:0xff849dcb,function}));
        s.setOnCheckedChangeListener((v,on)->prefs.edit().putBoolean(name,on).apply());
        box.addView(s,new LinearLayout.LayoutParams(-1,dp(48)));
    }
    private TextView pill(String title,boolean selected,Runnable click) {
        TextView item=text(title,14);item.setGravity(Gravity.CENTER);
        item.setTextColor(selected?onPrimary:ink);item.setSelected(selected);
        item.setBackground(face(selected?primary:key,14));item.setOnClickListener(v->click.run());return item;
    }
    private void cell(LinearLayout row,TextView item) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(42),1);
        lp.setMargins(dp(2),dp(2),dp(2),dp(2));row.addView(item,lp);
    }
    private void shortcuts(LinearLayout box,int open) {
        box.removeAllViews();section(box,"语音工具栏 · 自定义键位");
        int count=VoiceShortcut.count(prefs);
        LinearLayout counts=new LinearLayout(context);box.addView(counts);
        for(int i=0;i<=3;i++) {
            final int n=i;
            cell(counts,pill(i+" 个",count==i,()->{
                prefs.edit().putInt("voiceSlotCount",n).apply();
                shortcuts(box,n>count?count:Math.min(open,n-1));
            }));
        }
        for(int i=0;i<count;i++) {
            final int slot=i;
            VoiceShortcut saved=VoiceShortcut.at(prefs,i);
            TextView header=pill("位置 "+(i+1)+"　"+(saved.computer()?"电脑":"手机")+" · "+saved.label,open==i,
                    ()->shortcuts(box,open==slot?-1:slot));
            box.addView(header,new LinearLayout.LayoutParams(-1,dp(42)));
            if(open!=i) continue;
            LinearLayout groups=new LinearLayout(context);box.addView(groups);
            for(int g=0;g<2;g++) {
                boolean pc=g==1;
                cell(groups,pill(pc?"电脑键位":"手机键位",saved.computer()==pc,()->{
                    if(saved.computer()!=pc) {
                        String key="voiceSlot"+slot;
                        String next=prefs.getString(key+(pc?"Computer":"Phone"),pc?"TAB":"SPACE");
                        prefs.edit().putString(key,next).apply();shortcuts(box,slot);
                    }
                }));
            }
            LinearLayout row=null;int column=0;
            for(VoiceShortcut action:VoiceShortcut.values()) {
                if(action.computer()!=saved.computer()) continue;
                if(column++%3==0) {row=new LinearLayout(context);box.addView(row);}
                cell(row,pill(action.label,saved==action,()->{
                    String key="voiceSlot"+slot;
                    prefs.edit().putString(key,action.name()).putString(key+(action.computer()?"Computer":"Phone"),action.name()).apply();
                    shortcuts(box,slot);
                }));
            }
        }
    }
    View page() {
        LinearLayout box=new LinearLayout(context);box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(base);box.setPadding(dp(8),0,dp(8),dp(8));
        LinearLayout slots=new LinearLayout(context);slots.setOrientation(LinearLayout.VERTICAL);box.addView(slots);
        shortcuts(slots,-1);
        section(box,"外观与布局");
        choice(box,"主题","keyTheme","深色","跟随系统","浅色","深色");
        choice(box,"键盘高度","keyHeight","标准","紧凑","标准","宽松");
        toggle(box,"独立数字行","keyNumbers",!phonePortrait());
        toggle(box,"显示字母键底色","keyBorders",true);
        section(box,"输入方式");
        toggle(box,"自动学习选词和听写改字","learnWords",true);
        toggle(box,"打字先存草稿，确认后填入","typedDraft",false);
        toggle(box,"听写填入时补句末标点","finalSpeechMark",true);
        toggle(box,"输入框被挡时显示当前输入","editorPreview",true);
        toggle(box,"保留分段识别中的句号","keepDictatedPeriods",false);
        section(box,"按键反馈");
        toggle(box,"按键音","keySound",false);
        toggle(box,"按键振动","keyHaptic",true);
        ScrollView scroll=new ScrollView(context);scroll.addView(box);scroll.setBackgroundColor(base);return scroll;
    }
}
