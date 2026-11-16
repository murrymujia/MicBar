package org.micbar.ime;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.Handler;
import android.os.Looper;
import java.io.File;

/** Prepare same-edition deltas on unmetered networks. Installation is user initiated. */
public final class AutoUpdates extends JobService {
    private static final int JOB=12101;
    static final String STATE="update-state";
    static SharedPreferences state(Context c){return c.getSharedPreferences(STATE,MODE_PRIVATE);}
    static boolean enabled(Context c){return state(c).getBoolean("automatic",true);}
    static void schedule(Context c) {
        JobScheduler jobs=c.getSystemService(JobScheduler.class);
        if(!enabled(c)){jobs.cancel(JOB);return;}
        if(jobs.getPendingJob(JOB)==null) jobs.schedule(new JobInfo.Builder(JOB,new ComponentName(c,AutoUpdates.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPersisted(true)
                .setPeriodic(24*60*60*1000L).build());
    }
    static String ready(Context c) {
        SharedPreferences p=state(c);
        String sha=p.getString("sha","");
        if(!BuildConfig.VERSION_NAME.equals(p.getString("base","")) || BuildConfig.HAS_WRITER!=p.getBoolean("full",false)
                || !sha.matches("[a-f0-9]{64}")) return "";
        File f=new File(new File(c.getCacheDir(),"updates"),sha+".apk");
        return f.isFile() && f.length()==p.getLong("bytes",-1)?sha:"";
    }
    static void forget(Context c){
        state(c).edit().remove("sha").remove("base").remove("version").apply();
        c.getSystemService(NotificationManager.class).cancel(JOB);
    }
    private Thread worker;
    @Override public boolean onStartJob(JobParameters params) {
        if(!enabled(this))return false;
        worker=new Thread(()->{
            boolean retry=false;
            try {
                EditionUpdates.Release r=EditionUpdates.fetch();
                EditionUpdates.Asset apk=r.apk(BuildConfig.HAS_WRITER),patch=r.patch(BuildConfig.HAS_WRITER,BuildConfig.VERSION_NAME);
                if(r.compare(BuildConfig.VERSION_NAME)>0 && patch!=null && patch.bytes<apk.bytes && patch.sha.matches("[a-f0-9]{64}")){
                    EditionUpdates.stage(this,new File(getApplicationInfo().sourceDir),r,patch,apk);
                    if(!Thread.currentThread().isInterrupted() && enabled(this)){
                        boolean fresh=!apk.sha.equals(ready(this));
                        state(this).edit().putString("sha",apk.sha).putString("base",BuildConfig.VERSION_NAME)
                            .putString("version",r.version).putLong("bytes",apk.bytes).putBoolean("full",BuildConfig.HAS_WRITER).apply();
                        if(fresh) notifyReady(r.version);
                    }
                }
            }catch(Exception unavailable){retry=true;}
            final Thread owner=Thread.currentThread();
            final boolean later=retry;
            new Handler(Looper.getMainLooper()).post(()->{
                if(worker==owner){worker=null;jobFinished(params,later);}
            });
        },"micbar-auto-update");
        worker.start();return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        if(worker!=null){worker.interrupt();worker=null;}return enabled(this);
    }
    private void notifyReady(String version){
        NotificationManager manager=getSystemService(NotificationManager.class);
        if(!manager.areNotificationsEnabled())return;
        String channel="updates";
        manager.createNotificationChannel(new NotificationChannel(channel,"版本更新",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,JOB,new Intent(this,SetupActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,channel).setSmallIcon(R.drawable.mic_icon)
                .setContentTitle("轻声键盘 "+version+" 已准备好").setContentText("打开轻声键盘即可安装")
                .setContentIntent(open).setAutoCancel(true).build();
        manager.notify(JOB,notification);
    }
}
