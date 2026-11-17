package org.micbar.ime;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

public final class KeyboardUpdateActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setTitle("轻声键盘更新");
        LinearLayout page=new LinearLayout(this);
        page.setGravity(android.view.Gravity.CENTER);
        Button update=new Button(this);
        update.setText("更新轻声键盘");
        update.setOnClickListener(v->{
            EditionUpdates updates=new EditionUpdates(this);
            String ready=AutoUpdates.ready(this);
            if(ready.isEmpty()) updates.check(update,true);
            else updates.install(ready);
        });
        page.addView(update);
        setContentView(page);
        if(saved==null) update.performClick();
    }
}
