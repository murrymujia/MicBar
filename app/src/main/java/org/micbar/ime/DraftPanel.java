package org.micbar.ime;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.text.Editable;
import static android.text.InputType.*;
import java.util.Locale;

/** Direct typing and a separately editable dictation draft share one keyboard. */
final class DraftPanel extends LinearLayout {
    interface Host { boolean insert(String text); boolean insertSpeech(String text,boolean mark); boolean undo(); String selection(); long caretVersion(); boolean rewriteSelection(String old,String edit); boolean undoRewrite(); void hideInput(); void picker(); void toggleVoice(); void acceptVoice(); void tapToolbarReturn(); void editingStarted(); void enterAfterVoice(); void editorKey(int code); void shortcut(VoiceShortcut action); void retakeVoice(); void positionToolbar(float heightFraction); }
    private final Host host;
    private final PersonalWords personal;
    private final DraftLearning learning=new DraftLearning();
    private boolean rewriting, spokenReplacement;
    private final TypingSuggestions suggestions=new TypingSuggestions();
    private final WritingMenu writing;
    private String transcript="", identifier="";
    private boolean fromVoice;
    private String aiBefore="",aiAfter="";
    private long draftVersion;
    boolean permitsLearning() {return previewAllowed;}
    String personalSpeech(String text) {return MixedText.format(personal.speech(text),false);}

