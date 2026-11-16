package org.micbar.ime;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.security.MessageDigest;

final class WriterPack {
    static final String NAME = "Qwen3-4B-Instruct-2507";
    static final String URL = "https://modelscope.cn/models/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/master/Qwen3-4B-Instruct-2507-Q4_K_M.gguf";
    static final long SIZE = 2497281120L;
    private static final String SHA = "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597";
    static File file(Context c) { return new File(c.getNoBackupFilesDir(),"writer-4b.gguf"); }
    static boolean enabled(Context c) {
        return BuildConfig.HAS_WRITER && c.getSharedPreferences("micbar",0).getBoolean("writer4b",false)
                && file(c).length()==SIZE;
    }
    static void select(Context c,boolean enable) { c.getSharedPreferences("micbar",0).edit().putBoolean("writer4b",enable).apply(); }
    static synchronized void load(Context c,Uri uri) throws Exception {
        File dir=c.getNoBackupFilesDir(), temp=new File(dir,"writer-4b.part");
        if (dir.getUsableSpace()<SIZE) throw new IOException("请留出至少 2.5 GB 空间");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try {
            try(InputStream in=c.getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(temp)){
                if (in==null) throw new IOException("无法读取文件");
                byte[] buffer=new byte[256*1024]; long total=0; int n;
                while ((n=in.read(buffer))!=-1) {
                    total+=n;
                    if(total>SIZE) throw new IOException("请选择 4B 的 Q4_K_M 文件");
                    digest.update(buffer,0,n);out.write(buffer,0,n);
                }
            }
            StringBuilder actual=new StringBuilder();
            for(byte b:digest.digest()) actual.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(temp.length()!=SIZE || !SHA.contentEquals(actual)) throw new IOException("模型校验失败，请选择完整的 Q4_K_M 文件");
            android.system.Os.rename(temp.getPath(),file(c).getPath());select(c,true);
        } finally { temp.delete(); }
    }
}
