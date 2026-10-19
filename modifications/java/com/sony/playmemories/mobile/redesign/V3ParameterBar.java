package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Inline camera controls: readings above, one discrete adjustment below, liveview stays visible. */
public final class V3ParameterBar extends LinearLayout {
    public interface Source {
        V3CameraParameters.Snapshot read(String key);
        boolean set(String key, String value);
    }
    private static final int WHITE = 0xfff5f5f5, MUTED = 0xffa0a0a0, RED = 0xffff4b43, BLACK = 0xff080808;
    private static final String[] KEYS = {"ev", "shutter", "aperture", "iso", "wb", "focus", "mode"};
    private static final String[] NAMES = {"EV", "快门", "光圈", "ISO", "白平衡", "对焦"};
    private final Activity activity;
    private final Source source;
    private final TextView[] captions = new TextView[NAMES.length], readings = new TextView[NAMES.length];
    private final LinearLayout[] cells = new LinearLayout[NAMES.length];
    private final V3CameraParameters.Snapshot[] states = new V3CameraParameters.Snapshot[KEYS.length];
    private final LinearLayout editor, numeric, choices;
    private final HorizontalScrollView choiceScroll;
    private final TextView title, status, auto, close;
    private final V3Ruler ruler;
    private final ArrayList<String> gestureValues = new ArrayList<String>();
    private int selected = -1;
    private boolean blocked, connected, disposed;
    private String pendingKey, pendingValue;
    private long pendingSince;
    private String choiceSignature = "";
    private final int rowHeight, editorHeight;

