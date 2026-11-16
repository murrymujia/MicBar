package org.micbar.ime;

import static android.view.KeyEvent.*;

/** Stable preference identifiers, display labels and editor actions for the voice toolbar. */
enum VoiceShortcut {
    SPACE("空格","空格"," "), COMMA("逗号","，","，"), PERIOD("句号","。","。"),
    QUESTION("问号","？","？"), EXCLAMATION("感叹号","！","！"), PAUSE("顿号","、","、"),
    TAB("Tab","Tab",KEYCODE_TAB), BACKTAB("Shift+Tab","⇤",KEYCODE_TAB),
    ESC("Esc","Esc",KEYCODE_ESCAPE), LEFT("←","←",KEYCODE_DPAD_LEFT), RIGHT("→","→",KEYCODE_DPAD_RIGHT),
    UP("↑","↑",KEYCODE_DPAD_UP), DOWN("↓","↓",KEYCODE_DPAD_DOWN),
    HOME("Home","Home",KEYCODE_MOVE_HOME), END("End","End",KEYCODE_MOVE_END),
    FORWARD_DELETE("Delete","Del",KEYCODE_FORWARD_DEL), PAGE_UP("Page Up","PgUp",KEYCODE_PAGE_UP),
    PAGE_DOWN("Page Down","PgDn",KEYCODE_PAGE_DOWN);

    final String label,cap,text;
    final int code;
    VoiceShortcut(String label,String cap,String text) {this.label=label;this.cap=cap;this.text=text;code=0;}
    VoiceShortcut(String label,String cap,int code) {this.label=label;this.cap=cap;this.code=code;text=null;}
    boolean computer() {return text==null;}
    static VoiceShortcut at(android.content.SharedPreferences prefs,int slot) {
        String fallback=new String[]{"SPACE","COMMA","PERIOD"}[slot];
        try {return valueOf(prefs.getString("voiceSlot"+slot,fallback));}
        catch(IllegalArgumentException bad) {return valueOf(fallback);}
    }
    static int count(android.content.SharedPreferences prefs) {
        return Math.min(3,Math.max(0,prefs.getInt("voiceSlotCount",0)));
    }
}
