package org.micbar.ime;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** A transparent landscape IME surface. Only the keyboard rectangle receives touches. */
final class KeyboardSurface extends FrameLayout {
    private final SharedPreferences prefs;
    private final int gap;
    private float x, lift, startX, startLift, downX, downY;
    private int travelX, travelY;
    private boolean moving;

    KeyboardSurface(Context context) {
        super(context);
        prefs = context.getSharedPreferences("micbar", Context.MODE_PRIVATE);
        gap = dp(6);
        x = prefs.getFloat("floatingKeyboardX", .88f);
        lift = prefs.getFloat("floatingKeyboardLift", .12f);
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        DraftPanel panel = (DraftPanel)getChildAt(0);
        boolean floating = panel != null && panel.getVisibility() == VISIBLE && panel.floating();
        int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        if (panel != null) {
            int standard = Math.min(dp(404), Math.max(dp(304), Math.round(MeasureSpec.getSize(heightSpec) * .91f)));
            String size = prefs.getString("floatingWidth", "标准");
            int preferred = Math.round(standard * ("窄".equals(size) ? .86f : "宽".equals(size) ? 1.18f : 1f));
            panel.getLayoutParams().width = floating ? Math.min(preferred, Math.max(1, width * 4 / 5)) : LayoutParams.MATCH_PARENT;
        }
        super.onMeasure(widthSpec, heightSpec);
        if (!floating) return;
        travelX = Math.max(0, width - panel.getMeasuredWidth() - 2 * gap);
        int base = panel.getMeasuredHeight() + getPaddingTop() + getPaddingBottom() + gap;
        travelY = Math.max(0, MeasureSpec.getSize(heightSpec) - base - gap);
        int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? base : MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(getMeasuredWidth(), height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        DraftPanel panel = (DraftPanel)getChildAt(0);
        if (panel == null || !panel.floating()) return;
        int left = getPaddingLeft() + gap + Math.round(x * travelX);
        int top = getHeight() - getPaddingBottom() - gap - panel.getHeight() - Math.round(lift * travelY);
        panel.offsetLeftAndRight(left - panel.getLeft());
        panel.offsetTopAndBottom(Math.max(getPaddingTop(), top) - panel.getTop());
    }

    boolean drag(View handle, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX(); downY = e.getRawY();
                startX = x; startLift = lift; moving = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (!moving && Math.hypot(dx, dy) < ViewConfiguration.get(getContext()).getScaledTouchSlop()) return true;
                moving = true;
                x = Math.max(0, Math.min(1, startX + dx / Math.max(1, travelX)));
                lift = Math.max(0, Math.min(1, startLift - dy / Math.max(1, travelY)));
                requestLayout();
                return true;
            case MotionEvent.ACTION_UP:
                if (moving) {
                    float dropY=e.getRawY()-downY;
                    if (dropY>ViewConfiguration.get(getContext()).getScaledTouchSlop()
                            && startLift*travelY-dropY<=dp(8)) {
                        // Restore the last floating position when the user undocks.
                        x=startX; lift=startLift;
                        ((DraftPanel)getChildAt(0)).setLandscapeDocked(true);
                    } else {
                        prefs.edit().putFloat("floatingKeyboardX",x).putFloat("floatingKeyboardLift",lift).apply();
                    }
                } else handle.performClick();
                moving = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                x = startX; lift = startLift; moving = false; requestLayout();
                return true;
            default:
                return false;
        }
    }
}
