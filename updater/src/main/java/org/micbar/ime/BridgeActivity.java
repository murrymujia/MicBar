package org.micbar.ime;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

public final class BridgeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page=new LinearLayout(this);page.setGravity(Gravity.CENTER);page.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(getResources().getDisplayMetrics().density*24);
        page.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this);title.setText("轻声键盘 · 增量更新");title.setTextSize(24);page.addView(title);
        TextView detail=new TextView(this);detail.setText("使用设备上的旧版安装包还原新版。升级后可在轻声键盘首页检查后续更新。");detail.setPadding(0,pad,0,pad);page.addView(detail);
        Button check=new Button(this);check.setText("检查轻声键盘更新");page.addView(check);
        check.setOnClickListener(v->new EditionUpdates(this).check(check));
        setContentView(page);
    }
}