    private final EditText draft;
    private final ToolButton edit, submit, hide, dictate, delete, settings, enter;
    private final TextView toolbarSpace;
    private final LinearLayout shortcutRow;
    private boolean peekChosen;
    private boolean showPeek() {
        return peekChosen?peek:peek || (options.shortLandscape() && previewAllowed && draft.length()>0
                && options.prefs.getBoolean("editorPreview",true));
    }
    private void bindShortcuts() {
        shortcutRow.removeAllViews();
        for(int i=0;i<VoiceShortcut.count(options.prefs);i++) {
            VoiceShortcut action=VoiceShortcut.at(options.prefs,i);
            TextView key=labelKey(action.cap,14);key.setContentDescription("快捷键 "+action.label);
            key.setTooltipText(action.label);key.setBackground(options.face(surface,14));
            key.setOnClickListener(v->{
                feedback(v);
                if(action.computer()) host.shortcut(action);
                else {finishComposition();replaceSelection(action.text);}
            });
            shortcutRow.addView(key,new LayoutParams(dp(48),-1));
        }
    }
    private boolean dictatedTail, proseField, previewAllowed;
    private android.graphics.RectF editorCaret;
    private boolean drafting, secureField;
    private final LinearLayout bar;
    private final FrameLayout dragHandle;
    private final View grip;
    private boolean peek,dragging;
    private float xFraction=.96f,heightFraction=0f, dragX,dragY,originalX,originalLift;
    private android.content.SharedPreferences positions() { return getContext().getSharedPreferences("micbar",Context.MODE_PRIVATE); }
    private final KeyboardOptions options;
    private boolean dark;
    private int surface, canvas, ink, muted, blue, accent;
    // Each token is one key. Paired Chinese ellipses and dashes each insert in a single tap.
    private static final String[] EN_SYMBOLS = {"1 2 3 4 5 6 7 8 9 0", "@ # $ _ & - + ( )", "* \" ' : ; ! ? /"};
    private static final String[] ZH_SYMBOLS = {"1 2 3 4 5 6 7 8 9 0", "@ # $ _ & - + （ ）", "、 “ ” ： ； ！ ？ /"};
    private static final String[] EN_MORE = {"~ ` | \\ ^ % =", "[ ] { } < >", "£ € ¥ • … —"};
    private static final String[] ZH_MORE = {"【 】 《 》 「 」 『 』", "‘ ’ [ ] { } < >", "…… —— · ￥ % * = \\"};
    private static final String[] EN_SHORTCUTS = {",", "?", "!", "'", "\"", ":", ";", "-", "/", "@"};
    private static final String[] ZH_SHORTCUTS = {"，", "？", "！", "、", "……", "：", "；", "“", "”", "——"};
    private PopupWindow punctuationPopup;
    private boolean preferencesOpen, caps, moreSymbols, showTypingTools;
    private String editorSuffix="";
    private boolean portrait() {
        return getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT;
    }
    private boolean portraitChinese(){
        return portrait() && expanded() && !preferencesOpen && !english && !symbols && !voiceActive && !fromVoice;
    }
    boolean needsFollowingContext(){return portraitChinese() && proseField && previewAllowed && !drafting;}
    void editorSuffix(String context){
        String suffix=context.substring(Math.max(0,context.length()-12));
        if(!suffix.equals(editorSuffix)){editorSuffix=suffix;renderCandidates();}
    }
    private java.util.List<String> nextWords(){
        if(!ready || !portraitChinese() || !previewAllowed || !proseField || sendAction)
            return java.util.Collections.emptyList();
        if(!drafting)return suggestions.following(editorSuffix);
        int end=draft.getSelectionEnd();
        if(end<=0 || end!=draft.getSelectionStart())return java.util.Collections.emptyList();
        return suggestions.following(draft.getText().subSequence(Math.max(0,end-12),end).toString());
    }
    private long lastShiftTap;
    private View preferences;
    private final HorizontalScrollView candidateScroll;
    private final boolean inlineCandidates;
    private final android.icu.text.BreakIterator characters=android.icu.text.BreakIterator.getCharacterInstance();
    private final Object replaceRange=new Object(), retakeRange=new Object();
    private String replaceOriginal="",retakeOriginal="";
    private boolean listening, finalizing;
    private final android.icu.text.BreakIterator words=android.icu.text.BreakIterator.getWordInstance(Locale.CHINESE);
    // A right-gravity anchor follows edits before the next dictated phrase.
    private final Object voiceAnchor = new Object();
    private final Object provisionalStart = new Object();
    private String previousHypothesis = "";
    private boolean voiceActive, changing, edited;
    private final LinearLayout keyboard, candidates, keys;
    private final RimeEngine engine = new RimeEngine();
    private RimeEngine.Result composition = new RimeEngine.Result();
    private boolean ready, opening, closed, english, shift, symbols;
    private String error = "";
    DraftPanel(Context context, Host host) {
        super(context); this.host=host;
        personal=new PersonalWords(context);
        options=new KeyboardOptions(context,this::reloadPreferences);
        readColors();
        setOrientation(VERTICAL);setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(4),dp(4),dp(4),dp(3));setClipToPadding(false);
        bar = new LinearLayout(context);bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4),0,dp(4),0);bar.setBackground(shape(surface,28));
        bar.setElevation(dp(1));addView(bar,new LayoutParams(-1,dp(50)));
        xFraction=positions().getFloat("toolX",.96f);heightFraction=positions().getFloat("toolHeight",0);
        dictate=tool(bar,() -> {
            if (voiceActive && listening && wantsReplacement()) {
                rememberRetake();host.retakeVoice();
            } else host.toggleVoice();
        });
        dictate.setOnTouchListener(this::dragMic);
        writing=new WritingMenu(context,dictate,new WritingMenu.Editor() {
            public long position() {return drafting?draftVersion:host.caretVersion();}
            public boolean undo() {
                if(!drafting) return host.undoRewrite();
                if(aiBefore.isEmpty() || !aiAfter.contentEquals(draft.getText())) return false;
                rewriting=true;try {draft.setText(aiBefore);draft.setSelection(draft.length());} finally {rewriting=false;}aiBefore="";aiAfter="";return true;
            }
            public String source() {return aiSource();}
            public boolean apply(String source,String edit) {
                if(!source.equals(aiSource())) return false;
                if(drafting) {
                    aiBefore=draft.getText().toString();
                    if(!selected()) draft.selectAll();
                    rewriting=true;try {replaceSelection(edit);} finally {rewriting=false;}
                    aiAfter=draft.getText().toString();peek=true;applyColors();return true;
                }
                return host.rewriteSelection(source,edit);
            }
            public String[] correction() {return proposedWord();}
            public boolean save(String[] pair) {return personal.remember(pair[0],pair[1]);}
        });
        dictate.setOnLongClickListener(v->{
            if(!previewAllowed) notice("此输入框已关闭个人词典和 AI");
            else if(voiceActive) notice("先轻点麦克风暂停，再长按使用写作工具");
            else {finishComposition();writing.open();}
            return true;
        });
        draft = new EditText(context) {
            private float downX,downY;
            private int pressedOffset;
            @Override public boolean performLongClick() {
                if (!expanded() && length()>0 && pressedOffset>=0 && pressedOffset<length()) {
                    selectWordAt(pressedOffset);
                    if (selected()) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        return true;
                    }
                }
                return super.performLongClick();
            }
            @Override public boolean onTouchEvent(android.view.MotionEvent event) {
                if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN) {
                    downX=event.getRawX();downY=event.getRawY();pressedOffset=getOffsetForPosition(event.getX(),event.getY());
                }
                boolean stationary=event.getActionMasked()==android.view.MotionEvent.ACTION_UP && !expanded()
                        && Math.hypot(event.getRawX()-downX,event.getRawY()-downY)<ViewConfiguration.get(context).getScaledTouchSlop();
                boolean open=stationary && event.getEventTime()-event.getDownTime()<ViewConfiguration.getLongPressTimeout();
                int offset=open?getOffsetForPosition(event.getX(),event.getY()):0;
                boolean handled=super.onTouchEvent(event);
                if(open) { showKeyboard(true); selectWordAt(offset); }
                else if(stationary) selectWordAt(pressedOffset);
                return handled;
            }
            @Override protected void onSelectionChanged(int start,int end) {
                super.onSelectionChanged(start,end);draftVersion++;
                if (submit!=null) updateMic();
                if(keys!=null && drafting && portraitChinese()) renderCandidates();
            }
        };
        draft.setSingleLine(true); draft.setTextSize(17); draft.setTextColor(ink);
        draft.setHintTextColor(muted); draft.setHint("草稿");
        draft.setShowSoftInputOnFocus(false); draft.setSelectAllOnFocus(false);
        draft.setPadding(dp(7),0,dp(4),0); draft.setBackgroundColor(0);
        draft.setHighlightColor(dark?0xff52678a:0xffc0d7fc);
        draft.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        bar.addView(draft,new LayoutParams(0,-1,1));
        toolbarSpace=new TextView(context);
        toolbarSpace.setGravity(Gravity.CENTER_VERTICAL);
        toolbarSpace.setTextSize(16);toolbarSpace.setSingleLine();
        toolbarSpace.setEllipsize(android.text.TextUtils.TruncateAt.START);
        toolbarSpace.setPadding(dp(6),0,dp(6),0);
        bar.addView(toolbarSpace,new LayoutParams(0,-1,1));
        delete=tool(bar,this::backspace);
        delete.setOnClickListener(v->{feedback(v);backspace();});
        delete.setSoundEffectsEnabled(false);
        delete.setOnLongClickListener(v->{
            if((!drafting || draft.length()==0) && !voiceActive && composition.raw.isEmpty() && host.undo()) notice("已撤销上次听写");
            else notice("当前没有可以安全撤销的听写");
            return true;
        });
        enter=tool(bar,host::tapToolbarReturn);
        enter.setOnLongClickListener(v->{
            pressEnter();
            return true;
        });
        shortcutRow=new LinearLayout(context);bar.addView(shortcutRow,new LayoutParams(-2,-1));
        settings=tool(bar,() -> showPreferences(!preferencesOpen));
        edit=tool(bar,() -> showKeyboard(!expanded()));
        edit.setOnLongClickListener(v->{
            if(expanded()) return false;
            if(!drafting) {Toast.makeText(getContext(),"暂无听写草稿",Toast.LENGTH_SHORT).show();return true;}
            peek=!showPeek();peekChosen=true;applyColors();requestLayout();return true;
        });
        submit=tool(bar,this::fillOnly);
        submit.display(ToolButton.Icon.CHECK,"填入草稿",blue,accent);
        hide=tool(bar,()->{
            if (!drafting) finishComposition();
            host.hideInput();
        });
        hide.display(ToolButton.Icon.DOWN,"隐藏输入法",muted,0);
        draft.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {
                learning.before(s,start,count,after,fromVoice && previewAllowed && personal.learns()
                        && !rewriting && (!changing || spokenReplacement));
            }
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                if(s instanceof Editable)
                    learning.after((Editable)s,start,count,fromVoice && previewAllowed && personal.learns() && changing);
                if(!expanded() && drafting && options.shortLandscape()) {
                    draft.setVisibility(showPeek()?VISIBLE:GONE);requestLayout();
                }
                draftVersion++;if (s.length()==0) dictatedTail=false;
                if (voiceActive && !changing) edited=true;
                updateSubmit();
            }
            public void afterTextChanged(Editable s) {}
        });
        keyboard = new LinearLayout(context); keyboard.setOrientation(VERTICAL); addView(keyboard,new LayoutParams(-1,-2));
        candidateScroll = new HorizontalScrollView(context);
        candidateScroll.setHorizontalScrollBarEnabled(false);
        inlineCandidates=portrait() || options.phonePortrait() || options.shortLandscape() || getResources().getConfiguration().screenWidthDp>=640;
        if(inlineCandidates) bar.addView(candidateScroll,bar.indexOfChild(draft)+1,new LayoutParams(0,dp(40),2.3f));
        else keyboard.addView(candidateScroll);
        candidateScroll.setVisibility(GONE);
        candidates = new LinearLayout(context); candidateScroll.addView(candidates);
        keys = new LinearLayout(context); keys.setOrientation(VERTICAL); keyboard.addView(keys);
        dragHandle = new FrameLayout(context);
        dragHandle.setContentDescription("拖动悬浮键盘");
        dragHandle.setOnTouchListener((v,e) -> getParent() instanceof KeyboardSurface
                && ((KeyboardSurface)getParent()).drag(v,e));
        grip = new View(context);
        grip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dragHandle.addView(grip,new FrameLayout.LayoutParams(dp(70),dp(4),Gravity.CENTER));
        addView(dragHandle,new LayoutParams(-1,dp(36)));
        showKeyboard(false); endVoice();
    }
    private boolean dragMic(View v,MotionEvent e) {
        if(expanded()) return false;
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragX=e.getRawX();dragY=e.getRawY();originalX=xFraction;originalLift=heightFraction;
                dragging=false;return false;
            case MotionEvent.ACTION_MOVE:
                float dx=e.getRawX()-dragX,dy=e.getRawY()-dragY;
                if(!dragging && Math.hypot(dx,dy)<ViewConfiguration.get(getContext()).getScaledTouchSlop()) return false;
                if(!dragging) {
                    MotionEvent cancel=MotionEvent.obtain(e);cancel.setAction(MotionEvent.ACTION_CANCEL);
                    v.onTouchEvent(cancel);cancel.recycle();dragging=true;
                }
                int free=Math.max(1,getWidth()-getPaddingLeft()-getPaddingRight()-bar.getWidth());
                xFraction=Math.max(0,Math.min(1,originalX+dx/free));
                heightFraction=Math.max(0,Math.min(1,originalLift-dy/liftLimit()));
                positionBar();return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:
                if(!dragging) return false;
                dragging=false;savePosition();return true;
            default:return false;
        }
    }
    private void notice(String message) {Toast.makeText(getContext(),message,Toast.LENGTH_SHORT).show();}
    private String aiSource() {
        if(!previewAllowed || voiceActive) return "";
        if(!drafting) return host.selection();
        if(!selected()) return draft.getText().toString();
        int a=draft.getSelectionStart(),b=draft.getSelectionEnd();
        return draft.getText().subSequence(Math.min(a,b),Math.max(a,b)).toString();
    }
    private String[] proposedWord() {
        if(!previewAllowed) return null;
        String now=draft.getText().toString();
        if(drafting && !transcript.isEmpty() && !transcript.equals(now)) {
            int a=0,b=0;
            while(a<Math.min(now.length(),transcript.length()) && now.charAt(a)==transcript.charAt(a)) a++;
            while(b<Math.min(now.length(),transcript.length())-a && now.charAt(now.length()-1-b)==transcript.charAt(transcript.length()-1-b)) b++;
            // Include the complete Latin identifier if only its middle letters changed.
            while(a>0 && asciiWord(now.charAt(a-1))) a--;
            while(b>0 && asciiWord(now.charAt(now.length()-b))) b--;
            String before=transcript.substring(a,transcript.length()-b).trim(), after=now.substring(a,now.length()-b).trim();
            if(PersonalWords.word(before) && PersonalWords.word(after)) return new String[]{before,after};
        }
        String term=aiSource();return PersonalWords.word(term)?new String[]{term,term}:null;
    }
    private static boolean asciiWord(char c) {return c<128 && (Character.isLetterOrDigit(c)||c=='_');}
    private boolean selected() { return draft.getSelectionStart()>=0 && draft.getSelectionStart()!=draft.getSelectionEnd(); }
    private boolean wantsReplacement() {
        if(!selected()) return false;
        Editable text=draft.getText();
        int a=draft.getSelectionStart(),b=draft.getSelectionEnd();
        return text.getSpanStart(replaceRange)!=Math.min(a,b) || text.getSpanEnd(replaceRange)!=Math.max(a,b);
    }
    private void selectWordAt(int offset) {
        String value=draft.getText().toString();
        if (offset<0 || offset>=value.length() || !Character.isLetterOrDigit(value.codePointAt(offset))) return;
        words.setText(value);
        int left=words.preceding(offset+1),right=words.following(offset);
        if(left>=0 && right>left) draft.setSelection(left,right);
    }
    private String mark(Object span) {
        Editable text=draft.getText();text.removeSpan(span);
        int a=draft.getSelectionStart(),b=draft.getSelectionEnd();
        if(a<0 || b<0 || a==b) return "";
        text.setSpan(span,Math.min(a,b),Math.max(a,b),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text.subSequence(Math.min(a,b),Math.max(a,b)).toString();
    }
    private void rememberRetake() { retakeOriginal=mark(retakeRange); }
    boolean restoreRetake() {
        Editable text=draft.getText();
        int a=text.getSpanStart(retakeRange),b=text.getSpanEnd(retakeRange);
        text.removeSpan(retakeRange);
        if(a<0 || b<=a || !retakeOriginal.contentEquals(text.subSequence(a,b))) return false;
        draft.setSelection(a,b); return true;
    }
    private ToolButton tool(LinearLayout parent,Runnable task) {
        ToolButton b=new ToolButton(getContext());b.setOnClickListener(v -> task.run());
        parent.addView(b,new LayoutParams(dp(48),dp(48))); return b;
    }
    private void updateMic() {
        ToolButton.Icon icon=!voiceActive?ToolButton.Icon.MIC:!listening?ToolButton.Icon.BUSY:
                wantsReplacement()?ToolButton.Icon.MIC:ToolButton.Icon.LISTEN;
        String label=!voiceActive?(selected()?"重说选中文字":"开始听写"):
                finalizing?"正在完成听写":!listening?"取消准备":wantsReplacement()?"重说选中文字":"暂停听写";
        dictate.display(icon,label,voiceActive?(dark?0xff102c50:0xffffffff):blue,
                voiceActive?(dark?0xffabc8ff:blue):accent);
        dictate.setEnabled(!finalizing);
    }
    private void updateSubmit() {
        boolean enabled=voiceActive?listening && !finalizing:hasText();
        submit.setEnabled(enabled);submit.setAlpha(enabled?1f:.38f);
    }
    @Override protected void onMeasure(int width,int height) {
        int available=MeasureSpec.getSize(width)-getPaddingLeft()-getPaddingRight();
        // Leave room to read and select a draft in narrow and split-screen windows.
        int buttonWidth=Math.min(dp(48),Math.max(dp(32),(available-dp(90))/6));
        int count=expanded()?0:shortcutRow.getChildCount();
        int usable=Math.max(0,available-bar.getPaddingLeft()-bar.getPaddingRight());
        boolean preview=showPeek() && drafting;
        if(!expanded()) buttonWidth=Math.min(dp(48),Math.max(1,(usable-(preview?Math.min(dp(100),usable/3):0))/(4+count)));
        for(int i=0;i<count;i++) shortcutRow.getChildAt(i).getLayoutParams().width=buttonWidth;
        int barHeight=dp(50);
        if(floating()) barHeight=dp(options.shortLandscape()?42:46);
        if(expanded() && options.phonePortrait()) barHeight=dp(44);
        bar.getLayoutParams().height=barHeight;
        for(int i=0;i<bar.getChildCount();i++) {
            View child=bar.getChildAt(i);
            if(child instanceof ToolButton) child.getLayoutParams().height=Math.min(dp(48),barHeight);
            if(child instanceof ToolButton) child.getLayoutParams().width=buttonWidth;
        }
        int compactWidth=(4+count)*buttonWidth+bar.getPaddingLeft()+bar.getPaddingRight()+(preview?dp(210):0);
        bar.getLayoutParams().width=expanded()?-1:Math.min(compactWidth,Math.max(0,available));
        super.onMeasure(width,height);
    }
    int liftLimit() { return Math.max(1,getResources().getDisplayMetrics().heightPixels-dp(148)); }
    private void savePosition() {
        positions().edit().putFloat("toolX",xFraction).putFloat("toolHeight",heightFraction).apply();
    }
    private void positionBar() { alignBar();host.positionToolbar(expanded()?0:heightFraction); }
    private void alignBar() {
        int free=Math.max(0,getWidth()-getPaddingLeft()-getPaddingRight()-bar.getWidth());
        float offset=expanded()?0:(xFraction-.5f)*free;
        if(!expanded() && !dragging && editorCaret!=null && free>0) {
            int[] screen=new int[2];getLocationOnScreen(screen);
            float left=screen[0]+bar.getLeft()+offset,top=screen[1]+bar.getTop();
            if(editorCaret.right>left && editorCaret.left<left+bar.getWidth()
                    && editorCaret.bottom>top && editorCaret.top<top+bar.getHeight()) {
                offset=editorCaret.centerX()<screen[0]+getWidth()/2f?free/2f:-free/2f;
            }
        }
        bar.setTranslationX(offset);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        super.onLayout(changed,l,t,r,b);alignBar();
    }
    void trackCaret(android.graphics.RectF rect) {
        editorCaret=rect;
        float old=bar.getTranslationX();alignBar();
        if(old!=bar.getTranslationX()) requestLayout();
    }
    boolean shortLandscape() {return options.shortLandscape();}
    boolean canPreview() {return expanded() && !drafting && !preferencesOpen && previewAllowed;}
    void showEditorLine(String text) {
        if(!android.text.TextUtils.equals(toolbarSpace.getText(),text)) toolbarSpace.setText(text);
    }
    View touchSurface() { return expanded()?this:bar; }
    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color,int radius) {
        GradientDrawable shape=new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout row=new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL); parent.addView(row); return row;
    }
    private void readColors() {
        options.refresh();dark=options.dark; canvas=options.base; surface=options.key;
        ink=options.ink; muted=options.secondary; blue=dark?0xffb9ccff:0xff265a9b;
        accent=dark?0xff354763:0xffd4e3ff;
    }
    private ToolButton typingReturn;
    private boolean sendAction;
    void showSendAction(boolean value) {
        if (sendAction == value) return;
        sendAction = value;
        drawReturn();
        updateTypingReturn();renderCandidates();
    }
    private void updateTypingReturn() {
        if (typingReturn!=null) typingReturn.glyph(sendAction?ToolButton.Icon.SEND:ToolButton.Icon.ENTER, sendAction?"发送":"确认输入",options.onPrimary);
    }
    private void drawReturn() {
        enter.display(sendAction ? ToolButton.Icon.SEND : ToolButton.Icon.ENTER,
                sendAction ? "发送" : "填入草稿，再点发送；长按回车",
                sendAction ? blue : ink, sendAction ? accent : 0);
    }
    private void applyColors() {
        readColors();
        bar.setBackground(shape(expanded()?canvas:surface,28));
        bar.setElevation(floating()?0:dp(1));
        draft.setTextColor(ink);draft.setHintTextColor(muted);toolbarSpace.setTextColor(ink);
        draft.setHighlightColor(dark?0xff52678a:0xffc0d7fc);
        shortcutRow.setVisibility(expanded()?GONE:VISIBLE);
        draft.setVisibility(drafting && (expanded() || showPeek())?VISIBLE:GONE);
        submit.setVisibility(drafting && expanded()?VISIBLE:GONE);
        hide.setVisibility(expanded()?VISIBLE:GONE);
        drawReturn();
        delete.display(ToolButton.Icon.DELETE,drafting?"删除草稿文字":"删除",ink,0);
        settings.display(preferencesOpen?ToolButton.Icon.BACK:ToolButton.Icon.SETTINGS,
                preferencesOpen?"返回键盘":"键盘设置",ink,0);
        edit.display(expanded()?ToolButton.Icon.FOLD:ToolButton.Icon.KEYBOARD,expanded()?"折叠键盘":"展开键盘",muted,0);
        submit.display(ToolButton.Icon.CHECK,"填入草稿",blue,accent);
        hide.display(ToolButton.Icon.DOWN,"隐藏输入法",muted,0);
        if (floating() && hide.getParent()==bar) {
            bar.removeView(settings);bar.addView(settings,0,new LayoutParams(dp(44),dp(44)));
            bar.removeView(hide);
            dragHandle.addView(hide,new FrameLayout.LayoutParams(dp(48),-1,Gravity.LEFT));
            bar.removeView(dictate);bar.addView(dictate,new LayoutParams(dp(44),dp(44)));
        } else if (!floating() && hide.getParent()==dragHandle) {
            bar.removeView(settings);bar.addView(settings,bar.indexOfChild(edit),new LayoutParams(dp(48),dp(48)));
            dragHandle.removeView(hide);bar.addView(hide,new LayoutParams(dp(48),dp(48)));
            bar.removeView(dictate);bar.addView(dictate,0,new LayoutParams(dp(48),dp(48)));
        }
        dragHandle.setVisibility(floating()?VISIBLE:GONE);
        grip.setBackground(shape(muted,4));
        if (floating()) {
            setElevation(dp(3));
            setBackground(shape(canvas,18));
            setClipToOutline(true);
        } else {
            setElevation(0);
            setClipToOutline(false);
            setBackgroundColor(expanded()?canvas:0);
        }
        updateMic();
    }
    private void feedback(View v) {
        if(options.prefs.getBoolean("keySound",false))
            ((android.media.AudioManager)getContext().getSystemService(Context.AUDIO_SERVICE))
                    .playSoundEffect(android.media.AudioManager.FX_KEYPRESS_STANDARD);
        if(options.prefs.getBoolean("keyHaptic",true)) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }
    private void bindRepeat(View v) {
        Runnable repeat=new Runnable() {
            public void run() {
                if(!v.isPressed() || !v.isShown() || (drafting && !hasText())) return;
                backspace();v.postDelayed(this,80);
            }
        };
        v.setOnLongClickListener(key->{feedback(key);repeat.run();return true;});
        v.setOnTouchListener((key,event)->{
            int a=event.getActionMasked();
            if(a==MotionEvent.ACTION_CANCEL || a==MotionEvent.ACTION_UP) key.removeCallbacks(repeat);
            return false;
        });
    }
    private TextView labelKey(String label,int size) {
        TextView key=new TextView(getContext());key.setGravity(Gravity.CENTER);key.setText(label);
        key.setSingleLine(true);key.setTextSize(size);key.setTextColor(ink);
        key.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));return key;
    }
    private void attachKey(LinearLayout row,View v,float weight,int fill,int radius,Runnable action) {
        v.setSoundEffectsEnabled(false);
        v.setOnClickListener(key->{feedback(key);action.run();});
        LayoutParams lp=new LayoutParams(0,dp(options.rowHeight()),weight);
        int h=floating()?2:getResources().getConfiguration().screenWidthDp>=600?5:2;
        int vertical=floating()?4:options.shortLandscape()?1:options.phonePortrait()?3:4;
        int corner=floating()?(radius>=26?22:6):options.phonePortrait()?Math.min(radius,radius>=26?16:7):radius;
        v.setBackground(new android.graphics.drawable.InsetDrawable(options.face(fill,corner),dp(h),dp(vertical),dp(h),dp(vertical)));
        row.addView(v,lp);
    }
    private TextView textKey(LinearLayout row,String label,float weight,int size,int fill,int radius,Runnable action) {
        int cap=floating()?22:options.shortLandscape()?18:options.phonePortrait()?21:size;
        TextView key=labelKey(label,Math.min(size,cap));attachKey(row,key,weight,fill,radius,action);return key;
    }
    private ToolButton iconKey(LinearLayout row,ToolButton.Icon glyph,String name,float weight,int fill,Runnable action) {
        ToolButton key=new ToolButton(getContext()); key.display(glyph,name,ink,0);
        attachKey(row,key,weight,fill,glyph==ToolButton.Icon.ENTER?26:14,action);return key;
    }
    boolean floating() {
        return expanded() && getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }
    int backdrop() { return expanded() && !floating()?options.base:0; }
    boolean expanded() { return keyboard.getVisibility()==VISIBLE; }
    boolean hasText() { return draft.length()>0 || !composition.raw.isEmpty(); }
    void showKeyboard(boolean show) {
        dismissPunctuation();showTypingTools=false;
        if(preferences!=null) {keyboard.removeView(preferences);preferences=null;}
        preferencesOpen=false;keys.setVisibility(VISIBLE);
        keyboard.setVisibility(show ? VISIBLE : GONE);
        settings.setVisibility(show?VISIBLE:GONE);peek=false;peekChosen=false;bindShortcuts();
        applyColors();positionBar();requestLayout();
        renderCandidates();
        if(show) {if(drafting) draft.requestFocus();openEngine();renderKeys();}
    }
    private void showPreferences(boolean show) {
        if(!expanded()) return;
        preferencesOpen=show;
        if(preferences!=null) {keyboard.removeView(preferences);preferences=null;}
        keys.setVisibility(show?GONE:VISIBLE);
        if(show) {
            preferences=options.page();
            keyboard.addView(preferences,new LayoutParams(-1,dp(options.rowHeight()*(options.numberRow()?5:4))));
        }
        applyColors();positionBar();renderCandidates();
        if(!show) {
            if(!voiceActive && !hasText()) setDraftMode(typedDraft());
            renderKeys();if(drafting) draft.requestFocus();
        }
    }
    private void reloadPreferences() {options.refresh();showPreferences(true);}
    private void openEngine() {
        if (ready || opening || closed) return;
        opening=true; error="";
        Context app=getContext().getApplicationContext();
        RimeEngine.worker.execute(() -> {
            String failure="";
            try { engine.open(app);suggestions.load(app); }
            catch (Exception | LinkageError ex) {
                android.util.Log.e("MicBarRime","Rime initialization failed",ex);
                failure="拼音未就绪，点此重试";
            }
            final String message=failure;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (closed) return;
                opening=false; ready=message.isEmpty(); error=message;
                renderCandidates(); renderKeys();
            });
        });
    }
    void dispose() {
        dismissPunctuation();
        writing.close();closed=true;
        RimeEngine.worker.execute(engine::close);
    }
    void reset() {
        editorSuffix="";showTypingTools=false;
        writing.close();aiBefore="";aiAfter="";transcript="";identifier="";fromVoice=false;dictatedTail=false;editorCaret=null;showEditorLine("");
        if (ready) engine.clear();
        composition = new RimeEngine.Result();
        draft.getText().removeSpan(retakeRange); retakeOriginal="";
        draft.setText(""); symbols=false; moreSymbols=false; shift=false; caps=false; showKeyboard(false);
    }
    private boolean typedDraft() { return !secureField && options.prefs.getBoolean("typedDraft",false); }
    void startEditor(android.view.inputmethod.EditorInfo info) {
        int type=info.inputType;
        int kind=type & TYPE_MASK_CLASS;
        int variation=type & TYPE_MASK_VARIATION;
        boolean numeric=kind==TYPE_CLASS_NUMBER || kind==TYPE_CLASS_PHONE
                || kind==TYPE_CLASS_DATETIME;
        boolean textField=kind==TYPE_CLASS_TEXT;
        secureField=(textField && (variation==TYPE_TEXT_VARIATION_PASSWORD || variation==TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation==TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (kind==TYPE_CLASS_NUMBER && variation==TYPE_NUMBER_VARIATION_PASSWORD);
        english=numeric || secureField || (textField && (variation==TYPE_TEXT_VARIATION_URI || variation==TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || variation==TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
                || options.prefs.getBoolean("englishTyping",false);
        proseField=!secureField && !numeric && !(textField && (variation==TYPE_TEXT_VARIATION_URI
                || variation==TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation==TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS));
        previewAllowed=!secureField && (info.imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)==0;
        symbols=numeric;
        setDraftMode(typedDraft());
    }
    private void setDraftMode(boolean value) {
        drafting=value;if(!value) peek=false;
        applyColors();renderCandidates();updateSubmit();
    }
    void replaceSelection(String text) {
        if(!drafting) {host.insert(text);return;}
        int a=draft.getSelectionStart(), b=draft.getSelectionEnd();
        if (a<0 || b<0) a=b=draft.length();
        int start=Math.min(a,b);
        draft.getText().replace(start,Math.max(a,b),text);
        draft.setSelection(start+text.length());
    }
    void beginVoice() {
        finishComposition();
        writing.close();fromVoice=true;voiceActive=true; edited=false;
        setDraftMode(true);
        previousHypothesis="";
        Editable text=draft.getText();
        int pos=draft.getSelectionEnd()<0 ? text.length() : draft.getSelectionEnd();
        replaceOriginal=mark(replaceRange);
        text.setSpan(voiceAnchor,pos,pos,android.text.Spanned.SPAN_POINT_POINT);
        voiceState("准备中",true);
        hide.setVisibility(expanded()?VISIBLE:GONE);
        draft.setHint("准备麦克风…");
    }
    void voiceState(String label,boolean enabled) {
        listening="结束".equals(label); finalizing="识别中".equals(label);
        updateMic();updateSubmit();
        if (listening) draft.setHint("正在聆听…");
    }
    void endVoice() {
        if(voiceActive && !edited) transcript=draft.getText().toString();
        boolean emptyVoice=voiceActive && !hasText();
        voiceActive=false;
        if(emptyVoice) setDraftMode(typedDraft());
        draft.getText().removeSpan(voiceAnchor);
        draft.getText().removeSpan(replaceRange);
        draft.getText().removeSpan(provisionalStart);
        previousHypothesis="";
        voiceState("填入",true); hide.setVisibility(expanded()?VISIBLE:GONE); dictate.setVisibility(VISIBLE); draft.setHint("草稿");
    }
    void rememberSpokenEnding() {
        // A spoken correction in the middle of a draft must not punctuate its untouched tail.
        if(draft.length()>0 && draft.getText().getSpanStart(voiceAnchor)==draft.length()) dictatedTail=true;
    }
    boolean manualVoiceEdit() { return edited || expanded() || !composition.raw.isEmpty(); }
    boolean insertDraft() { return confirm(); }
    void phrase(String value) { insertPhrase(value,true); }
    private void insertPhrase(String value,boolean trimStop) {
        if (!voiceActive || value.isEmpty()) return;
        Editable text=draft.getText();
        int pos=text.getSpanStart(voiceAnchor);
        if (pos<0) return;
        int a=text.getSpanStart(replaceRange),b=text.getSpanEnd(replaceRange);
        text.removeSpan(replaceRange);
        if(a>=0 && b>a && replaceOriginal.contentEquals(text.subSequence(a,b))) {
            changing=true;
            try {
                // Dictating a word in the middle of a sentence should not add a sentence terminator.
                if(trimStop && b<text.length() && value.length()>1 && "。.!！?？".indexOf(value.charAt(value.length()-1))>=0)
                    value=value.substring(0,value.length()-1);
                spokenReplacement=true;
                try {text.replace(a,b,value);} finally {spokenReplacement=false;}
                int end=a+value.length();
                text.setSpan(voiceAnchor,end,end,android.text.Spanned.SPAN_POINT_POINT);
                draft.setSelection(end);
            } finally { changing=false; }
            return;
        }
        if (pos>0 && value.charAt(0)<128 && Character.isLetterOrDigit(value.charAt(0))
                && Character.isLetterOrDigit(text.charAt(pos-1))) value=" "+value;
        changing=true;
        try { text.insert(pos,value); } finally { changing=false; }
    }
    // System services can revise the whole partial hypothesis at every callback.
    // Limit replacement to the marked speech. If edited, preserve it and append
    // later recognition outside the edit; offline results arrive as independent phrases.
    void systemHypothesis(String value) {
        if (!voiceActive || value.isEmpty() || value.equals(previousHypothesis)) return;
        Editable text=draft.getText();
        int end=text.getSpanStart(voiceAnchor);
        if (end<0) return;
        if (previousHypothesis.isEmpty()) {
            insertPhrase(value,false);
            end=text.getSpanStart(voiceAnchor);
            int start=Math.max(0,end-value.length());
            text.setSpan(provisionalStart,start,start,android.text.Spanned.SPAN_MARK_MARK);
        } else {
            int start=text.getSpanStart(provisionalStart);
            if(start<0 || start>end) return;
            String current=text.subSequence(start,end).toString();
            String merged=RevisedSpeech.merge(previousHypothesis,current,value);
            int left=0, tail=0;
            while(left<current.length() && left<merged.length() && current.charAt(left)==merged.charAt(left)) left++;
            while(tail<current.length()-left && tail<merged.length()-left &&
                    current.charAt(current.length()-tail-1)==merged.charAt(merged.length()-tail-1)) tail++;
            changing=true;
            try {
                text.replace(start+left,end-tail,merged,left,merged.length()-tail);
            } finally { changing=false; }
        }
        previousHypothesis=value;
    }
    private void renderKeys() {
        dismissPunctuation();
        keys.removeAllViews();
        int pad=floating()?2:getResources().getConfiguration().screenWidthDp>=600?16:0;
        keys.setPadding(dp(pad),0,dp(pad),dp(2));
        int letters=options.borders()?options.key:canvas;
        if(!symbols && options.numberRow()) characterRow("1234567890",letters,false);
        String[] rows;
        if(symbols) {
            rows=english?(moreSymbols?EN_MORE:EN_SYMBOLS):(moreSymbols?ZH_MORE:ZH_SYMBOLS);
            symbolKeys(row(keys),rows[0],letters);
            symbolKeys(row(keys),rows[1],letters);
        } else {
            rows=new String[]{"qwertyuiop","asdfghjkl","zxcvbnm"};
            characterRow(rows[0],letters,false);
            characterRow(rows[1],letters,true);
        }
        LinearLayout third=row(keys);
        if(symbols) textKey(third,moreSymbols?"?123":"=\\<",1.5f,15,options.function,14,()->{moreSymbols=!moreSymbols;renderKeys();});
        else {
            ToolButton arrow=iconKey(third,caps?ToolButton.Icon.LOCK:ToolButton.Icon.SHIFT,
                    caps?"关闭大写锁定":"大写，连点两下锁定",1.5f,shift?accent:options.function,()->{
                        long now=android.os.SystemClock.uptimeMillis();
                        if(caps) {caps=false;shift=false;}
                        else if(shift && now-lastShiftTap<ViewConfiguration.getDoubleTapTimeout()) caps=true;
                        else shift=!shift;
                        lastShiftTap=now;renderKeys();
                    });
            arrow.setSelected(shift);
        }
        if(symbols) symbolKeys(third,rows[2],letters);
        else addCharacters(third,rows[2],letters);
        ToolButton erase=iconKey(third,ToolButton.Icon.DELETE,"退格",1.5f,options.function,this::backspace);
        bindRepeat(erase);
        LinearLayout bottom=row(keys);
        if(portrait() && !english && !symbols) {
            textKey(bottom,"⋯",.95f,20,options.function,14,()->{
                showTypingTools=!showTypingTools;renderCandidates();
            }).setContentDescription("切换候选栏和工具栏");
        }
        textKey(bottom,symbols?"ABC":"?123",1.5f,16,options.function,26,()->{
            finishComposition();symbols=!symbols;moreSymbols=false;shift=false;caps=false;renderKeys();renderCandidates();
        });
        String comma=english?",":"，",period=english?".":"。";
        TextView commaKey=textKey(bottom,comma,1,21,options.function,12,()->type(comma));
        commaKey.setOnLongClickListener(v->{showPreferences(true);return true;});
        ToolButton lang=iconKey(bottom,ToolButton.Icon.GLOBE,english?"切换到拼音，长按切换输入法":"切换到英文，长按切换输入法",
                1,options.key,()->{finishComposition(); english=!english;options.prefs.edit().putBoolean("englishTyping",english).apply();shift=false;caps=false;renderKeys();renderCandidates();});
        lang.setOnLongClickListener(v->{host.picker();return true;});
        TextView space=textKey(bottom,english?"English":"拼音",4,15,options.key,13,()->{
            if(composition.raw.isEmpty()) replaceSelection(" ");else if(!preferred()) selectCandidate(composition.raw,engine.key(' '));
        });
        space.setTextColor(muted);space.setContentDescription("空格，左右滑动移动光标");
        bindSpaceSwipe(space);
        TextView periodKey=textKey(bottom,period,1,21,options.function,12,()->type(period));
        periodKey.setContentDescription(period+"，长按选择常用标点");
        periodKey.setOnLongClickListener(anchor->{showPunctuation(anchor);return true;});
        typingReturn=iconKey(bottom,ToolButton.Icon.ENTER,"确认输入",1.5f,options.primary,host::tapToolbarReturn);
        typingReturn.setOnLongClickListener(v->{pressEnter();return true;});
        updateTypingReturn();
    }
    private void symbolKeys(LinearLayout row,String tokens,int fill) {
        for(String token:tokens.split(" ")) {
            textKey(row,token,1,token.length()>1?20:24,fill,12,()->type(token));
        }
    }
    private void dismissPunctuation() {
        if(punctuationPopup!=null) punctuationPopup.dismiss();
    }
    private void showPunctuation(View anchor) {
        dismissPunctuation();
        LinearLayout choices=new LinearLayout(getContext());
        choices.setOrientation(VERTICAL);
        choices.setPadding(dp(4),dp(4),dp(4),dp(4));
        choices.setBackground(shape(surface,16));
        String[] marks=english?EN_SHORTCUTS:ZH_SHORTCUTS;
        for(int i=0;i<marks.length;) {
            LinearLayout group=row(choices);
            for(int j=0;j<5 && i<marks.length;j++,i++) {
                String mark=marks[i];
                TextView key=labelKey(mark,22);
                key.setBackground(options.face(surface,10));
                key.setOnClickListener(v->{dismissPunctuation();feedback(v);type(mark);});
                group.addView(key,new LayoutParams(0,dp(46),1));
            }
        }
        int width=Math.min(keys.getWidth()-dp(8),dp(272));
        int height=dp(46)*2+dp(4)*2;
        PopupWindow popup=new PopupWindow(choices,width,height,true);
        popup.setBackgroundDrawable(shape(surface,16));
        popup.setElevation(dp(6));popup.setOutsideTouchable(true);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        popup.setOnDismissListener(()->punctuationPopup=null);
        punctuationPopup=popup;
        popup.showAsDropDown(anchor,0,-anchor.getHeight()-height,Gravity.END);
    }
    private void addCharacters(LinearLayout row,String values,int fill) {
        for(char ch:values.toCharArray()) {
            String value=String.valueOf(ch);
            final String label=shift&&!symbols?value.toUpperCase(Locale.ROOT):value;
            int index="qwertyuiop".indexOf(ch);
            if (floating() && !options.numberRow() && index>=0) {
                String number=String.valueOf((index+1)%10);
                TextView key=new TextView(getContext()) {
                    private final android.graphics.Paint hint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                    @Override protected void onDraw(android.graphics.Canvas c) {
                        super.onDraw(c);
                        hint.setTextAlign(android.graphics.Paint.Align.RIGHT);
                        hint.setTextSize(dp(9));hint.setColor(muted);
                        c.drawText(number,getWidth()-dp(5),dp(13),hint);
                    }
                };
                key.setText(label);key.setTextColor(ink);key.setTextSize(21);key.setGravity(Gravity.CENTER);
                attachKey(row,key,1,fill,12,()->type(label));
                key.setOnLongClickListener(v->{type(number);return true;});
            } else textKey(row,label,1,24,fill,12,()->type(label));
        }
    }
    private void characterRow(String values,int fill,boolean inset) {
        LinearLayout r=row(keys);
        if(inset) r.addView(new Space(getContext()),new LayoutParams(0,1,.5f));
        addCharacters(r,values,fill);
        if(inset) r.addView(new Space(getContext()),new LayoutParams(0,1,.5f));
    }
    private void bindSpaceSwipe(View space) {
        space.setOnTouchListener(new OnTouchListener() {
            float last;boolean swiping;
            public boolean onTouch(View v,MotionEvent event) {
                switch(event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:last=event.getX();swiping=false;v.setPressed(true);return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx=event.getX()-last;int steps=(int)(dx/dp(18));
                        if(steps!=0) {
                            swiping=true;int direction=Integer.signum(steps);
                            for(int i=0;i<Math.abs(steps);i++) move(direction);
                            last+=steps*dp(18);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:v.setPressed(false);if(!swiping) v.performClick();return true;
                    case MotionEvent.ACTION_CANCEL:v.setPressed(false);return true;
                    default:return false;
                }
            }
        });
    }
    private void type(String s) {
        showTypingTools=false;
        host.editingStarted();
        char ch=s.charAt(0);
        if(!english && !symbols && (Character.isUpperCase(ch) || !identifier.isEmpty() && asciiWord(ch))) {
            if(identifier.isEmpty()) identifier=composition.raw;
            if(ready) engine.clear();
            identifier+=s;composition=new RimeEngine.Result();composition.raw=identifier;
            renderCandidates();updateSubmit();
            if(shift && !caps) {shift=false;renderKeys();}
            return;
        }
        if (!english && !symbols && ch>='a' && ch<='z') {
            if (ready) apply(engine.key(ch));
            else Toast.makeText(getContext(),error.isEmpty()?"正在打开离线词库":error,Toast.LENGTH_SHORT).show();
        } else {
            finishComposition(); replaceSelection(s);
        }
        if (shift && !caps) { shift=false; renderKeys(); }
    }
    private void renderCandidates() {
        candidates.removeAllViews();
        boolean composing=!composition.raw.isEmpty();
        java.util.List<String> next=composing?java.util.Collections.emptyList():nextWords();
        boolean full=portraitChinese() && !showTypingTools && (composing || !next.isEmpty());
        // Swap the existing toolbar row for one full-width candidate row. Never add height.
        if(full && candidateScroll.getParent()!=keyboard){
            bar.removeView(candidateScroll);keyboard.addView(candidateScroll,0,new LayoutParams(-1,dp(options.phonePortrait()?44:50)));
        }else if(!full && inlineCandidates && candidateScroll.getParent()==keyboard){
            keyboard.removeView(candidateScroll);bar.addView(candidateScroll,bar.indexOfChild(draft)+1,new LayoutParams(0,dp(40),2.3f));
        }
        bar.setVisibility(full?GONE:VISIBLE);
        candidateScroll.setFillViewport(full);
        candidates.setGravity(Gravity.CENTER_VERTICAL);
        boolean loading=!ready && !english && !symbols;
        candidateScroll.setVisibility(expanded() && !preferencesOpen && (full || ((composing || loading) && !(portraitChinese() && showTypingTools)))?VISIBLE:GONE);
        // Let the suggestion strip take the tool row while spelling; editing keys remain on the keyboard.
        if (floating()) {
            delete.setVisibility(GONE);enter.setVisibility(GONE);
            settings.setVisibility(!composing?VISIBLE:GONE);
        } else if(options.phonePortrait()) {
            boolean showActions=candidateScroll.getVisibility()!=VISIBLE;
            delete.setVisibility(showActions?VISIBLE:GONE);
            enter.setVisibility(showActions?VISIBLE:GONE);
            settings.setVisibility(expanded() && showActions?VISIBLE:GONE);
        }
        if(!expanded()) { delete.setVisibility(VISIBLE);enter.setVisibility(VISIBLE); }
        toolbarSpace.setVisibility(expanded() && !drafting && (!floating() || !composing) && (options.shortLandscape() || !inlineCandidates || candidateScroll.getVisibility()!=VISIBLE)?VISIBLE:GONE);
        candidateScroll.scrollTo(0,0);
        if(!composing) {
            if(full){
                for(String word:next)candidate(word,()->{host.editingStarted();replaceSelection(word);},false);
            }
            if(loading) candidate(error.isEmpty()?"正在打开离线词库…":error,this::openEngine,false);
            return;
        }
        java.util.LinkedHashSet<String> extra=new java.util.LinkedHashSet<>();
        if(previewAllowed) extra.addAll(personal.candidates(composition.raw));
        java.util.List<String> alternatives=ready?suggestions.forInput(composition.raw):java.util.Collections.emptyList();
        for(String word:extra) candidate(word,()->chooseWord(word),true);
        for(String word:alternatives) if(!extra.contains(word) && word.equalsIgnoreCase(composition.raw)) candidate(word,()->chooseWord(word),true);
        if(!full) candidate(composition.raw,()->chooseWord(composition.raw),false);
        for(int i=0;i<composition.candidates.size();i++) {
            final int index=i;
            String value=composition.candidates.get(i);
            if(!extra.contains(value) && !(alternatives.contains(value) && value.equalsIgnoreCase(composition.raw))) candidate(value,()->{
                selectCandidate(composition.raw,engine.select(index));
            },i==0);
            if(i==0) typoCandidates(alternatives,extra);
        }
        if(composition.candidates.isEmpty()) typoCandidates(alternatives,extra);
        if(full && !extra.contains(composition.raw) && !composition.candidates.contains(composition.raw)) candidate(composition.raw,()->chooseWord(composition.raw),false);
        if(!english && composition.raw.matches("[A-Za-z][A-Za-z0-9_]*")) {
            candidate(composition.raw+"_",()->{
                identifier=composition.raw+"_";if(ready) engine.clear();
                composition=new RimeEngine.Result();composition.raw=identifier;renderCandidates();updateSubmit();
            },false);
        }
        if(composition.previous) candidate("‹",()->apply(engine.key(0xff55)),false);
        if(composition.next) candidate("›",()->apply(engine.key(0xff56)),false);
        candidateScroll.scrollTo(0,0);
    }
    private void typoCandidates(java.util.List<String> alternatives,java.util.Set<String> personalWords) {
        for(String word:alternatives) if(!word.equalsIgnoreCase(composition.raw) && !personalWords.contains(word)
                && !composition.candidates.contains(word)) candidate(word,()->chooseWord(word),false);
    }
    private void candidate(String label,Runnable action,boolean first) {
        TextView v=labelKey(label,17);v.setPadding(dp(14),0,dp(14),0);
        boolean full=bar.getVisibility()==GONE;
        v.setTextColor(first?blue:ink);v.setMinWidth(dp(full?64:42));
        v.setBackground(full?options.face(canvas,0):options.face(first?accent:canvas,18));v.setOnClickListener(key->{feedback(key);action.run();});
        candidates.addView(v,new LayoutParams(-2,dp(full?42:38),full?1:0));
    }
    private void apply(RimeEngine.Result r) {
        composition=r; updateSubmit();
        if (!r.committed.isEmpty()) replaceSelection(r.committed);
        renderCandidates();
    }
    private void selectCandidate(String raw,RimeEngine.Result result) {
        if(previewAllowed && !result.committed.isEmpty() && result.raw.isEmpty()) personal.selected(raw,result.committed);
        apply(result);
    }
    private void chooseWord(String word) {
        if(previewAllowed) personal.selected(composition.raw,word);
        clearAndInsert(word);
    }
    private void clearAndInsert(String word) {
        identifier="";if(ready) engine.clear();apply(new RimeEngine.Result());replaceSelection(word);
    }
    private void literal() {clearAndInsert(composition.raw);}
    private boolean preferred() {
        if(!identifier.isEmpty()) {literal();return true;}
        if(!previewAllowed) return false;
        java.util.List<String> words=personal.candidates(composition.raw);
        if(words.isEmpty()) return false;
        chooseWord(words.get(0));return true;
    }
    private void finishComposition() {
        while (!composition.raw.isEmpty()) {
            if(preferred()) break;
            String prev=composition.raw;
            if (composition.candidates.isEmpty()) { literal(); break; }
            apply(engine.select(0));
            if (prev.equals(composition.raw)) { literal(); break; }
        }
    }
    private void backspace() {
        host.editingStarted();
        if(!identifier.isEmpty()) {
            identifier=identifier.substring(0,identifier.length()-1);composition.raw=identifier;renderCandidates();updateSubmit();return;
        }
        if (!composition.raw.isEmpty()) { apply(engine.key(0xff08)); return; }
        if(!drafting) {host.editorKey(KeyEvent.KEYCODE_DEL);return;}
        int a=draft.getSelectionStart(),b=draft.getSelectionEnd();
        if (a!=b) replaceSelection("");
        else if (a>0) {
            characters.setText(draft.getText().toString());
            int start=characters.preceding(a);
            if(start<0) return;
            draft.getText().delete(start,a); draft.setSelection(start);
        }
    }
    private void move(int dir) {
        finishComposition();
        if(!drafting) {host.editorKey(dir<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT);return;}
        int a=draft.getSelectionStart(),b=draft.getSelectionEnd();
        Editable text=draft.getText();
        int p=dir<0 ? Math.min(a,b) : Math.max(a,b);
        if (p<0) p=text.length();
        if (a==b && (dir<0 ? p>0 : p<text.length())) p=Character.offsetByCodePoints(text,p,dir);
        draft.setSelection(p);
    }
    private void fillOnly() {
        if(voiceActive) host.acceptVoice();
        else if(drafting) confirm();
        else finishComposition();
    }
    private void pressEnter() {
        if(voiceActive) {host.enterAfterVoice();return;}
        finishComposition();
        if(drafting && draft.length()>0 && !confirm()) return;
        host.editorKey(KeyEvent.KEYCODE_ENTER);
    }
    private boolean confirm() {return confirm(true);}
    boolean confirm(boolean allowFinalMark) {
        finishComposition();
        String text=draft.getText().toString();
        if(text.isEmpty()) return false;
        boolean speech=allowFinalMark && dictatedTail && proseField && options.prefs.getBoolean("finalSpeechMark",true);
        if(!(fromVoice?host.insertSpeech(text,speech):host.insert(text))) return false;
        if(fromVoice && previewAllowed && personal.learns()) learning.save(draft.getText(),personal);
        fromVoice=false;transcript="";
        dictatedTail=false;
        boolean wasExpanded=expanded();
        draft.setText("");peek=false;setDraftMode(typedDraft());
        showKeyboard(wasExpanded);
        return true;
    }
}
