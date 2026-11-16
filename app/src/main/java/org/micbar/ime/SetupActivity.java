package org.micbar.ime;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;

public final class SetupActivity extends Activity {
    private TextView status;
    private Button next, micPermission;
    private Switch bubble;
    private boolean pendingOverlay;
    private EditText sample;
    private LinearLayout content;
    private InputMethodManager manager;
    private static final int INK = 0xff152b27, MUTED = 0xff465f59, ACCENT = 0xff195950;
    private boolean asked;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        manager = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xfff7f9f5); scroll.setFillViewport(true);
        FrameLayout viewport = new FrameLayout(this);
        viewport.addView(scroll);
        viewport.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(edges.left, edges.top, edges.right, edges.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            if(android.os.Build.VERSION.SDK_INT>=30 && sample!=null && sample.hasFocus()
                    && insets.isVisible(WindowInsets.Type.ime())) {
                sample.post(()->{
                    android.graphics.Rect caret=new android.graphics.Rect();
                    sample.getFocusedRect(caret);sample.requestRectangleOnScreen(caret,false);
                });
            }
            return insets;
        });
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        FrameLayout page = new FrameLayout(this); scroll.addView(page);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels, dp(600));
        FrameLayout.LayoutParams column = new FrameLayout.LayoutParams(width,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24),dp(28),dp(24),dp(36));
        content.setFocusableInTouchMode(true); page.addView(content,column);
        label("轻声键盘",32,true,0);
        label("点麦克风开始听写，再点暂停。草稿默认收起，长按键盘图标可在同一行查看，再长按收起。点键盘即可展开改字。点退格删除，轻点工具栏回车只填入文字，长按则填入并执行回车。",17,false,10);
        status=label("",16,true,25);
        next=new Button(this); next.setTextColor(0xffffffff); next.setBackground(tint(ACCENT,14)); next.setAllCaps(false);
        next.setOnClickListener(v -> advance()); add(next,10,50);
        label("试说一句",19,true,26);
        sample=new EditText(this); sample.setSingleLine(false); sample.setMinLines(2); sample.setMaxLines(5);
        sample.setHint("点这里打字或听写"); sample.setTextSize(17);
        sample.setTextColor(INK); sample.setHintTextColor(MUTED); sample.setBackground(tint(0xffe8efe9,14));
        sample.setPadding(dp(16),dp(12),dp(16),dp(12)); add(sample,8,-2);
        label("在 O+ 里使用",19,true,24);
        label("选「平板键盘」并点电脑的输入框。远控中默认只显示四个按钮，可拖动麦克风挪开整排。其他应用默认展开打字键盘。",15,false,6);
        Button typing=new Button(this);typing.setText("键盘设置");
        typing.setOnClickListener(v -> showKeyboardSettings());add(typing,8,-2);
        Button words=new Button(this);words.setText("个人词典");words.setOnClickListener(v->wordList());add(words,4,-2);
        if (BuildConfig.HAS_WRITER) {
            label("离线写作",19,true,18);
        label("暂停听写后长按麦克风，可整理、校对、缩短或翻译草稿；打字时也可以先选中文字。长按工具栏退格可撤销刚填入的听写。",15,false,6);
        }
        label("从其他键盘切回来",19,true,24);
        bubble=new Switch(this);
        bubble.setText("贴边悬浮按钮");bubble.setTextSize(17); add(bubble,5,48);
        label("用其他键盘打字时，点屏幕边缘的小键盘，选择轻声键盘。拖动按钮可换边或调整高度。",15,false,4);
        pendingOverlay=state!=null && state.getBoolean("pendingOverlay");
        bubble.setOnClickListener(v -> {
            if(!bubble.isChecked()) { setBubble(false); return; }
            if(Settings.canDrawOverlays(this)) { setBubble(true); return; }
            pendingOverlay=true;
            bubble.setChecked(false);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:"+getPackageName())));
            } catch(ActivityNotFoundException ex) {
                pendingOverlay=false;
                Toast.makeText(this,"请在系统设置中允许轻声键盘显示悬浮窗",Toast.LENGTH_LONG).show();
            }
        });
        label("听写方式",19,true,24);
        micPermission=new Button(this);
        micPermission.setText("允许听写使用麦克风");
        micPermission.setOnClickListener(v -> requestMicrophone());
        add(micPermission,4,-2);
        RadioGroup engines=new RadioGroup(this);
        RadioButton local=new RadioButton(this), system=new RadioButton(this);
        local.setId(View.generateViewId()); system.setId(View.generateViewId());
        local.setText("内置中英混合听写 · 离线"); system.setText("设备语音服务 · 混说支持取决于设备");
        boolean available=SpeechRecognizer.isRecognitionAvailable(this);
        system.setEnabled(available);
        SharedPreferences prefs=getSharedPreferences("micbar",MODE_PRIVATE);
        if (!available) prefs.edit().putBoolean("systemForMixed",false).apply();
        engines.addView(local); engines.addView(system); add(engines,4,-2);
        engines.check(prefs.getBoolean("systemForMixed",false) ? system.getId() : local.getId());
        engines.setOnCheckedChangeListener((group,id) -> prefs.edit().putBoolean("systemForMixed",id==system.getId()).apply());
        CheckBox direct = new CheckBox(this);
        direct.setText("结束时自动填入（手动改过的草稿仍需确认）");
        direct.setChecked(prefs.getBoolean("directInsert",false));
        direct.setOnCheckedChangeListener((box,checked) -> prefs.edit().putBoolean("directInsert",checked).apply());
        add(direct,4,-2);
        CheckBox terms = new CheckBox(this);
        terms.setText("修正常见编程术语的拼写");
        terms.setChecked(prefs.getBoolean("termSpelling", true));
        terms.setOnCheckedChangeListener((box, checked) -> prefs.edit().putBoolean("termSpelling", checked).apply());
        add(terms, 4, -2);
        label("内置模式在平板上识别，不保存录音。设备语音服务可能联网。切换输入框或收起键盘会取消当前听写。",14,false,8);
        label("版本与更新",19,true,20);
        label("当前版本  " + BuildConfig.VERSION_NAME + " · " + EditionUpdates.editionName(BuildConfig.HAS_WRITER),15,false,4);
        Button releases = new Button(this);
        releases.setText("检查更新 / 切换版本");
        releases.setOnClickListener(v -> new EditionUpdates(this).check(releases));
        add(releases,6,-2);
        Button about=new Button(this); about.setText("隐私与开源许可");
        about.setOnClickListener(v -> showAbout()); add(about,10,-2);
        setContentView(viewport);
    }
    private void setBubble(boolean enabled) {
        getSharedPreferences("micbar",MODE_PRIVATE).edit().putBoolean(EdgeSwitcher.ENABLED,enabled).apply();
        Intent service=new Intent(this,EdgeSwitcher.class);
        if(enabled) startForegroundService(service); else stopService(service);
        bubble.setChecked(enabled);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("pendingOverlay",pendingOverlay);super.onSaveInstanceState(state);
    }
    private final android.database.ContentObserver imeChanged=new android.database.ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) { update(); }
    };
    @Override protected void onResume() {
        super.onResume();
        boolean allowed=Settings.canDrawOverlays(this);
        boolean wanted=getSharedPreferences("micbar",MODE_PRIVATE).getBoolean(EdgeSwitcher.ENABLED,false);
        if (pendingOverlay || wanted) setBubble(allowed);
        pendingOverlay=false;
        bubble.setChecked(allowed && getSharedPreferences("micbar",MODE_PRIVATE).getBoolean(EdgeSwitcher.ENABLED,false));
        getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),false,imeChanged);
        update();
    }
    @Override protected void onPause() { getContentResolver().unregisterContentObserver(imeChanged);super.onPause(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if(focus) update(); }
    private boolean permitted() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED; }
    private boolean enabled() {
        for(InputMethodInfo info:manager.getEnabledInputMethodList()) if(getPackageName().equals(info.getPackageName())) return true;
        return false;
    }
    private boolean selected() {
        String value=Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName component=value==null ? null : ComponentName.unflattenFromString(value);
        return component!=null && component.getPackageName().equals(getPackageName());
    }
    private void update() {
        if (next==null) return;
        micPermission.setVisibility(permitted()? View.GONE : View.VISIBLE);
        if (!enabled()) { status.setText("第 1 步 / 共 2 步"); next.setText("在系统中启用轻声键盘"); }
        else if (!selected()) { status.setText("第 2 步 / 共 2 步"); next.setText("选择轻声键盘"); }
        else { status.setText("轻声键盘已就绪"); next.setText("试用键盘"); }
    }
    private void requestMicrophone() {
        if (!permitted()) {
            if (asked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+getPackageName())));
            } else { asked=true; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); }
        }
    }
    private void advance() {
        if (!enabled()) startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        else if (!selected()) manager.showInputMethodPicker();
        else { sample.requestFocus(); manager.showSoftInput(sample,InputMethodManager.SHOW_IMPLICIT); }
    }
    private void showKeyboardSettings() {
        FrameLayout body=new FrameLayout(this);
        KeyboardOptions options=new KeyboardOptions(this,new Runnable() {
            public void run() {
                KeyboardOptions page=new KeyboardOptions(SetupActivity.this,this);
                body.removeAllViews();body.addView(page.page());
            }
        });
        body.addView(options.page());
        new android.app.AlertDialog.Builder(this).setTitle("键盘设置").setView(body)
                .setPositiveButton("完成",null).show();
    }
    private void wordList() {
        PersonalWords words=new PersonalWords(this);
        java.util.Map<String,?> entries=words.entries();
        java.util.List<String> names=new java.util.ArrayList<>(entries.keySet());
        java.util.Collections.sort(names);
        java.util.List<String> labels=new java.util.ArrayList<>();
        for(String name:names) {
            if(name.startsWith("a:") || name.startsWith("c:"))
                labels.add(name.substring(2)+" → "+entries.get(name)+(name.startsWith("c:")?" · 自动学习":""));
            else labels.add(name.substring(2).replace(":"," → ")+" · 选词");
        }
        android.app.AlertDialog.Builder box=new android.app.AlertDialog.Builder(this).setTitle("个人词典");
        if(names.isEmpty()) box.setMessage("点选拼音候选、改正听写草稿里的词并填入后，词条会自动出现在这里。");
        else box.setItems(labels.toArray(new String[0]),(dlg,i)->new android.app.AlertDialog.Builder(this)
                .setMessage("删除「"+labels.get(i)+"」？").setPositiveButton("删除",(a,b)->{words.forget(names.get(i));wordList();})
                .setNegativeButton("取消",null).show());
        box.setPositiveButton("添加词条",(a,b)->editWord(words)).setNegativeButton("关闭",null);
        if(!names.isEmpty()) box.setNeutralButton("清空",(a,b)->new android.app.AlertDialog.Builder(this)
                .setMessage("清空所有个人词条和选词记录？").setNegativeButton("取消",null)
                .setPositiveButton("清空",(x,y)->{words.clear();wordList();}).show());
        box.show();
    }
    private void editWord(PersonalWords words) {
        LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20),dp(4),dp(20),0);
        EditText alias=new EditText(this),value=new EditText(this);alias.setSingleLine();value.setSingleLine();
        alias.setHint("听到的写法或拼音");value.setHint("希望输入的写法");fields.addView(alias);fields.addView(value);
        android.app.AlertDialog dlg=new android.app.AlertDialog.Builder(this).setTitle("记住写法").setView(fields)
                .setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dlg.setOnShowListener(x->dlg.getButton(-1).setOnClickListener(v->{
            if(words.remember(alias.getText().toString().trim(),value.getText().toString().trim())) {dlg.dismiss();wordList();}
            else value.setError("请使用 1–48 个字母、汉字、数字或词内符号；最多 512 条");
        }));dlg.show();
    }
    private void showAbout() {
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this)
                .setTitle("隐私与开源许可")
                .setMessage("内置听写在本机处理音频。录音和草稿只留在当前会话，个人词典仅在本机保存你添加的写法，以及自动学习的选词和听写改字。可逐条删除或清空。打字、内置听写和本地写作均在设备上完成。点「检查更新」时仅向 GitHub 查询公开版本信息。选择设备语音服务时，语音由该服务处理。点 ✓ 填入后，接收文字的应用可以读取它。\n\n拼音和英文打字无需麦克风权限。可在系统设置中撤回麦克风权限。")
                .setPositiveButton("完成",null)
                .setNeutralButton("开源组件", (d,w) -> showLicenses()).create();
        dialog.show();
    }
    private void showLicenses() {
        try {
            String[] files=getAssets().list("licenses");
            new android.app.AlertDialog.Builder(this).setTitle("开源组件与许可")
                    .setItems(files,(d,w) -> {
                        try (java.io.BufferedReader in=new java.io.BufferedReader(new java.io.InputStreamReader(
                                getAssets().open("licenses/"+files[w]),java.nio.charset.StandardCharsets.UTF_8))) {
                            StringBuilder contents=new StringBuilder(); String line;
                            while ((line=in.readLine())!=null) contents.append(line).append('\n');
                            new android.app.AlertDialog.Builder(this).setTitle(files[w]).setMessage(contents)
                                    .setPositiveButton("完成",null).show();
                        } catch (java.io.IOException e) { Toast.makeText(this,"许可文件读取失败",Toast.LENGTH_SHORT).show(); }
                    }).setNegativeButton("返回",null).show();
        } catch (java.io.IOException e) { Toast.makeText(this,"许可目录读取失败",Toast.LENGTH_SHORT).show(); }
    }
    private TextView label(String text,int size,boolean bold,int top) {
        TextView view=new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(bold?INK:MUTED);
        if(bold) view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        view.setLineSpacing(dp(2),1); add(view,top,-2); return view;
    }
    private void add(View v,int top,int height) {
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,height<0?height:dp(height)); p.topMargin=dp(top);
        content.addView(v,p);
    }
    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private GradientDrawable tint(int color,int radius) { GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
}
