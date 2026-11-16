package org.micbar.ime;

import android.content.Context;
import com.sun.jna.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Rime's versioned C API. All runtime and session access is serialized on this worker. */
final class RimeEngine {
    static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static NativeLibrary library;
    private static Pointer api;
    private Pointer session;

    private enum Call {
        SETUP(0), INITIALIZE(2), CREATE(13), DESTROY(15), KEY(18), CLEAR(20),
        COMMIT(21), FREE_COMMIT(22), CONTEXT(23), FREE_CONTEXT(24), SCHEMA(34),
        INPUT(69), SELECT_ON_PAGE(74);
        final int slot;
        Call(int slot) { this.slot = slot; }
        Function fn() {
            int offset = (slot + 1) * Native.POINTER_SIZE;
            if (offset + Native.POINTER_SIZE > api.getInt(0) + 4) throw new IllegalStateException("Rime API");
            return Function.getFunction(api.getPointer(offset));
        }
    }

    @Structure.FieldOrder({"data_size","shared","user","name","code","version","app","modules","logLevel","log","prebuilt","staging"})
    public static class Traits extends Structure {
        public int data_size;
        public String shared, user, name, code, version, app;
        public Pointer modules;
        public int logLevel;
        public String log, prebuilt, staging;
    }
    @Structure.FieldOrder({"length","cursor","start","end","preedit"})
    public static class Composition extends Structure {
        public int length, cursor, start, end;
        public Pointer preedit;
    }
    @Structure.FieldOrder({"size","page","last","highlight","count","candidates","keys"})
    public static class Menu extends Structure {
        public int size,page,last,highlight,count;
        public Pointer candidates,keys;
    }
    @Structure.FieldOrder({"data_size","composition","menu","preview","labels"})
    public static class ContextData extends Structure {
        public int data_size;
        public Composition composition = new Composition();
        public Menu menu = new Menu();
        public Pointer preview,labels;
    }
    @Structure.FieldOrder({"data_size","text"})
    public static class Commit extends Structure {
        public int data_size;
        public Pointer text;
    }
    static final class Result {
        String committed = "", raw = "";
        boolean previous, next;
        final List<String> candidates = new ArrayList<>();
    }

    private static void initialize(Context context) throws IOException {
        if (api != null) return;
        File user = new File(context.getFilesDir(), "rime-user");
        File root = new File(context.getFilesDir(), "ice13-v2");
        File build = new File(root, "build");
        if ((!user.isDirectory() && !user.mkdirs()) || (!build.isDirectory() && !build.mkdirs()))
            throw new IOException("Cannot create Rime directories");
        for (String name : context.getAssets().list("rime13")) {
            File dest = new File(build, name);
            if (dest.isFile()) continue;
            File temp = new File(build, name + ".tmp");
            try (InputStream in = context.getAssets().open("rime13/" + name);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer,0,n);
            }
            if (!temp.renameTo(dest)) throw new IOException("Cannot install Rime data");
        }
        library = NativeLibrary.getInstance(new File(context.getApplicationInfo().nativeLibraryDir,"librime_jni.so").getAbsolutePath());
        api = library.getFunction("rime_get_api").invokePointer(new Object[0]);
        Traits t = new Traits();
        t.data_size = t.size()-4;
        t.shared = root.getPath();
        t.user = user.getPath();
        t.name = "MicBar"; t.code = "micbar"; t.version = "1.3"; t.app = "rime.micbar";
        t.logLevel = 2; t.log = ""; t.prebuilt = build.getPath(); t.staging = build.getPath();
        t.write();
        Call.SETUP.fn().invokeVoid(new Object[]{t.getPointer()});
        Call.INITIALIZE.fn().invokeVoid(new Object[]{t.getPointer()});
    }
    void open(Context context) throws IOException {
        if (session != null) return;
        initialize(context);
        session = Call.CREATE.fn().invokePointer(new Object[0]);
        if (session == null || Call.SCHEMA.fn().invokeInt(new Object[]{session,"rime_ice"}) == 0) {
            close();
            throw new IOException("Cannot open rime_ice");
        }
    }
    void close() {
        if (session != null) Call.DESTROY.fn().invokeInt(new Object[]{session});
        session = null;
    }
    void clear() { if (session != null) Call.CLEAR.fn().invokeVoid(new Object[]{session}); }
    Result key(int code) {
        Call.KEY.fn().invokeInt(new Object[]{session,code,0});
        return result();
    }
    Result select(int index) {
        Call.SELECT_ON_PAGE.fn().invokeInt(new Object[]{session,new NativeLong(index)});
        return result();
    }
    private Result result() {
        Result r = new Result();
        Commit commit = new Commit(); commit.data_size=commit.size()-4; commit.write();
        if (Call.COMMIT.fn().invokeInt(new Object[]{session,commit.getPointer()}) != 0) {
            try { commit.read(); r.committed = utf8(commit.text); }
            finally { Call.FREE_COMMIT.fn().invokeInt(new Object[]{commit.getPointer()}); }
        }
        r.raw = utf8(Call.INPUT.fn().invokePointer(new Object[]{session}));
        if (r.raw.isEmpty()) return r;
        ContextData ctx = new ContextData(); ctx.data_size=ctx.size()-4; ctx.write();
        if (Call.CONTEXT.fn().invokeInt(new Object[]{session,ctx.getPointer()}) != 0) {
            try {
                ctx.read();
                r.previous = ctx.menu.page > 0; r.next = ctx.menu.last == 0;
                for(int i=0;i<ctx.menu.count;i++)
                    r.candidates.add(utf8(ctx.menu.candidates.getPointer((long)i*3*Native.POINTER_SIZE)));
            } finally { Call.FREE_CONTEXT.fn().invokeInt(new Object[]{ctx.getPointer()}); }
        }
        return r;
    }
    private static String utf8(Pointer p) { return p == null ? "" : p.getString(0,"UTF-8"); }
}
