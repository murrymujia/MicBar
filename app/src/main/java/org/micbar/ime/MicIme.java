package org.micbar.ime;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.*;
import android.speech.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.ArrayList;

public final class MicIme extends InputMethodService {
    private enum State { IDLE, PREPARING, LISTENING, FINISHING }
    private State state = State.IDLE;
    private enum ToolbarReturn { IDLE, FILL_PENDING, SEND_PENDING, READY }
    private long commits;
    private boolean enteredText;
    private ToolbarReturn toolbarReturn = ToolbarReturn.IDLE;
    private long generation;
    private InputConnection target;
    private KeyboardSurface strip;
    private DraftPanel panel;
    private InputConnection draftTarget;
    private boolean autoFill, heard, automaticCommit, acceptAfterStop, retakeAfterStop, enterAfterStop;
    private boolean remote, obscured, markerInvisible;
    private VoiceShortcut pendingTab;
    private android.graphics.RectF caretBounds;
    private String recentInput="";
    private String undoText="",undoOriginal="";
    private int undoEnd=-1;
    private long caretVersion;
    private String aiOld="",aiNew="";
    private int aiEnd=-1;
    private int selectionEnd(InputConnection ic) {
        ExtractedText e=ic.getExtractedText(new ExtractedTextRequest(),0);
        return e!=null && e.selectionStart==e.selectionEnd && e.selectionEnd>=0 ? e.startOffset+e.selectionEnd : -1;
    }
    private boolean undoSpeech() {
        InputConnection ic=getCurrentInputConnection();
        if(ic==null || ic!=draftTarget || undoText.isEmpty() || undoEnd<0 || selectionEnd(ic)!=undoEnd) return false;
        CharSequence previous=ic.getTextBeforeCursor(undoText.length(),0);
        if(!undoText.contentEquals(previous==null?"":previous)) return false;
        if(undoOriginal.isEmpty()) {
            if(!ic.deleteSurroundingText(undoText.length(),0)) return false;
        } else {
            if(!ic.setSelection(undoEnd-undoText.length(),undoEnd) || !ic.commitText(undoOriginal,1)) return false;
        }
        setToolbarReturn(ToolbarReturn.IDLE);
        undoText="";recentInput="";schedulePreview();return true;
    }
    private final HandlerThread readerThread=new HandlerThread("EditorPreview");
    private Handler reader;
    private long previewVersion;
    private final Runnable readPreview=this::readEditorLine;
    private OfflineVoice offline;
    private OfflineVoice.Run run;
    private SpeechRecognizer speech;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> { if (state != State.IDLE) fail("设备语音服务未响应，可在轻声键盘里选用离线听写"); };

