package org.micbar.ime;

import java.io.*;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

final class ApkDelta {
    static String hex(byte[] digest) {
        StringBuilder value=new StringBuilder();
        for(byte b:digest) value.append(String.format(Locale.ROOT,"%02x",b&255));
        return value.toString();
    }

    static void apply(File base, File patch, File result, long expectedSize, String expectedHash) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[256*1024];
        try (RandomAccessFile original=new RandomAccessFile(base,"r");
             DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(patch))));
             OutputStream out=new DigestOutputStream(new BufferedOutputStream(new FileOutputStream(result)),sha)) {
            if(in.readLong()!=0x4d49434449464631L || in.readLong()!=expectedSize) throw new IOException("差分文件不匹配");
            long remaining=expectedSize;
            for(int op=in.readUnsignedByte();op!='E';op=in.readUnsignedByte()) {
                if(op!='C' && op!='L') throw new IOException("无效的差分指令");
                long length=in.readLong();
                if(length<=0 || length>remaining) throw new IOException("差分长度异常");
                if(op=='C') {
                    long offset=in.readLong();
                    if(offset<0 || offset>original.length()-length) throw new IOException("原安装包不匹配");
                    original.seek(offset);
                }
                remaining-=length;
                while(length>0) {
                    int n=(int)Math.min(buffer.length,length);
                    if(op=='C') original.readFully(buffer,0,n); else in.readFully(buffer,0,n);
                    out.write(buffer,0,n);length-=n;
                }
            }
            if(remaining!=0 || in.read()!=-1) throw new IOException("差分文件不完整");
        }
        if(!hex(sha.digest()).equals(expectedHash)) throw new IOException("重建的 APK 与官方发布文件不一致");
    }
}
