package org.micbar.ime;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.widget.ImageButton;

/** A 48dp touch target with a smaller circular surface and a 24dp Material-style glyph. */
final class ToolButton extends ImageButton {
    enum Icon { MIC, LISTEN, BUSY, KEYBOARD, FOLD, CHECK, DOWN, DELETE, SETTINGS, SHIFT, LOCK, GLOBE, ENTER, SEND, BACK }
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Icon icon;
    private int color;
    ToolButton(Context c) {
        super(c);
        setPadding(0,0,0,0);
        setFocusable(true);
    }
    void display(Icon glyph,String label,int foreground,int fill) {
        icon=glyph; color=foreground;
        setContentDescription(label); setTooltipText(label);
        GradientDrawable face=new GradientDrawable();
        face.setShape(GradientDrawable.OVAL); face.setColor(fill);
        GradientDrawable mask=new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL); mask.setColor(0xffffffff);
        int pad=(int)(getResources().getDisplayMetrics().density*4+.5f);
        setBackground(new InsetDrawable(new RippleDrawable(ColorStateList.valueOf(0x308495aa),face,mask),pad));
        invalidate();
    }
    private void line(Canvas c,float x1,float y1,float x2,float y2) { c.drawLine(x1,y1,x2,y2,ink); }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c); c.save();
        float scale=getResources().getDisplayMetrics().density;
        c.translate(getWidth()/2f-12*scale,getHeight()/2f-12*scale); c.scale(scale,scale);
        ink.setColor(color); ink.setStrokeWidth(1.9f); ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStyle(Paint.Style.STROKE);
        switch(icon) {
            case MIC:
                c.drawRoundRect(9,2.8f,15,13.7f,3,3,ink); c.drawArc(5.7f,5,18.3f,17.6f,0,180,false,ink);
                line(c,12,18,12,21); break;
            case LISTEN:
                long time=android.os.SystemClock.uptimeMillis();
                for (int i=0;i<4;i++) {
                    float h=3+(float)(2+2*Math.sin(time/180.0+i*1.7));
                    line(c,5.5f+i*4.3f,12-h,5.5f+i*4.3f,12+h);
                }
                postInvalidateDelayed(100); break;
            case BUSY:
                c.drawArc(5,5,19,19,(android.os.SystemClock.uptimeMillis()%1000)*.36f,240,false,ink);
                postInvalidateDelayed(70);break;
            case KEYBOARD:
                c.drawRoundRect(2,5,22,19,3,3,ink);
                for(int j=0;j<2;j++) for(int i=0;i<4;i++) c.drawPoint(6.5f+i*3.7f,9+j*3,ink);
                line(c,8,15.5f,16,15.5f);break;
            case FOLD:
                c.drawRoundRect(2,2.5f,22,14,2.5f,2.5f,ink);
                for(int i=0;i<4;i++) c.drawPoint(6.5f+i*3.7f,7,ink);
                line(c,9,10.5f,15,10.5f);line(c,8.5f,17.5f,12,20.5f);line(c,12,20.5f,15.5f,17.5f);break;
            case DELETE:
                Path outline=new Path(); outline.moveTo(2.5f,12);outline.lineTo(8.5f,5);
                outline.lineTo(21,5);outline.lineTo(21,19);outline.lineTo(8.5f,19);outline.close();
                ink.setStrokeJoin(Paint.Join.ROUND); c.drawPath(outline,ink);
                line(c,11,9,17,15);line(c,17,9,11,15);break;
            case SHIFT: case LOCK:
                Path arrow=new Path();arrow.moveTo(3,12);arrow.lineTo(12,3);arrow.lineTo(21,12);
                arrow.lineTo(16,12);arrow.lineTo(16,18);arrow.lineTo(8,18);arrow.lineTo(8,12);arrow.close();
                c.drawPath(arrow,ink);if(icon==Icon.LOCK) line(c,8,21,16,21);break;
            case GLOBE:
                c.drawCircle(12,12,8,ink);c.drawOval(8,4,16,20,ink);line(c,4,12,20,12);break;
            case ENTER:
                line(c,20,7,20,13);line(c,20,13,4,13);line(c,4,13,9,8);line(c,4,13,9,18);break;
            case SEND:
                Path send = new Path();
                send.moveTo(3.5f,4); send.lineTo(21,12); send.lineTo(3.5f,20);
                send.lineTo(6,12); send.close();
                c.drawPath(send,ink); line(c,6,12,13,12); break;
            case BACK:
                line(c,4,12,20,12);line(c,4,12,11,5);line(c,4,12,11,19);break;
            case SETTINGS:
                Path gear=new Path();
                for(int j=0;j<32;j++) {
                    double angle=(j-.5)*Math.PI/16;
                    float radius=j%4<2?9.5f:7.8f;
                    float x=12+radius*(float)Math.cos(angle),y=12+radius*(float)Math.sin(angle);
                    if(j==0) gear.moveTo(x,y);else gear.lineTo(x,y);
                }
                gear.close();c.drawPath(gear,ink);c.drawCircle(12,12,3,ink);break;
            case CHECK: line(c,4.5f,12,9.5f,17);line(c,9.5f,17,19.5f,7);break;
            case DOWN: line(c,6,9.5f,12,15.5f);line(c,12,15.5f,18,9.5f);break;
        }
        c.restore();
    }
}
