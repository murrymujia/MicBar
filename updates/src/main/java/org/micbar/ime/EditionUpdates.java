package org.micbar.ime;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.widget.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

final class EditionUpdates {
    private static final String PROJECT="https://github.com/murrymujia/MicBar";
    private static final String API="https://api.github.com/repos/murrymujia/MicBar/releases/latest";
    private static final AtomicBoolean preparing=new AtomicBoolean();
    private final Activity activity;
    EditionUpdates(Activity activity){this.activity=activity;}
    static String editionName(boolean full){return full?"完整版":"轻量版";}
    private static String flavor(boolean full){return full?"Full":"Lite";}
    private static String size(long bytes){
        return bytes<1000000?String.format(Locale.ROOT,"%.0f KB",bytes/1000d):String.format(Locale.ROOT,"%.1f MB",bytes/1e6);
    }

    static final class Asset {
        final String name,sha;
        final long bytes;
        Asset(JSONObject asset)throws JSONException {
            name=asset.getString("name");bytes=asset.getLong("size");
            sha=asset.optString("digest").replaceFirst("^sha256:","");
        }
    }
    static final class Release {
        final String version;
        final java.util.Map<String,Asset> assets=new java.util.HashMap<>();
        Release(JSONObject json)throws Exception {
            String tag=json.getString("tag_name");
            if(!tag.matches("v[0-9]{1,5}([.][0-9]{1,5}){1,2}") || json.optBoolean("draft") || json.optBoolean("prerelease"))
                throw new IOException("版本无效");
            version=tag.substring(1);
            JSONArray list=json.getJSONArray("assets");
            for(int i=0;i<list.length();i++){
                JSONObject item=list.getJSONObject(i);
                if(!"uploaded".equals(item.optString("state"))) continue;
                Asset a=new Asset(item);if(a.bytes>0)assets.put(a.name,a);
            }
            if(apk(true)==null || apk(false)==null)throw new IOException("版本发布中");
        }
        Asset apk(boolean full){return assets.get("MicBar-"+flavor(full)+"-"+version+".apk");}
        Asset patch(boolean full,String from){return assets.get("MicBar-"+flavor(full)+"-"+from+"-to-"+version+".mupd");}
        String url(Asset asset){return PROJECT+"/releases/download/v"+version+"/"+asset.name;}
        int compare(String local) {
            String[] a=version.split("[.]"),b=local.split("[.]");
            for(int i=0;i<Math.max(a.length,b.length);i++){
                int d=Integer.compare(i<a.length?Integer.parseInt(a[i]):0,i<b.length?Integer.parseInt(b[i]):0);
                if(d!=0)return d;
            }
            return 0;
        }
    }

