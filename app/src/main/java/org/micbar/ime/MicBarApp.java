package org.micbar.ime;

import android.app.Application;
import java.io.File;

public final class MicBarApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (!BuildConfig.HAS_WRITER) {
            WriterPack.file(this).delete();
            new File(getNoBackupFilesDir(),"writer-4b.part").delete();
            File directory = getNoBackupFilesDir();
            new File(directory, "qwen25-05-q4km.gguf").delete();
            new File(directory, "qwen25-model.part").delete();
        }
    }
}
