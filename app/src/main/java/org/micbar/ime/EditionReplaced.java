package org.micbar.ime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the application after replacement so a switch to Lite releases extracted weights. */
public final class EditionReplaced extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        EdgeSwitcher.restore(context);
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        AutoUpdates.forget(context);
        AutoUpdates.schedule(context);
        java.io.File[] files=new java.io.File(context.getCacheDir(),"updates").listFiles();
        if (files!=null) for (java.io.File f:files) f.delete();
    }
}
