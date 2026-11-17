package org.micbar.ime;

import android.app.Application;
import java.io.File;

public final class MicBarApp extends Application {
    // IME and overlay callbacks run on the main thread in the same process.
    MicIme visibleIme;
    Runnable imeVisibilityChanged;
    void setVisibleIme(MicIme ime) {
        if(visibleIme==ime) return;
        visibleIme=ime;
        if(imeVisibilityChanged!=null) imeVisibilityChanged.run();
    }

    @Override public void onCreate() {
        super.onCreate();
        AutoUpdates.schedule(this);
        if (!BuildConfig.HAS_WRITER) {
            WriterPack.file(this).delete();
            new File(getNoBackupFilesDir(),"writer-4b.part").delete();
            File directory = getNoBackupFilesDir();
            new File(directory, "qwen25-05-q4km.gguf").delete();
            new File(directory, "qwen25-model.part").delete();
        }
    }
}
