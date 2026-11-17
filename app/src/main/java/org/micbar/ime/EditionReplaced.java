package org.micbar.ime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the application after replacement so a switch to Lite releases extracted weights. */
public final class EditionReplaced extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context.getSharedPreferences("micbar",Context.MODE_PRIVATE).getBoolean(EdgeSwitcher.ENABLED,false)
                && android.provider.Settings.canDrawOverlays(context)) {
            try { context.startForegroundService(new Intent(context,EdgeSwitcher.class)); }
            catch (IllegalStateException restricted) {
                // SetupActivity resumes the enabled shortcut if the OS defers background starts.
            }
        }
        AutoUpdates.forget(context);
        AutoUpdates.schedule(context);
        java.io.File[] files=new java.io.File(context.getCacheDir(),"updates").listFiles();
        if (files!=null) for (java.io.File f:files) f.delete();
    }
}
