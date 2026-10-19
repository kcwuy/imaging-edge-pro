package com.sony.playmemories.mobile.redesign;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

/** A discrete, camera-supplied scale. A gesture sends only its final candidate. */
public final class V3Ruler extends View {
    public interface Listener { void preview(int index); void commit(int index); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<String> labels = new ArrayList<String>();
    private Listener listener;
    private float position, downPosition, downX;
    private boolean dragging, moved;
    private final float density;
    private final int slop;
    public V3Ruler(Context context) {
        super(context); density = getResources().getDisplayMetrics().density;
        setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(true); setClickable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    public void setListener(Listener value) { listener = value; }
    public boolean isDragging() { return dragging; }
    public void cancelGesture() {
        if (dragging) { position = downPosition; dragging = false; describe(); invalidate(); }
    }
    @Override protected void onDetachedFromWindow() { cancelGesture(); super.onDetachedFromWindow(); }
    public void bind(List<String> values, int index, boolean enabled) {
        if (dragging) return;
        labels.clear(); labels.addAll(values); position = clamp(index, labels.size());
        setEnabled(enabled && labels.size() > 1); describe(); invalidate();
    }
    public static int clamp(int index, int count) { return Math.max(0, Math.min(Math.max(0, count - 1), index)); }
    private float labelSize() { return Math.min(11 * getResources().getDisplayMetrics().scaledDensity, 13 * density); }
    private float spacing() {
        paint.setTextSize(labelSize()); float largest = 0;
        for (String label : labels) largest = Math.max(largest, paint.measureText(label));
        return Math.max(22 * density, (largest + 10 * density) / 3f);
    }
    private void describe() {
        setContentDescription(labels.isEmpty() ? "暂无可调数值" : labels.get(clamp(Math.round(position), labels.size())) + "，左右滑动调整");
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float mid = getWidth() / 2f, base = getHeight() - 8 * density;
        paint.setStrokeWidth(1.5f * density); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(labelSize());
        int selected = clamp(Math.round(position), labels.size());
        for (int i = 0; i < labels.size(); i++) {
            float x = mid + (i - position) * spacing();
            if (x < -spacing() || x > getWidth() + spacing()) continue;
            float edge = Math.min(x, getWidth() - x) / (36 * density);
            int alpha = Math.round(Math.max(.15f, Math.min(1f, edge)) * (isEnabled() ? 255 : 110));
            paint.setColor(0xff888888); paint.setAlpha(alpha);
            float length = (i % 3 == 0 ? 16 : 10) * density;
            canvas.drawLine(x, base - length, x, base, paint);
            if (i % 3 == 0 && Math.abs(i - selected) > 1) {
                canvas.drawText(labels.get(i), x, base - 26 * density, paint);
            }
        }
        paint.setColor(isEnabled() ? 0xffff4b43 : 0xff777777); paint.setAlpha(255);
        paint.setStrokeWidth(2.5f * density);
        canvas.drawLine(mid, base - 23 * density, mid, base + 1 * density, paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || labels.size() < 2) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX(); downPosition = position; dragging = true; moved = false;
                getParent().requestDisallowInterceptTouchEvent(true); return true;
            case MotionEvent.ACTION_MOVE:
                moved |= Math.abs(event.getX() - downX) > slop;
                position = Math.max(0, Math.min(labels.size() - 1, downPosition + (downX - event.getX()) / spacing()));
                if (listener != null) listener.preview(clamp(Math.round(position), labels.size()));
                invalidate(); return true;
            case MotionEvent.ACTION_UP:
                if (!moved) position += (event.getX() - getWidth() / 2f) / spacing();
                position = clamp(Math.round(position), labels.size()); dragging = false; describe(); invalidate();
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick(); if (listener != null) listener.commit((int) position); return true;
            case MotionEvent.ACTION_CANCEL:
                position = downPosition; dragging = false; describe(); invalidate();
                getParent().requestDisallowInterceptTouchEvent(false); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.SeekBar");
        if (!labels.isEmpty()) info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, labels.size() - 1, position));
        if (isEnabled()) { info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD); }
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (isEnabled() && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            int index = clamp(Math.round(position) + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1), labels.size());
            position = index; describe(); invalidate();
            if (listener != null) { listener.preview(index); listener.commit(index); } return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
