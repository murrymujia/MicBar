package org.micbar.ime;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.zip.ZipFile;

public final class OfflineUpdateActivity extends Activity {
    private static final String IME = "org.micbar.ime";
    private final Properties release = new Properties();
    private TextView detail;
    private Button action;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        page.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("轻声键盘 · 离线更新");
        title.setTextSize(24);
        page.addView(title);
        detail = new TextView(this);
        detail.setPadding(0, pad, 0, pad);
        page.addView(detail);
        action = new Button(this);
        action.setText("准备并安装");
        action.setOnClickListener(v -> prepare());
        page.addView(action);
        setContentView(page);
        try (InputStream in = getAssets().open("release.properties")) {
            release.load(in);
        } catch (IOException ex) {
            detail.setText("缺少更新信息，请重新获取更新工具。");
        }
    }

    @Override public void onResume() {
        super.onResume();
        if (busy) return;
        action.setEnabled(false);
        String from = release.getProperty("from"), to = release.getProperty("to");
        if (to == null) return;
        try {
            PackageInfo installed = getPackageManager().getPackageInfo(IME, 0);
            if (to.equals(installed.versionName)) {
                detail.setText("轻声键盘已更新到 " + to + "。现在可以关闭或卸载本工具。");
                File[] files = directory().listFiles();
                if (files != null) for (File file : files) file.delete();
            } else if (from.equals(installed.versionName)) {
                detail.setText("将已安装的 " + from + " 原地升级到 " + to + "，自动匹配轻量版或完整版。");
                action.setEnabled(true);
            } else {
                detail.setText("本机版本为 " + installed.versionName + "。此工具仅适用于 " + from + "。");
            }
        } catch (PackageManager.NameNotFoundException ex) {
            detail.setText("请先确认本机已安装轻声键盘。");
        }
    }

    private File directory() {
        return new File(getCacheDir(), "updates");
    }

    private void prepare() {
        busy = true;
        action.setEnabled(false);
        detail.setText("正在本机还原并校验安装包…");
        new Thread(() -> {
            try {
                String hash = stage();
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    busy = false;
                    action.setEnabled(true);
                    detail.setText("安装包已校验。请在系统安装界面确认更新。");
                    Uri uri = new Uri.Builder().scheme("content").authority(getPackageName() + ".updates")
                            .appendPath(hash + ".apk").build();
                    Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(open); }
                    catch (android.content.ActivityNotFoundException ex) { detail.setText("未找到系统安装器。"); }
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    busy = false;
                    action.setEnabled(true);
                    detail.setText("准备失败。" + ex.getMessage());
                });
            }
        }, "micbar-offline-update").start();
    }

    private String stage() throws Exception {
        // A recreated activity may retry while the previous worker is finishing.
        synchronized (OfflineUpdateActivity.class) {
            PackageInfo installed = getPackageManager().getPackageInfo(IME, 0);
            if (!release.getProperty("from").equals(installed.versionName))
                throw new IOException("本机版本已变化，请重新打开本工具。");
            File base = new File(installed.applicationInfo.sourceDir);
            String edition;
            try (ZipFile zip = new ZipFile(base)) {
                edition = zip.getEntry("assets/qwen25-05.gguf") == null ? "Lite" : "Full";
            }
            String hash = release.getProperty(edition + ".sha256");
            long bytes = Long.parseLong(release.getProperty(edition + ".size"));
            File dir = directory();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建临时目录。");
            File part = new File(dir, "update.part"), patch = new File(dir, "update.patch");
            File target = new File(dir, hash + ".apk");
            // Only verified, atomically renamed files are reusable from this private directory.
            if (target.isFile() && target.length() == bytes) return hash;
            part.delete();
            patch.delete();
            try {
                try (InputStream in = getAssets().open(edition + ".mupd")) {
                    Files.copy(in, patch.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                if (dir.getUsableSpace() < bytes)
                    throw new IOException("请先留出至少 " + ((bytes + 999999) / 1000000) + " MB 临时空间。");
                ApkDelta.apply(base, patch, part, bytes, hash);
                android.system.Os.rename(part.getPath(), target.getPath());
                return hash;
            } finally {
                part.delete();
                patch.delete();
            }
        }
    }
}
