package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Adds only the existing background preference to an explicitly reserved XML slot. */
public final class IosHome {
    private IosHome() { }

    public static void install(Activity activity) {
        if (activity == null || activity.isDestroyed() || activity.isFinishing()) return;
        int id = activity.getResources().getIdentifier("ioshg_keepalive_slot", "id", activity.getPackageName());
        View slotView = id == 0 ? null : activity.findViewById(id);
        if (!(slotView instanceof LinearLayout)) return;
        LinearLayout slot = (LinearLayout) slotView;
        if (slot.getTag() instanceof Binding) {
            ((Binding) slot.getTag()).start();
            return;
        }
        try {
            Binding binding = new Binding(activity, slot);
            slot.setTag(binding);
            binding.start();
        } catch (RuntimeException error) { Log.w("IosHome", "Background preference card unavailable", error); }
    }

    public static void pause(Activity activity) {
        Binding binding = findBinding(activity);
        if (binding != null) binding.stop();
    }

    public static void destroy(Activity activity) {
        Binding binding = findBinding(activity);
        if (binding == null) return;
        binding.stop();
        binding.slot.removeOnAttachStateChangeListener(binding);
        binding.toggle.setOnCheckedChangeListener(null);
        if (binding.slot.getTag() == binding) binding.slot.setTag(null);
    }

    private static Binding findBinding(Activity activity) {
        if (activity == null) return null;
        int id = activity.getResources().getIdentifier("ioshg_keepalive_slot", "id", activity.getPackageName());
        View slot = id == 0 ? null : activity.findViewById(id);
        return slot != null && slot.getTag() instanceof Binding ? (Binding) slot.getTag() : null;
    }

    private static final class Binding implements Runnable, View.OnAttachStateChangeListener {
        final WeakReference<Activity> activity;
        final LinearLayout slot;
        final TextView description;
        final Switch toggle;
        final Handler handler = new Handler(Looper.getMainLooper());
        boolean syncing;
        boolean active;

        Binding(final Activity activity, LinearLayout slot) {
            this.activity = new WeakReference<Activity>(activity);
            this.slot = slot;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(16), dp(18), dp(16));
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(color("ioshg_surface", Color.WHITE));
            surface.setCornerRadius(dp(20));
            row.setBackground(surface);
            LinearLayout words = new LinearLayout(activity);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(activity, 16, color("ioshg_text", 0xff1d1d1f));
            title.setText(string("ioshg_keepalive_title"));
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            words.addView(title, new LinearLayout.LayoutParams(-1, -2));
            description = text(activity, 13, color("ioshg_secondary", 0xff6e6e73));
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
            subtitleParams.topMargin = dp(5);
            words.addView(description, subtitleParams);
            row.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
            toggle = new Switch(activity);
            toggle.setShowText(false);
            toggle.setContentDescription(title.getText());
            toggle.setMinHeight(dp(48));
            toggle.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
            toggle.setTrackTintList(new ColorStateList(
                    new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                    new int[] { color("ioshg_blue", 0xff007aff), 0xffc7c7cc }));
            LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(-2, dp(48));
            toggleParams.leftMargin = dp(12);
            row.addView(toggle, toggleParams);
            slot.addView(row, new LinearLayout.LayoutParams(-1, -2));
            toggle.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton button, boolean enabled) {
                    if (syncing) return;
                    Activity current = Binding.this.activity.get();
                    if (current == null || current.isDestroyed()) return;
                    ConnectionKeeper.setEnabled(current, enabled);
                    update();
                }
            });
            slot.addOnAttachStateChangeListener(this);
        }

        void start() {
            active = true;
            handler.removeCallbacks(this);
            update();
            if (slot.isAttachedToWindow()) handler.postDelayed(this, 1500);
        }

        void stop() {
            active = false;
            handler.removeCallbacks(this);
        }

        void update() {
            Activity current = activity.get();
            if (current == null || current.isDestroyed()) return;
            boolean enabled = ConnectionKeeper.isEnabled(current);
            boolean connected = ConnectionKeeper.hasCameraConnection(current);
            boolean running = ConnectionKeeper.isRunning();
            syncing = true;
            toggle.setChecked(enabled);
            syncing = false;
            // A saved preference can be changed before connecting; text does not claim a live session.
            String key = !enabled ? "ioshg_keepalive_off"
                    : !connected ? "ioshg_keepalive_waiting"
                    : running ? "ioshg_keepalive_running" : "ioshg_keepalive_starting";
            CharSequence message = string(key);
            if (!message.toString().contentEquals(description.getText())) description.setText(message);
        }

        @Override public void run() {
            Activity current = activity.get();
            if (!active || current == null || current.isDestroyed() || current.isFinishing() || !slot.isAttachedToWindow()) return;
            if (slot.isShown() && current.hasWindowFocus()) update();
            handler.postDelayed(this, 1500);
        }

        @Override public void onViewAttachedToWindow(View view) {
            handler.removeCallbacks(this);
            if (active) handler.post(this);
        }

        @Override public void onViewDetachedFromWindow(View view) { handler.removeCallbacks(this); }

        TextView text(Context context, int size, int color) {
            TextView view = new TextView(context);
            view.setTextSize(size);
            view.setTextColor(color);
            view.setIncludeFontPadding(false);
            return view;
        }

        int dp(int value) { return Math.round(value * slot.getResources().getDisplayMetrics().density); }

        int color(String key, int fallback) {
            Activity current = activity.get();
            int id = current == null ? 0 : current.getResources().getIdentifier(key, "color", current.getPackageName());
            return id == 0 ? fallback : current.getColor(id);
        }

        String string(String key) {
            Activity current = activity.get();
            int id = current == null ? 0 : current.getResources().getIdentifier(key, "string", current.getPackageName());
            return id == 0 ? key : current.getString(id);
        }
    }
}