    private static HttpsURLConnection connect(String url)throws IOException {
        HttpsURLConnection c=(HttpsURLConnection)new URL(url).openConnection();
        c.setUseCaches(false);c.setConnectTimeout(12000);c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent","MicBar-Update");return c;
    }
    private static Release fetch()throws Exception {
        HttpsURLConnection c=connect(API);
        try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;
            while((n=in.read(b))!=-1){if(out.size()+n>512*1024)throw new IOException();out.write(b,0,n);}
            return new Release(new JSONObject(out.toString(StandardCharsets.UTF_8.name())));
        }finally{c.disconnect();}
    }
    private boolean visible(){return !activity.isDestroyed() && !activity.isFinishing();}
    private void alert(String message){
        new AlertDialog.Builder(activity).setMessage(message).setPositiveButton("打开下载页",(d,w)->browse(PROJECT+"/releases/latest")).setNegativeButton("关闭",null).show();
    }
    void check(Button button){
        CharSequence label=button.getText();button.setEnabled(false);button.setText("正在检查更新…");
        new Thread(()->{
            Runnable done;
            try{
                PackageInfo local=activity.getPackageManager().getPackageInfo("org.micbar.ime",0);
                File base=new File(local.applicationInfo.sourceDir);
                boolean full;
                try(ZipFile zip=new ZipFile(base)){full=zip.getEntry("assets/qwen25-05.gguf")!=null;}
                Release r=fetch();done=()->show(r,base,local.versionName,full);
            }catch(android.content.pm.PackageManager.NameNotFoundException e){done=()->alert("设备上尚未安装轻声键盘。");}
            catch(Exception e){done=()->alert("暂时无法检查更新。可以稍后重试或打开 GitHub 下载页。");}
            final Runnable ui=done;
            activity.runOnUiThread(()->{if(visible()){button.setText(label);button.setEnabled(true);ui.run();}});
        },"micbar-update-check").start();
    }
    private void show(Release r,File base,String local,boolean full) {
        int comparison=r.compare(local);
        AlertDialog.Builder box=new AlertDialog.Builder(activity).setNegativeButton("关闭",null);
        Asset apk=r.apk(full),other=r.apk(!full),patch=r.patch(full,local);
        boolean delta=patch!=null && patch.bytes<apk.bytes && patch.sha.matches("[a-f0-9]{64}") && apk.sha.matches("[a-f0-9]{64}");
        if(comparison<0){box.setMessage("本机版本高于最新公开版本。").show();return;}
        String change="切换到"+editionName(!full)+"（"+size(other.bytes)+"）";
        String update=delta?"增量更新（"+size(patch.bytes)+"）":"下载安装包（"+size(apk.bytes)+"）";
        box.setTitle((comparison==0?"已是最新版本 · ":"发现新版本 · ")+r.version)
            .setItems(comparison==0?new String[]{change}:new String[]{update,change},(d,i)->{
                if(comparison==0 || i==1)browse(r.url(other));
                else if(delta)prepare(base,r,patch,apk);
                else browse(r.url(apk));
            }).show();
    }
    private void prepare(File base,Release release,Asset patch,Asset apk) {
        if(!preparing.compareAndSet(false,true)){Toast.makeText(activity,"已有更新正在准备",Toast.LENGTH_SHORT).show();return;}
        ProgressDialog progress=new ProgressDialog(activity);progress.setMessage("正在下载差分并在本机还原安装包…");progress.setCancelable(false);progress.show();
        new Thread(()->{
            File dir=new File(activity.getCacheDir(),"updates");
            File output=new File(dir,apk.sha+".apk"),part=new File(dir,"update.part"),recipe=new File(dir,"update.patch");
            Runnable result;
            try {
                if(!dir.isDirectory() && !dir.mkdirs())throw new IOException("无法创建更新目录");
                File[] stale=dir.listFiles();
                if(stale!=null) for(File f:stale) if(!f.equals(output)) f.delete();
                if(!output.isFile()){
                    if(dir.getUsableSpace()<apk.bytes+patch.bytes)throw new IOException("空间不足，需临时留出 "+size(apk.bytes+patch.bytes));
                    download(release.url(patch),patch,recipe);
                    ApkDelta.apply(base,recipe,part,apk.bytes,apk.sha);
                    android.system.Os.rename(part.getPath(),output.getPath());
                }
                result=()->install(apk.sha);
            }catch(Exception e){String message=e.getMessage();result=()->alert("增量更新未完成。"+(message==null?"请重试。":message));}
            finally {recipe.delete();part.delete();preparing.set(false);}
            final Runnable finish=result;
            activity.runOnUiThread(()->{if(visible()){progress.dismiss();finish.run();}});
        },"micbar-update-prepare").start();
    }
    private static void download(String url,Asset asset,File dest)throws Exception {
        HttpsURLConnection c=connect(url);MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(dest)){
            byte[] b=new byte[65536];long total=0;int n;
            while((n=in.read(b))!=-1){
                total+=n;if(total>asset.bytes)throw new IOException("下载大小异常");
                digest.update(b,0,n);out.write(b,0,n);
            }
            if(total!=asset.bytes || !asset.sha.equals(ApkDelta.hex(digest.digest())))throw new IOException("差分校验失败");
        }finally{c.disconnect();}
    }
    private void install(String sha) {
        Uri uri=new Uri.Builder().scheme("content").authority(activity.getPackageName()+".updates").appendPath(sha+".apk").build();
        Intent open=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{activity.startActivity(open);}
        catch(ActivityNotFoundException ex){alert("未找到系统安装器。");}
    }
    private void browse(String url){
        try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(ActivityNotFoundException e){Toast.makeText(activity,"请先安装浏览器",Toast.LENGTH_LONG).show();}
    }
}
