package org.micbar.ime;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

public final class UpdateFile extends ContentProvider {
    public boolean onCreate() {return true;}
    public String getType(Uri uri) {return "application/vnd.android.package-archive";}
    private File resolve(Uri uri) {
        String path=uri.getPath();
        if(path==null || !path.matches("/[0-9a-f]{64}\\.apk")) throw new IllegalArgumentException();
        return new File(new File(getContext().getCacheDir(),"updates"),path.substring(1));
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException();
        return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri uri,String[] columns,String selection,String[] args,String sort){
        File f=resolve(uri);
        if(columns==null) columns=new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE};
        MatrixCursor cursor=new MatrixCursor(columns); MatrixCursor.RowBuilder row=cursor.newRow();
        for(String c:columns) row.add(OpenableColumns.SIZE.equals(c)?f.length():OpenableColumns.DISPLAY_NAME.equals(c)?"MicBar.apk":null);
        return cursor;
    }
    public int delete(Uri uri,String s,String[] a){throw new UnsupportedOperationException();}
    public int update(Uri uri,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException();}
}
