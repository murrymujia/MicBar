package org.micbar.ime;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;

/** The system picker needs a focused caller. Close this temporary task on dismissal. */
public final class PickerActivity extends Activity {
    private boolean requested, dialogFocused;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("切换输入法");
        Button again = new Button(this);
        again.setText("选择输入法");
        again.setOnClickListener(v -> showPicker());
        setContentView(again);
    }
    private void showPicker() {
        requested=true;
        getSystemService(InputMethodManager.class).showInputMethodPicker();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused && requested) dialogFocused=true;
        if (!focused || isFinishing()) return;
        if (dialogFocused) { finishAndRemoveTask(); return; }
        if (!requested) getWindow().getDecorView().post(() -> {
            if (hasWindowFocus() && !isFinishing()) showPicker();
        });
    }
}