    @Override public void onCreate() { super.onCreate();readerThread.start();reader=new Handler(readerThread.getLooper()); offline = new OfflineVoice(this); }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public boolean onEvaluateInputViewShown() { return true; }
    @Override public View onCreateInputView() {
        if (panel != null) panel.dispose();
        draftTarget = null;
        strip = new KeyboardSurface(this);
        strip.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateCoverage());
        getWindow().getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));

        // Android 15 lays the IME window behind the tablet taskbar.
        if (Build.VERSION.SDK_INT >= 35) {
            WindowManager windows = (WindowManager) getSystemService(WINDOW_SERVICE);
            int navHeight = windows.getMaximumWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom;
            strip.setPadding(0,0,0,navHeight);
            strip.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets edges = insets.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
                v.setPadding(edges.left, 0, edges.right, Math.max(navHeight, edges.bottom));
                return insets;
            });
        }
        panel = new DraftPanel(this,new DraftPanel.Host() {
            public long caretVersion() {return MicIme.this.caretVersion;}
            public boolean rewriteSelection(String old,String edit) {
                if(old.isEmpty() || !old.equals(selection()) || !insert(edit)) return false;
                aiOld=old;aiNew=edit;aiEnd=selectionEnd(draftTarget);return true;
            }
            public boolean undoRewrite() {
                InputConnection ic=getCurrentInputConnection();
                if(ic==null || ic!=draftTarget || aiOld.isEmpty() || aiEnd<0 || selectionEnd(ic)!=aiEnd) return false;
                CharSequence previous=ic.getTextBeforeCursor(aiNew.length(),0);
                if(previous==null || !aiNew.contentEquals(previous)) return false;
                String restore=aiOld;
                if(!ic.setSelection(aiEnd-aiNew.length(),aiEnd)) return false;
                return insert(restore);
            }
            public String selection() {
                InputConnection ic=getCurrentInputConnection();
                if(ic==null || ic!=draftTarget) return "";
                CharSequence s=ic.getSelectedText(0);
                return s==null?"":s.toString();
            }
            public boolean undo() {return undoSpeech();}
            private boolean speechInsert;
            public boolean insertSpeech(String text,boolean mark) {
                InputConnection current=getCurrentInputConnection();
                if(current==null || current!=draftTarget || !isInputViewShown()) return false;
                CharSequence next=current.getTextAfterCursor(1,0);
                boolean end=next==null || next.length()==0 || next.charAt(0)=='\n';
                speechInsert=true;
                try {return insert(end && mark?MixedText.finishSentence(text):text);} finally {speechInsert=false;}
            }
            public boolean insert(String text) {
                InputConnection current=getCurrentInputConnection();
                if (current == null || current != draftTarget || !isInputViewShown()) {
                    toast("输入位置已改变，请重新选择输入框"); return false;
                }
                if (automaticCommit && !text.isEmpty() && text.charAt(0)<128 && Character.isLetterOrDigit(text.charAt(0))) {
                    CharSequence prev=current.getTextBeforeCursor(1,0);
                    if (prev!=null && prev.length()==1 && Character.isLetterOrDigit(prev.charAt(0))) text=" "+text;
                }
                setToolbarReturn(ToolbarReturn.IDLE);
                undoText="";aiOld="";aiNew="";
                String replaced=speechInsert && panel.permitsLearning()?selection():"";
                boolean ok=current.commitText(text,1);
                if (ok && !text.isEmpty()) {enteredText=true;commits++;}
                if(ok && speechInsert && panel.permitsLearning()) {
                    undoEnd=selectionEnd(current);
                    if(undoEnd>=0) {undoText=text;undoOriginal=replaced;}
                }
                if(ok && rememberEditor()) {
                    recentInput=tail(recentInput+text);
                    panel.showEditorLine(recentInput);schedulePreview();
                }
                if (!ok) toast("当前输入框未接受文字");
                return ok;
            }
            public void hideInput() { requestHideSelf(0); }
            public void toggleVoice() { tap(); }
            public void editingStarted() {
                if (toolbarReturn==ToolbarReturn.READY) setToolbarReturn(ToolbarReturn.IDLE);
            }
            public void tapToolbarReturn() { handleToolbarReturn(); }
            public void acceptVoice() { finishForEditor(false); }
            public void shortcut(VoiceShortcut action) {runShortcut(action);}
            public void editorKey(int code) { pressEditorKey(code); }
            public void enterAfterVoice() { finishForEditor(true); }
            public void retakeVoice() {
                if(state==State.LISTENING) { retakeAfterStop=true; tap(); }
            }
            public void positionToolbar(float fraction) {
                if(panel==null || panel.getParent()!=strip) return;
                strip.setBackgroundColor(panel.backdrop());
                schedulePreview();
                int pixels=panel.getVisibility()==View.VISIBLE?Math.round(fraction*panel.liftLimit()):0;
                FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)panel.getLayoutParams();
                if(lp.bottomMargin!=pixels) { lp.bottomMargin=pixels;panel.setLayoutParams(lp); }
                else strip.requestLayout();

            }
            public void picker() {
                ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
            }
        });
        panel.setVisibility(View.GONE);
        strip.addView(panel,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        refresh();
        return strip;
    }
    @Override public void onComputeInsets(Insets insets) {
        super.onComputeInsets(insets);
        if (strip == null || panel == null || !isInputViewShown()) return;
        int[] bar = new int[2], pos = new int[2];
        strip.getLocationInWindow(bar);
        View active = panel.touchSurface();
        active.getLocationInWindow(pos);
        // Compact controls float over the editor. Only system navigation needs space below it.
        int top = panel.expanded() ? bar[1] : bar[1] + strip.getHeight() - strip.getPaddingBottom();
        if (panel.floating()) {
            // The IME window can still be negotiating its new height during the first insets pass.
            top = Math.max(getWindow().getWindow().getDecorView().getHeight(), getResources().getDisplayMetrics().heightPixels);
        }
        insets.contentTopInsets=top;
        insets.visibleTopInsets=top;
        insets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION;
        insets.touchableRegion.set(pos[0],pos[1],pos[0]+active.getWidth(),pos[1]+active.getHeight());
    }
    private boolean rememberEditor() {
        return panel!=null && panel.permitsLearning() && (watchEditor() || remote || panel.shortLandscape())
                && prefs().getBoolean("editorPreview",true);
    }
    private boolean watchEditor() {
        return panel!=null && panel.canPreview() && (remote || obscured || panel.shortLandscape()) && draftTarget!=null
                && prefs().getBoolean("editorPreview",true);
    }
    private static String tail(String text) {
        int start=Math.max(0,text.length()-96);
        if(start<text.length() && Character.isLowSurrogate(text.charAt(start))) start++;
        return text.substring(start);
    }
    private void schedulePreview() {
        previewVersion++;
        main.removeCallbacks(readPreview);
        if(reader!=null) reader.removeCallbacksAndMessages(null);
        if(watchEditor()) main.postDelayed(readPreview,65);
        else if(panel!=null) panel.showEditorLine("");
    }
    private void readEditorLine() {
        final long version=previewVersion;
        final InputConnection ic=draftTarget;
        if(!watchEditor() || ic!=getCurrentInputConnection()) return;
        reader.post(()->{
            String value=null;
            try {
                // Keep slow or non-cooperating remote editors off the IME's UI thread.
                CharSequence before=ic.getTextBeforeCursor(96,0);
                if(before!=null && before.length()>0) value=tail(before.toString());
                if(before!=null && before.length()==0) {
                    CharSequence after=ic.getTextAfterCursor(48,0);
                    if(after!=null && after.length()>0) value="▏"+after;
                }
            } catch(RuntimeException unavailable) { /* The target may already have closed. */ }
            final String line=value;
            main.post(()->{
                if(version==previewVersion && ic==draftTarget && watchEditor())
                    panel.showEditorLine(line!=null?line:recentInput.isEmpty()?"":"本次键入 · "+recentInput);
            });
        });
    }
    @Override public void onUpdateSelection(int os,int oe,int ns,int ne,int cs,int ce) {
        super.onUpdateSelection(os,oe,ns,ne,cs,ce);
        if(ns!=os || ne!=oe) {caretVersion++;schedulePreview();}
    }
    @Override public void onUpdateCursorAnchorInfo(CursorAnchorInfo info) {
        super.onUpdateCursorAnchorInfo(info);
        if(draftTarget==null || info==null || panel==null) return;
        float[] xy={info.getInsertionMarkerHorizontal(),info.getInsertionMarkerTop(),
                info.getInsertionMarkerHorizontal(),info.getInsertionMarkerBottom()};
        info.getMatrix().mapPoints(xy);
        boolean valid=Float.isFinite(xy[0]) && Float.isFinite(xy[1]) && Float.isFinite(xy[2])
                && Float.isFinite(xy[3]) && xy[3]>xy[1];
        android.graphics.RectF caret=valid?new android.graphics.RectF(xy[0]-2,xy[1],xy[2]+2,xy[3]):null;
        caretBounds=caret;markerInvisible=(info.getInsertionMarkerFlags() & CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION)!=0;
        panel.trackCaret(caret);updateCoverage();
    }
    private void updateCoverage() {
        if(strip==null || panel==null || draftTarget==null) return;
        int[] loc=new int[2];strip.getLocationOnScreen(loc);
        boolean covered=caretBounds==null || markerInvisible || caretBounds.bottom>loc[1];
        if (panel.floating() && caretBounds!=null && !markerInvisible) {
            panel.getLocationOnScreen(loc);
            covered = caretBounds.intersects(loc[0], loc[1], loc[0]+panel.getWidth(), loc[1]+panel.getHeight());
        }
        if(obscured!=covered) {obscured=covered;schedulePreview();}
    }
    private void runShortcut(VoiceShortcut action) {
        if(draftTarget==null || draftTarget!=getCurrentInputConnection() || !isInputViewShown()) return;
        setToolbarReturn(ToolbarReturn.IDLE);
        if(action.code!=KeyEvent.KEYCODE_TAB) {pressEditorKey(action.code);return;}
        // Autocomplete must see the final transcript before it receives Tab.
        if(state!=State.IDLE) {
            pendingTab=action;enterAfterStop=false;retakeAfterStop=false;
            if(state==State.LISTENING) tap();
            return;
        }
        completeWithTab(action);
    }
    private void completeWithTab(VoiceShortcut action) {
        InputConnection ic=getCurrentInputConnection();
        if(ic==null || ic!=draftTarget || !isInputViewShown()) return;
        if(panel.hasText() && !panel.confirm(false)) return;
        if(ic!=getCurrentInputConnection() || ic!=draftTarget) return;
        if(action==VoiceShortcut.TAB) {pressEditorKey(KeyEvent.KEYCODE_TAB);return;}
        undoText="";aiOld="";recentInput="";schedulePreview();
        long now=SystemClock.uptimeMillis();
        int shift=KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        shortcutEvent(ic,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SHIFT_LEFT,shift);
        shortcutEvent(ic,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_TAB,shift);
        shortcutEvent(ic,now,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_TAB,shift);
        shortcutEvent(ic,now,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SHIFT_LEFT,0);
    }
    private void shortcutEvent(InputConnection ic,long down,int action,int key,int meta) {
        ic.sendKeyEvent(new KeyEvent(down,SystemClock.uptimeMillis(),action,key,0,meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD,0,KeyEvent.FLAG_SOFT_KEYBOARD,InputDevice.SOURCE_KEYBOARD));
    }
    private void setToolbarReturn(ToolbarReturn next) {
        toolbarReturn = next;
        if (panel != null) panel.showSendAction(next == ToolbarReturn.FILL_PENDING || next == ToolbarReturn.READY);
    }

    private void handleToolbarReturn() {
        if (draftTarget == null || draftTarget != getCurrentInputConnection() || !isInputViewShown()) return;
        if (state != State.IDLE) {
            if (toolbarReturn == ToolbarReturn.SEND_PENDING) return;
            boolean secondTap = toolbarReturn == ToolbarReturn.FILL_PENDING;
            finishForEditor(false);
            setToolbarReturn(secondTap ? ToolbarReturn.SEND_PENDING : ToolbarReturn.FILL_PENDING);
        } else if (panel.hasText()) {
            // Direct-mode Pinyin commits its candidate while finishing composition.
            long before=commits;
            if (panel.insertDraft() || commits!=before) setToolbarReturn(ToolbarReturn.READY);
        } else if (toolbarReturn == ToolbarReturn.READY) {
            sendFromToolbar();
        } else if (enteredText || hasEditorText()) {
            setToolbarReturn(ToolbarReturn.READY);
        }
    }
    private boolean hasEditorText() {
        InputConnection ic = getCurrentInputConnection();
        return ic != null && (!android.text.TextUtils.isEmpty(ic.getTextBeforeCursor(1,0))
                || !android.text.TextUtils.isEmpty(ic.getTextAfterCursor(1,0))
                || !android.text.TextUtils.isEmpty(ic.getSelectedText(0)));
    }

    private void sendFromToolbar() {
        enteredText=false;
        setToolbarReturn(ToolbarReturn.IDLE);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || ic != draftTarget || !isInputViewShown()) return;
        EditorInfo editor = getCurrentInputEditorInfo();
        if (editor != null && (editor.imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEND
                && ic.performEditorAction(EditorInfo.IME_ACTION_SEND)) return;
        // Remote editors forward the actual key pair to the focused desktop app.
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
    }

    private void pressEditorKey(int code) {
        if (code==KeyEvent.KEYCODE_DEL || code==KeyEvent.KEYCODE_ENTER) enteredText=false;
        setToolbarReturn(ToolbarReturn.IDLE);
        InputConnection current=getCurrentInputConnection();
        if(current==null || current!=draftTarget || !isInputViewShown()) return;
        if(rememberEditor()) {
            if(code==KeyEvent.KEYCODE_DEL && !recentInput.isEmpty())
                recentInput=recentInput.substring(0,recentInput.offsetByCodePoints(recentInput.length(),-1));
            else recentInput="";
            panel.showEditorLine(recentInput);schedulePreview();
        }
        undoText="";
        if(code==KeyEvent.KEYCODE_ENTER && sendDefaultEditorAction(true)) return;
        sendDownUpKeyEvents(code);
    }
    @Override public void onStartInputView(EditorInfo info,boolean restarting) {
        super.onStartInputView(info,restarting);
        if(panel!=null && (!restarting || draftTarget!=getCurrentInputConnection())) {
            panel.startEditor(info);
            String app=info.packageName;
            remote="com.oplus.remotecontrol".equals(app) || "com.oplus.linker".equals(app)
                    || "com.oplus.padconnect".equals(app);
            obscured=true;recentInput="";
            openPanel(!remote);
            InputConnection ic=getCurrentInputConnection();
            if(ic!=null) {
                int mode=InputConnection.CURSOR_UPDATE_IMMEDIATE | InputConnection.CURSOR_UPDATE_MONITOR;
                if(Build.VERSION.SDK_INT>=33) ic.requestCursorUpdates(mode,InputConnection.CURSOR_UPDATE_FILTER_INSERTION_MARKER);
                else ic.requestCursorUpdates(mode);
            }
        }
    }
    private void openPanel(boolean keyboard) {
        InputConnection current=getCurrentInputConnection();
        if (current==null) return;
        if (draftTarget!=null && draftTarget!=current) panel.reset();
        draftTarget=current;
        panel.setVisibility(View.VISIBLE);
        panel.showKeyboard(keyboard); strip.requestLayout();
    }
    private void endEditor() {
        enteredText=false;
        aiOld="";aiNew="";caretVersion++;undoText="";undoOriginal="";undoEnd=-1;cancel(); draftTarget=null;remote=false;obscured=false;caretBounds=null;markerInvisible=false;recentInput="";schedulePreview();
        if(panel!=null) { panel.reset();panel.setVisibility(View.GONE); }
    }
    @Override public void onStartInput(EditorInfo info, boolean restarting) { super.onStartInput(info,restarting); if(!restarting || draftTarget!=getCurrentInputConnection()) endEditor(); }
    @Override public void onFinishInputView(boolean finishing) { endEditor(); super.onFinishInputView(finishing); }
    @Override public void onFinishInput() { endEditor(); super.onFinishInput(); }
    @Override public void onWindowHidden() { endEditor(); super.onWindowHidden(); }
    @Override public void onDestroy() { endEditor(); if(panel!=null) panel.dispose(); offline.release(); readerThread.quitSafely(); super.onDestroy(); }
    private SharedPreferences prefs() { return getSharedPreferences("micbar", MODE_PRIVATE); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void refresh() {
        if (panel!=null && state==State.LISTENING) panel.voiceState("结束",true);
        if (panel!=null && state==State.FINISHING) panel.voiceState("识别中",false);
    }
    private void cancel() {
        setToolbarReturn(ToolbarReturn.IDLE);
        pendingTab=null;acceptAfterStop=false;retakeAfterStop=false;enterAfterStop=false;generation++;
        main.removeCallbacks(timeout);
        if (run != null) { run.cancelled = true; run = null; }
        if (speech != null) { speech.destroy(); speech = null; }
        target = null;
        if (panel!=null) panel.endVoice();
        state = State.IDLE;
        refresh();
    }
    private void fail(String message) { cancel(); toast(message); }
    private void tap() {
        if (state == State.PREPARING) { cancel(); return; }
        if (state == State.FINISHING) return;
        if (state == State.LISTENING) {
            state = State.FINISHING; refresh();
            if (run != null) run.stop = true;
            if (speech != null) { speech.stopListening(); guardTimeout(); }
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("请打开轻声键盘 App，允许使用麦克风"); return;
        }
        if (getCurrentInputConnection() == null) { toast("请先点一下要输入文字的位置"); return; }
        setToolbarReturn(ToolbarReturn.IDLE);
        target = getCurrentInputConnection();
        autoFill = prefs().getBoolean("directInsert",false) && !panel.hasText();
        heard=false;
        openPanel(false);
        panel.beginVoice();
        final long id = ++generation;
        state = State.PREPARING; refresh();
        if (prefs().getBoolean("systemForMixed", false)) startSystem(id);
        else run = offline.start(new OfflineVoice.Listener() {
            public void listening() { main.post(() -> markListening(id)); }
            public void phrase(String words) { main.post(() -> preview(id,words,false)); }
            public void finished() { main.post(() -> finishVoice(id)); }
            public void failed(String msg) { main.post(() -> { if (id == generation) fail(msg); }); }
        });
    }
    private boolean activeTarget(long id) {
        if (id!=generation) return false;
        if (target!=null && target==getCurrentInputConnection() && isInputViewShown()) return true;
        endEditor();
        return false;
    }
    private void preview(long id,String words,boolean system) {
        if (!activeTarget(id) || words==null || words.trim().isEmpty()) return;
        String text=MixedText.dictation(words,prefs().getBoolean("termSpelling",true),
                prefs().getBoolean("keepDictatedPeriods",false));
        if(panel.permitsLearning()) text=panel.personalSpeech(text);
        if(text.isEmpty()) return;
        if (system) panel.systemHypothesis(text); else panel.phrase(text);
        heard=true;
    }
    private void finishForEditor(boolean enter) {
        if(state==State.IDLE) return;
        setToolbarReturn(ToolbarReturn.IDLE);
        pendingTab=null;retakeAfterStop=false;
        acceptAfterStop=true;enterAfterStop=enter;
        if(state==State.LISTENING) tap();
    }
    private void finishVoice(long id) {
        if (!activeTarget(id)) return;
        ToolbarReturn requested = toolbarReturn;
        VoiceShortcut tab=pendingTab;
        boolean retake=retakeAfterStop, enter=enterAfterStop;
        if(heard) panel.rememberSpokenEnding();
        boolean submit=acceptAfterStop || (!retake && autoFill && heard && !panel.manualVoiceEdit());
        cancel();
        if(tab!=null) {completeWithTab(tab);return;}
        if (retake && panel.restoreRetake()) { tap(); return; }
        if (submit && panel.hasText()) {
            automaticCommit=autoFill && !panel.manualVoiceEdit();
            try {
                if (panel.insertDraft()) {
                    if (requested == ToolbarReturn.SEND_PENDING) sendFromToolbar();
                    else if (requested == ToolbarReturn.FILL_PENDING) setToolbarReturn(ToolbarReturn.READY);
                    else if (enter) pressEditorKey(KeyEvent.KEYCODE_ENTER);
                }
            } finally { automaticCommit=false; }
        }
        else if (!heard) {
            toast("没有听清，可以重新听写");
        }
    }
    private void systemResults(long id,Bundle data) {
        if (data==null) return;
        ArrayList<String> list=data.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if(list!=null && !list.isEmpty()) preview(id,list.get(0),true);
    }
    private void markListening(long id) {
        if (id != generation || state != State.PREPARING) return;
        main.removeCallbacks(timeout);
        state = State.LISTENING; refresh();
        if(pendingTab!=null || acceptAfterStop) tap();
    }
    private void guardTimeout() { main.removeCallbacks(timeout); main.postDelayed(timeout,15000); }
    private void startSystem(long id) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { fail("此设备没有可调用的语音服务，请在轻声键盘中选离线听写"); return; }
        try {
            speech = SpeechRecognizer.createSpeechRecognizer(this);
            speech.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { markListening(id); }
                public void onBeginningOfSpeech() { markListening(id); }
                public void onRmsChanged(float v) { }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() {
                    if (id == generation) { state = State.FINISHING; refresh(); guardTimeout(); }
                }
                public void onError(int error) {
                    if (id == generation) fail(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ? "没有听清，请再试一次" : "设备听写失败（" + error + "），可改用离线听写");
                }
                public void onResults(Bundle results) {
                    systemResults(id,results); finishVoice(id);
                }
                public void onPartialResults(Bundle b) { systemResults(id,b); }
                public void onEvent(int e, Bundle b) { }
            });
            Intent options = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            if (Build.VERSION.SDK_INT >= 34) {
                ArrayList<String> languages = new ArrayList<>(java.util.Arrays.asList("zh-CN", "en-US"));
                options.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED);
                options.putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, languages);
            }
            guardTimeout();
            speech.startListening(options);
        } catch (RuntimeException ex) { fail("无法打开设备语音服务，可在轻声键盘中选离线听写"); }
    }

}
