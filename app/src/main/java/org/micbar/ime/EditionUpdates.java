package org.micbar.ime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.Toast;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Checks only on demand. APK downloads and installation belong to the user's browser and Android. */
final class EditionUpdates {
    static final String PROJECT = "https://github.com/murrymujia/MicBar";
    private static final String API = "https://api.github.com/repos/murrymujia/MicBar/releases/latest";
    private final Activity activity;

    EditionUpdates(Activity activity) { this.activity = activity; }

    static String editionName(boolean full) { return full ? "完整版" : "轻量版"; }

    static final class Release {
        final String version;
        final long liteBytes;
        final long fullBytes;

        Release(JSONObject json) throws Exception {
            String tag = json.getString("tag_name");
            if (!tag.matches("v[0-9]{1,5}([.][0-9]{1,5}){1,2}") ||
                    json.optBoolean("draft") || json.optBoolean("prerelease"))
                throw new IOException("无效的发布版本");
            version = tag.substring(1);
            JSONArray assets = json.getJSONArray("assets");
            liteBytes = findSize(assets, false);
            fullBytes = findSize(assets, true);
        }

        String fileName(boolean full) {
            return "MicBar-" + (full ? "Full-" : "Lite-") + version + ".apk";
        }

        String download(boolean full) {
            return PROJECT + "/releases/download/v" + version + "/" + fileName(full);
        }

        private long findSize(JSONArray assets, boolean full) throws Exception {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (fileName(full).equals(asset.getString("name")) &&
                        "uploaded".equals(asset.getString("state")) && asset.getLong("size") > 0)
                    return asset.getLong("size");
            }
            throw new IOException("发布包尚未就绪");
        }

        int compareToInstalled() {
            String[] remote = version.split("[.]");
            String[] local = BuildConfig.VERSION_NAME.split("[.]");
            for (int i = 0; i < Math.max(remote.length, local.length); i++) {
                int a = i < remote.length ? Integer.parseInt(remote[i]) : 0;
                int b = i < local.length ? Integer.parseInt(local[i]) : 0;
                if (a != b) return Integer.compare(a, b);
            }
            return 0;
        }

        String option(boolean full, String action) {
            long bytes = full ? fullBytes : liteBytes;
            return String.format(Locale.ROOT, "%s%s（%.0f MB）", action, editionName(full), bytes / 1000000d);
        }
    }

    private static Release fetch() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection)new URL(API).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "MicBar-Update");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) != -1) {
                if (json.length() + n > 262144) throw new IOException("发布信息过长");
                json.append(buffer, 0, n);
            }
            return new Release(new JSONObject(json.toString()));
        } finally {
            connection.disconnect();
        }
    }

    void check(Button button) {
        CharSequence original = button.getText();
        button.setEnabled(false);
        button.setText("正在检查 GitHub 更新…");
        new Thread(() -> {
            Release result = null;
            try { result = fetch(); } catch (Exception ignored) { }
            final Release release = result;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                button.setText(original);
                button.setEnabled(true);
                show(release);
            });
        }, "micbar-release-check").start();
    }

    private void show(Release release) {
        AlertDialog.Builder box = new AlertDialog.Builder(activity).setNegativeButton("关闭", null);
        if (release == null) {
            box.setTitle("暂时无法检查更新").setMessage("请稍后重试，也可以用浏览器打开 GitHub 下载页。")
                    .setPositiveButton("打开下载页", (d, w) -> browse(PROJECT + "/releases/latest"));
        } else {
            int comparison = release.compareToInstalled();
            boolean current = BuildConfig.HAS_WRITER;
            if (comparison < 0) {
                box.setTitle("已安装较新版本").setMessage("GitHub 当前公开版本为 " + release.version + "。");
            } else {
                box.setTitle(comparison == 0 ? "已是最新版本 · " + release.version : "发现新版本 · " + release.version);
                String other = release.option(!current, "切换到");
                String[] options = comparison == 0 ? new String[]{other} :
                        new String[]{release.option(current, "更新"), other};
                box.setItems(options, (d, index) -> browse(release.download(comparison > 0 && index == 0 ? current : !current)));
            }
        }
        box.show();
    }

    private void browse(String url) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { Toast.makeText(activity, "请先安装浏览器以下载更新", Toast.LENGTH_LONG).show(); }
    }
}
