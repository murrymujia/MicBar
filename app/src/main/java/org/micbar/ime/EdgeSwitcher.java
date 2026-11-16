package org.micbar.ime;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.*;
import android.widget.Toast;

/** Optional screen-edge shortcut for the Android keyboard picker. */
public final class EdgeSwitcher extends Service {
    static final String ENABLED="edgeSwitcher";
    private static final String CHANNEL="keyboard_switcher";
    private static final int TYPE=WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private static final int PASSIVE=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    private WindowManager windows;
    private View probe;
    private ToolButton button;
    private WindowManager.LayoutParams bubbleParams;
    private boolean right, dragging, observing, buttonAdded;
    private float heightFraction, downX, downY;
    private int originX,originY,usableWidth,usableHeight;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ContentObserver selection=new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refresh(); }
    };
    private SharedPreferences prefs() { return getSharedPreferences("micbar",MODE_PRIVATE); }
    private int dp(int x) { return Math.round(getResources().getDisplayMetrics().density*x); }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        Context displayContext=Build.VERSION.SDK_INT>=30
                ? createDisplayContext(getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(Display.DEFAULT_DISPLAY)).createWindowContext(TYPE,null) : this;
        windows=displayContext.getSystemService(WindowManager.class);
        right=prefs().getBoolean("edgeRight",true);
        heightFraction=prefs().getFloat("edgeVertical",.72f);
        button=new ToolButton(displayContext);
        button.display(ToolButton.Icon.KEYBOARD,"切换到轻声键盘",0xffffffff,0xff344761);
        button.setOnClickListener(v -> {
            Intent picker=new Intent(this,PickerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(picker);
        });
        button.setOnTouchListener((v,e) -> handleTouch(e));
        bubbleParams=new WindowManager.LayoutParams(dp(49),dp(49),TYPE,PASSIVE,PixelFormat.TRANSLUCENT);
        bubbleParams.gravity=Gravity.TOP|Gravity.LEFT;
        bubbleParams.setTitle("MicBar Edge Switcher");
        probe=new View(displayContext);
        probe.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        probe.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->refresh());
        probe.setOnApplyWindowInsetsListener((v,insets)->{main.post(this::refresh);return insets;});
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if ("stop".equals(intent==null?null:intent.getAction())) prefs().edit().putBoolean(ENABLED,false).apply();
        NotificationManager notifications=getSystemService(NotificationManager.class);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"贴边切换按钮",NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);notifications.createNotificationChannel(channel);
        PendingIntent settings=PendingIntent.getActivity(this,0,new Intent(this,SetupActivity.class),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,EdgeSwitcher.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_switch)
                .setContentTitle("轻声键盘 · 贴边切换").setContentText("点按管理悬浮按钮").setContentIntent(settings)
                .setOngoing(true).setShowWhen(false).addAction(new Notification.Action.Builder(null,"关闭",stop).build()).build();
        startForeground(16,note);
        if (!prefs().getBoolean(ENABLED,false) || !Settings.canDrawOverlays(this)) {
            stopSelf();return START_NOT_STICKY;
        }
        if (!observing) {
            getContentResolver().registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),false,selection);
            observing=true;
            WindowManager.LayoutParams monitor=new WindowManager.LayoutParams(1,-1,TYPE,PASSIVE
                    |WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            monitor.gravity=Gravity.TOP|Gravity.LEFT;
            monitor.alpha=0;
            monitor.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
            monitor.setTitle("MicBar Keyboard Geometry");
            if(Build.VERSION.SDK_INT>=30) monitor.setFitInsetsTypes(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
            try {windows.addView(probe,monitor);} catch (RuntimeException e) {
                Toast.makeText(this,"系统未允许显示悬浮按钮",Toast.LENGTH_SHORT).show();
                prefs().edit().putBoolean(ENABLED,false).apply();
                stopSelf();return START_NOT_STICKY;
            }
        }
        refresh();
        return START_STICKY;
    }
    private void refresh() {
        if(!observing || !probe.isAttachedToWindow() || probe.getHeight()==0) return;
        WindowInsets insets=probe.getRootWindowInsets();
        DisplayMetrics metrics=new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        usableWidth=metrics.widthPixels;
        usableHeight=probe.getHeight();
        boolean keyboard=metrics.heightPixels-usableHeight>dp(145);
        if(Build.VERSION.SDK_INT>=30 && insets!=null) {
            WindowMetrics wm=windows.getMaximumWindowMetrics();
            Rect full=wm.getBounds();
            usableWidth=full.width();
            android.graphics.Insets bars=wm.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
            int available=full.height()-bars.top-bars.bottom;
            keyboard=insets.isVisible(WindowInsets.Type.ime()) || available-probe.getHeight()>dp(95);
            if(insets.isVisible(WindowInsets.Type.ime())) {
                int ime=insets.getInsets(WindowInsets.Type.ime()).bottom;
                if(ime>0) usableHeight=Math.min(usableHeight,full.height()-bars.top-ime);
            }
        }
        String ime=Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean ours=ime!=null && ime.startsWith(getPackageName()+"/");
        boolean show=keyboard && !ours && !getSystemService(KeyguardManager.class).isKeyguardLocked();
        if(!show) {removeButton();return;}
        if(!dragging) place();
        try {
            if(buttonAdded) windows.updateViewLayout(button,bubbleParams);
            else {
                windows.addView(button,bubbleParams);
                buttonAdded=true;
            }
        } catch(WindowManager.BadTokenException|SecurityException ex) {stopSelf();}
    }
    private int maxY() {return Math.max(0,usableHeight-bubbleParams.height-dp(4));}
    private int maxX() {return Math.max(0,usableWidth-bubbleParams.width);}
    private void place() {
        bubbleParams.x=right?maxX():0;
        bubbleParams.y=Math.round(maxY()*heightFraction);
    }
    private boolean handleTouch(MotionEvent e) {
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=e.getRawX();downY=e.getRawY();originX=bubbleParams.x;originY=bubbleParams.y;dragging=false;return true;
            case MotionEvent.ACTION_MOVE:
                float dx=e.getRawX()-downX,dy=e.getRawY()-downY;
                if(Math.hypot(dx,dy)>ViewConfiguration.get(button.getContext()).getScaledTouchSlop()) dragging=true;
                if(dragging) {
                    bubbleParams.x=Math.max(0,Math.min(maxX(),Math.round(originX+dx)));
                    bubbleParams.y=Math.max(0,Math.min(maxY(),Math.round(originY+dy)));
                    windows.updateViewLayout(button,bubbleParams);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if(!dragging) {button.performClick();return true;}
                right=bubbleParams.x>=maxX()/2;
                heightFraction=maxY()==0?0:bubbleParams.y/(float)maxY();
                prefs().edit().putBoolean("edgeRight",right).putFloat("edgeVertical",heightFraction).apply();
                // Snap the dragged control to the chosen side.
            case MotionEvent.ACTION_CANCEL:
                dragging=false;refresh();return true;
            default:return false;
        }
    }
    private void removeButton() {
        if(buttonAdded) {
            windows.removeViewImmediate(button);
            buttonAdded=false;
        }
        dragging=false;
    }
    @Override public void onConfigurationChanged(Configuration cfg) {super.onConfigurationChanged(cfg);main.post(this::refresh);}
    @Override public void onDestroy() {
        if(observing) getContentResolver().unregisterContentObserver(selection);
        observing=false;main.removeCallbacksAndMessages(null);removeButton();
        if(probe.isAttachedToWindow()) windows.removeViewImmediate(probe);
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