    public V3ParameterBar(final Activity activity) {
        this(activity, new Source() {
            @Override public V3CameraParameters.Snapshot read(String key) { return V3CameraParameters.read(activity, key); }
            @Override public boolean set(String key, String value) { return V3CameraParameters.set(activity, key, value); }
        });
    }
    public V3ParameterBar(Activity activity, Source source) {
        super(activity); this.activity = activity; this.source = source;
        boolean landscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        rowHeight = dp(landscape ? 50 : 62); editorHeight = dp(landscape ? 90 : 106);
        setOrientation(VERTICAL); setBackgroundColor(BLACK); setPadding(dp(6), dp(2), dp(6), 0);
        setClipChildren(true); setMinimumHeight(getReservedHeight());
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER); row.setBaselineAligned(false);
        for (int i = 0; i < NAMES.length; i++) {
            final int index = i;
            LinearLayout cell = new LinearLayout(activity); cell.setOrientation(VERTICAL); cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(1), dp(6), dp(1), dp(6)); cell.setFocusable(true); cell.setClickable(true); cells[i] = cell;
            readings[i] = fit("—", 10, 16, WHITE); readings[i].setGravity(Gravity.CENTER);
            captions[i] = fit(NAMES[i], 9, 10, MUTED); captions[i].setGravity(Gravity.CENTER);
            cell.addView(readings[i], new LinearLayout.LayoutParams(-1, 0, 3));
            cell.addView(captions[i], new LinearLayout.LayoutParams(-1, 0, 2));
            row.addView(cell, new LinearLayout.LayoutParams(0, rowHeight, 1));
            cell.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { select(index); } });
        }
        addView(row, new LinearLayout.LayoutParams(-1, rowHeight));
        // Keep this slot measured even when closed: SurfaceView/AF coordinates never resize for a parameter tap.
        editor = new LinearLayout(activity); editor.setOrientation(VERTICAL); editor.setVisibility(INVISIBLE);
        editor.setPadding(dp(6), 0, dp(6), dp(4));
        FrameLayout heading = new FrameLayout(activity);
        title = fit("", 11, 18, WHITE); title.setGravity(Gravity.CENTER);
        status = text("", 10, MUTED); status.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        status.setMaxLines(2);
        close = text("⌄", 23, WHITE); close.setGravity(Gravity.CENTER); close.setContentDescription("收起参数调整");
        FrameLayout.LayoutParams headingTitle = new FrameLayout.LayoutParams(-1, dp(40), Gravity.CENTER);
        headingTitle.leftMargin = dp(94); headingTitle.rightMargin = dp(94);
        heading.addView(title, headingTitle);
        heading.addView(status, new FrameLayout.LayoutParams(dp(90), dp(40), Gravity.START | Gravity.CENTER_VERTICAL));
        heading.addView(close, new FrameLayout.LayoutParams(dp(44), dp(40), Gravity.END | Gravity.CENTER_VERTICAL));
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { collapse(); } });
        editor.addView(heading, new LinearLayout.LayoutParams(-1, dp(40)));
        numeric = new LinearLayout(activity); numeric.setGravity(Gravity.CENTER_VERTICAL);
        auto = text("A", 18, WHITE); auto.setGravity(Gravity.CENTER); auto.setBackground(round(0xff202020));
        auto.setContentDescription("切换为自动");
        LinearLayout.LayoutParams autoParams = new LinearLayout.LayoutParams(dp(44), dp(44)); autoParams.rightMargin = dp(10);
        numeric.addView(auto, autoParams); auto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                for (int i = 0; i < gestureValues.size(); i++) if (isAuto(gestureValues.get(i))) { commit(i); break; }
            }
        });
        ruler = new V3Ruler(activity);
        ruler.setListener(new V3Ruler.Listener() {
            @Override public void preview(int index) {
                if (selected < 0 || index < 0 || index >= gestureValues.size()) return;
                title.setText(expanded(KEYS[selected], gestureValues.get(index))); status.setText("松手应用");
            }
            @Override public void commit(int index) { V3ParameterBar.this.commit(index); }
        });
        numeric.addView(ruler, new LinearLayout.LayoutParams(0, -1, 1));
        editor.addView(numeric, new LinearLayout.LayoutParams(-1, 0, 1));
        choiceScroll = new HorizontalScrollView(activity); choiceScroll.setHorizontalScrollBarEnabled(false);
        choices = new LinearLayout(activity); choices.setGravity(Gravity.CENTER_VERTICAL);
        choiceScroll.setFillViewport(false);
        choiceScroll.addView(choices, new HorizontalScrollView.LayoutParams(-2, -1));
        editor.addView(choiceScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        addView(editor, new LinearLayout.LayoutParams(-1, editorHeight));
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, float size, int color) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setIncludeFontPadding(false); text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return text;
    }
    private TextView fit(String value, float min, float max, int color) {
        V3FitText text = new V3FitText(activity, min, max); text.setText(value); text.setTextColor(color);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return text;
    }
    public int getReservedHeight() { return rowHeight + editorHeight + dp(2); }
    public void openMode() { select(6); }
    public boolean isModeExpanded() { return selected == 6; }
    public String modeLabel() {
        V3CameraParameters.Snapshot mode = states[6];
        return mode == null || !connected || mode.current.length() == 0 ? "模式" : display("mode", mode.current);
    }
    public boolean canOpenMode() { return connected && !blocked && pendingKey == null && !disposed; }
    private GradientDrawable round(int color) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(22)); return bg; }
    private boolean isAuto(String value) { return selected >= 0 && V3CameraParameters.isAutoValue(KEYS[selected], value); }
    private String display(String key, String value) { return V3CameraParameters.display(key, value); }
    private String expanded(String key, String value) { return V3CameraParameters.displayExpanded(key, value); }
    private void select(int index) {
        if (disposed || blocked || !connected || pendingKey != null) return;
        if (selected == index) { collapse(); return; }
        selected = index; choiceSignature = ""; status.setText(""); boolean show = editor.getVisibility() != VISIBLE;
        editor.setVisibility(VISIBLE); render();
        if (show) { editor.setTranslationY(dp(12)); editor.animate().translationY(0).setDuration(160).start(); }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }
    public boolean collapse() {
        if (selected < 0) return false;
        ruler.cancelGesture();
        selected = -1; editor.animate().cancel(); editor.setTranslationY(0); editor.setVisibility(INVISIBLE); render(); return true;
    }
    public boolean isExpanded() { return selected >= 0; }
    public void refresh(Boolean isConnected, boolean isBlocked) {
        if (disposed) return;
        connected = !Boolean.FALSE.equals(isConnected); blocked = isBlocked;
        for (int i = 0; i < KEYS.length; i++) states[i] = source.read(KEYS[i]);
        if (!connected || blocked) {
            collapse();
            if (!connected) pendingKey = pendingValue = null;
        }
        if (pendingKey != null) {
            for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(pendingKey)) {
                if (states[i].error.length() != 0) {
                    status.setText(states[i].error); pendingKey = pendingValue = null;
                } else if (pendingValue.equals(states[i].current)) {
                    pendingKey = pendingValue = null; status.setText("相机已确认");
                } else if (SystemClock.uptimeMillis() - pendingSince > 8000) {
                    pendingKey = pendingValue = null; status.setText("未确认，请重试");
                }
                break;
            }
        }
        render();
    }
    private void render() {
        for (int i = 0; i < NAMES.length; i++) {
            V3CameraParameters.Snapshot state = states[i];
            String raw = state == null || !connected ? "" : state.current;
            String value = raw == null || raw.length() == 0 ? "—" : display(KEYS[i], raw);
            if (pendingKey != null && KEYS[i].equals(pendingKey)) value += " ·";
            readings[i].setText(value);
            int color = i == selected ? RED : state != null && state.writable ? WHITE : MUTED;
            readings[i].setTextColor(color); captions[i].setTextColor(i == selected ? RED : MUTED);
            cells[i].setContentDescription(NAMES[i] + " " + value + (state != null && !state.writable ? "，" + state.reason : ""));
            cells[i].setEnabled(connected && !blocked && pendingKey == null);
        }
        if (selected < 0 || states[selected] == null || ruler.isDragging()) return;
        V3CameraParameters.Snapshot state = states[selected];
        boolean writable = connected && !blocked && pendingKey == null && state.writable && !state.values.isEmpty();
        title.setText(expanded(KEYS[selected], state.current));
        if (pendingKey != null) status.setText("等待相机确认…");
        else if (!state.writable) {
            String reason = state.reason;
            status.setText(reason.contains("高精度快门") ? "请关闭高精度快门"
                    : reason.contains("拍摄模式") ? "请在相机上改模式"
                    : reason.contains("正在处理") ? "相机处理中"
                    : reason.contains("未提供") ? "暂无可调档位" : reason);
            status.setContentDescription(reason);
        }
        else if (status.getText().length() == 0 || "松手应用".contentEquals(status.getText())) status.setText("左右滑动调整");
        gestureValues.clear(); gestureValues.addAll(state.values);
        boolean chips = selected >= 4;
        title.setTranslationX(0);
        numeric.setVisibility(chips ? GONE : VISIBLE); choiceScroll.setVisibility(chips ? VISIBLE : GONE);
        if (chips) renderChoices(state, writable);
        else {
            ArrayList<String> labels = new ArrayList<String>(); for (String value : state.values) labels.add(display(KEYS[selected], value));
            ruler.bind(labels, Math.max(0, state.values.indexOf(state.current)), writable);
            boolean hasAuto = false; for (String value : state.values) hasAuto |= isAuto(value);
            title.setTranslationX(hasAuto ? dp(27) : 0);
            auto.setVisibility(hasAuto ? VISIBLE : GONE); auto.setEnabled(writable);
            auto.setTextColor(isAuto(state.current) ? RED : WHITE);
        }
    }
    private void renderChoices(V3CameraParameters.Snapshot state, boolean writable) {
        String signature = selected + ":" + state.current + ":" + state.values + ":" + writable;
        if (signature.equals(choiceSignature)) return;
        choiceSignature = signature; choices.removeAllViews();
        TextView selectedChip = null;
        for (final String value : state.values) {
            TextView chip = fit(expanded(KEYS[selected], value), 11, 13, value.equals(state.current) ? RED : WHITE);
            chip.setGravity(Gravity.CENTER); chip.setPadding(dp(14), 0, dp(14), 0); chip.setBackground(round(0xff202020));
            chip.setEnabled(writable); chip.setAlpha(writable ? 1f : .5f);
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(-2, dp(44)); item.rightMargin = dp(8);
            choices.addView(chip, item); chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { commit(gestureValues.indexOf(value)); }
            });
            if (value.equals(state.current)) selectedChip = chip;
        }
        if (selectedChip != null) {
            final View current = selectedChip; final int selection = selected;
            choiceScroll.post(new Runnable() {
                @Override public void run() {
                    if (!disposed && selected == selection && current.getParent() == choices)
                        choiceScroll.smoothScrollTo(Math.max(0, current.getLeft() - (choiceScroll.getWidth() - current.getWidth()) / 2), 0);
                }
            });
        }
    }
    private void commit(int index) {
        if (selected < 0 || disposed || blocked || !connected || pendingKey != null || index < 0 || index >= gestureValues.size()) return;
        String key = KEYS[selected], value = gestureValues.get(index);
        V3CameraParameters.Snapshot fresh = source.read(key);
        states[selected] = fresh;
        if (!fresh.writable || !fresh.values.contains(value)) { states[selected] = fresh; status.setText(fresh.reason); render(); return; }
        if (value.equals(fresh.current)) { status.setText("左右滑动调整"); render(); return; }
        if (!source.set(key, value)) { status.setText("当前无法调整，请重试"); render(); return; }
        pendingKey = key; pendingValue = value; pendingSince = SystemClock.uptimeMillis();
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); render();
    }
    public void dispose() { disposed = true; collapse(); ruler.setListener(null); pendingKey = pendingValue = null; }
}
