package org.micbar.ime;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

final class WritingMenu {
    interface Editor {
        String source(); long position(); boolean apply(String source,String edit);
        String[] correction(); boolean save(String[] pair); boolean undo();
    }
    private final Context context;
    private final View anchor;
    private final Editor editor;
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private LocalWriter task;
    private PopupWindow window;
    private LinearLayout body;
    WritingMenu(Context c,View anchor,Editor editor) {context=c;this.anchor=anchor;this.editor=editor;}
    private int dp(float v) {return Math.round(v*context.getResources().getDisplayMetrics().density);}
    void close() {
        if(task!=null) {task.cancel();task=null;}
        if(window!=null) window.dismiss();
    }
    private TextView text(String value,int size,boolean bold) {
        TextView label=new TextView(context);label.setText(value);label.setTextSize(size);
        label.setTextColor(0xffeff2ff);label.setPadding(dp(12),dp(7),dp(12),dp(7));
        if(bold) label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return label;
    }
    private void action(LinearLayout parent,String title,Runnable callback) {
        TextView choice=text(title,15,false);choice.setTextColor(0xffc4d3ff);
        choice.setGravity(Gravity.CENTER_VERTICAL);choice.setMinHeight(dp(42));
        choice.setOnClickListener(v->callback.run());parent.addView(choice);
    }
    void open() {
        close();
        String source=editor.source();
        long position=editor.position();
        ScrollView scroller=new ScrollView(context);body=new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(4),dp(4),dp(4),dp(4));scroller.addView(body);
        GradientDrawable background=new GradientDrawable();background.setColor(0xff282b35);background.setCornerRadius(dp(18));
        body.addView(text(BuildConfig.HAS_WRITER ? "离线写作" : "个人词典",17,true));
        if (!BuildConfig.HAS_WRITER) body.addView(text("可在轻声键盘首页切换到完整版，使用离线 AI 写作。",14,false));
        else if(source.isEmpty()) body.addView(text("暂停听写，或选中输入框里的文字。",14,false));
        else if(source.length()>400) body.addView(text("请选中最多 400 个字符。",14,false));
        else {
            for(int i=0;i<LocalWriter.Mode.values().length;i+=2) {
                LinearLayout row=new LinearLayout(context);body.addView(row);
                for(int j=i;j<i+2 && j<LocalWriter.Mode.values().length;j++) {
                    LocalWriter.Mode m=LocalWriter.Mode.values()[j];action(row,m.label,()->run(m,source,position));
                }
            }
        }
        String[] pair=editor.correction();
        if(pair!=null) action(body,"记住写法 · "+(pair[0].equals(pair[1])?pair[1]:pair[0]+" → "+pair[1]),()->{
            toast(editor.save(pair)?"已存入本机个人词典":"词典已满或词条无效");close();
        });
        if (BuildConfig.HAS_WRITER) action(body,"撤销上次 AI 修改",()->{toast(editor.undo()?"已还原原文":"当前位置没有可还原的修改");close();});
        int width=Math.min(dp(300),context.getResources().getDisplayMetrics().widthPixels-dp(16));
        int maxHeight=Math.min(dp(332),context.getResources().getDisplayMetrics().heightPixels/2);
        body.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.UNSPECIFIED);
        int height=Math.min(body.getMeasuredHeight(),maxHeight);
        PopupWindow p=new PopupWindow(scroller,width,height,false);
        p.setBackgroundDrawable(background);p.setOutsideTouchable(true);p.setElevation(dp(8));
        p.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        p.setOnDismissListener(()->{if(window==p) {window=null;if(task!=null) task.cancel();task=null;}});
        window=p;p.showAsDropDown(anchor,0,-height-anchor.getHeight(),Gravity.END);
    }
    private void toast(String message) {Toast.makeText(context,message,Toast.LENGTH_SHORT).show();}
    private void run(LocalWriter.Mode mode,String source,long position) {
        body.removeAllViews();body.addView(text(mode.label+"…",17,true));action(body,"取消",this::close);
        LocalWriter job=new LocalWriter();task=job;
        worker.execute(()->{
            String result=null,reason=null;
            try {result=job.rewrite(context,mode,source);}
            catch(CancellationException ex) {return;}
            catch(Exception ex) {reason=ex.getMessage();}
            final String answer=result, message=reason;
            main.post(()->{
                if(task!=job || window==null) return;
                task=null;body.removeAllViews();
                if(answer==null) body.addView(text(message==null?"未生成建议":message,15,false));
                else if(answer.equals(source)) body.addView(text("原文可以直接使用",16,true));
                else {
                    body.addView(text(mode.label,16,true));body.addView(text(answer,16,false));
                    action(body,"采用建议",()->{
                        if(position!=editor.position() || !source.equals(editor.source()) || !editor.apply(source,answer)) toast("输入位置已变化，请重新选择");
                        close();
                    });
                }
                action(body,"保留原文",this::close);
            });
        });
    }
}
