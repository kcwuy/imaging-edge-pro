package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Shared native appearance. Original Sony view IDs, event handlers and camera surfaces remain. */
public final class IosTheme {
    private static final String TAG = "IosTheme";
    private static final int BLUE = Color.rgb(0, 122, 255);
    private static final int LABEL = Color.rgb(28, 28, 30);
    private static final int SECONDARY = Color.rgb(142, 142, 147);
    private static final int LIGHT = Color.rgb(242, 242, 247);
    private static final int DARK = Color.rgb(11, 11, 15);
    private static final WeakHashMap<Activity, Page> PAGES = new WeakHashMap<Activity, Page>();
    private static final WeakHashMap<Activity, Boolean> ACTIVE = new WeakHashMap<Activity, Boolean>();
    private static final WeakHashMap<Activity, Integer> EPOCHS = new WeakHashMap<Activity, Integer>();
    private IosTheme() { }

    /** Called before CommonActivity's super.onCreate, before AppCompat resolves its theme. */
    public static void beforeCreate(Activity activity) {
        try {
            String original = "";
            int old = activity.getPackageManager().getActivityInfo(activity.getComponentName(), 0).theme;
            if (old != 0) original = activity.getResources().getResourceEntryName(old);
            if (original.contains("Translucent")) return;
            String name = isDark(activity) ? "ios_base_camera" : "ios_base_light";
            if (original.contains("OverlayActionBar")) name += "_overlay";
            int theme = activity.getResources().getIdentifier(name, "style", activity.getPackageName());
            if (theme != 0) activity.setTheme(theme);
        } catch (Exception error) { Log.w(TAG, "Native theme selection failed", error); }
    }

    /** Called by CommonActivity; post waits for the concrete Activity's onResume to finish. */
    public static void onResume(final Activity activity) {
        if (activity == null || activity.isDestroyed()) return;
        ACTIVE.put(activity, Boolean.TRUE);
        Integer previous = EPOCHS.get(activity);
        final int epoch = previous == null ? 1 : previous + 1;
        EPOCHS.put(activity, epoch);
        final View decor = activity.getWindow().getDecorView();
        decor.post(new Runnable() {
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (!Boolean.TRUE.equals(ACTIVE.get(activity))
                        || !Integer.valueOf(epoch).equals(EPOCHS.get(activity))) return;
                Page page = PAGES.get(activity);
                if (page == null) {
                    page = new Page(activity);
                    PAGES.put(activity, page);
                    decor.getViewTreeObserver().addOnGlobalLayoutListener(page);
                }
                page.apply();
                callAdapter("IosHome", "install", activity);
                callAdapter("IosRemote", "apply", activity);
            }
        });
    }

    public static void onDestroy(Activity activity) {
        ACTIVE.remove(activity);
        EPOCHS.remove(activity);
        callAdapter("IosHome", "destroy", activity);
        Page page = PAGES.remove(activity);
        if (page == null) return;
        try {
            ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(page);
        } catch (RuntimeException error) { Log.w(TAG, "Appearance observer cleanup failed", error); }
    }

    public static void onPause(Activity activity) {
        ACTIVE.put(activity, Boolean.FALSE);
        callAdapter("IosHome", "pause", activity);
    }

    public static void onConfigurationChanged(Activity activity) {
        if (Boolean.TRUE.equals(ACTIVE.get(activity))) onResume(activity);
    }

    private static boolean isDark(Activity activity) {
        String name = activity.getClass().getName();
        return name.contains("remotecontrol.") || name.contains("ptpipremotecontrol.")
                || name.contains("PreviewModeActivity") || name.contains("ContShootPreviewActivity")
                || name.contains(".contentviewer.detail.")
                || (name.contains(".transfer.") && name.contains(".detail."));
    }

    private static void callAdapter(String type, String method, Activity activity) {
        try {
            Class.forName("com.sony.playmemories.mobile.redesign." + type)
                    .getMethod(method, Activity.class).invoke(null, activity);
        } catch (ClassNotFoundException optional) { }
        catch (Exception error) { Log.e(TAG, type + " adapter failed", error); }
    }

    private static final class Page implements ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<Activity> activity;
        final Set<View> styled = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
        final boolean dark;
        boolean applying;
        Page(Activity host) { activity = new WeakReference<Activity>(host); dark = isDark(host); }
        public void onGlobalLayout() { apply(); }

        void apply() {
            Activity host = activity.get();
            if (applying || host == null || host.isDestroyed() || host.isFinishing()) return;
            applying = true;
            try {
                Window window = host.getWindow();
                int background = dark ? DARK : LIGHT;
                window.setStatusBarColor(background);
                window.setNavigationBarColor(background);
                if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
                View decor = window.getDecorView();
                int flags = decor.getSystemUiVisibility();
                int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                decor.setSystemUiVisibility(dark ? flags & ~lightFlags : flags | lightFlags);
                styleTree(decor, host);
            } catch (RuntimeException error) { Log.e(TAG, "Native appearance update failed", error); }
            finally { applying = false; }
        }

        void styleTree(View view, Activity host) {
            if (styled.add(view)) style(view, host);
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) styleTree(group.getChildAt(i), host);
            }
        }

        void style(View view, Activity host) {
            String name = "";
            try { if (view.getId() != View.NO_ID) name = host.getResources().getResourceEntryName(view.getId()); }
            catch (RuntimeException ignored) { }
            if (view.getClass().getName().contains("Toolbar")) styleToolbar(view, host);
            if (dark && "settings_menu_relative_layout".equals(name)) {
                view.setBackground(rounded(Color.rgb(28, 28, 30), dp(host, 18)));
            }
            if (view instanceof TextView) {
                TextView text = (TextView) view;
                Typeface prior = text.getTypeface();
                text.setTypeface(Typeface.create("sans-serif", prior == null ? Typeface.NORMAL : prior.getStyle()));
                // This row is shared by the light app settings and the dark camera menu.
                if (dark && "settings_menu_text".equals(name)) text.setTextColor(Color.WHITE);
                if (dark && "settings_menu_current_value".equals(name)) text.setTextColor(Color.rgb(174, 174, 178));
            }
            if (view instanceof Switch) {
                Switch control = (Switch) view;
                control.setShowText(false);
                control.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
                control.setTrackTintList(new ColorStateList(new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {Color.rgb(52, 199, 89), Color.rgb(199, 199, 204)}));
            } else if (view instanceof CompoundButton) {
                ((CompoundButton) view).setButtonTintList(ColorStateList.valueOf(BLUE));
            } else if (!dark && view instanceof Button) {
                Button button = (Button) view;
                button.setAllCaps(false);
                button.setElevation(0);
                button.setStateListAnimator(null);
            }
            if (!dark && view instanceof EditText) {
                EditText input = (EditText) view;
                input.setTextColor(LABEL);
                input.setHintTextColor(SECONDARY);
                input.setBackground(rounded(Color.WHITE, dp(host, 13)));
                input.setPadding(dp(host, 14), dp(host, 12), dp(host, 14), dp(host, 12));
            }
            if (!dark && view instanceof ProgressBar) {
                ProgressBar progress = (ProgressBar) view;
                progress.setProgressTintList(ColorStateList.valueOf(BLUE));
                progress.setIndeterminateTintList(ColorStateList.valueOf(BLUE));
                if (progress instanceof SeekBar) ((SeekBar) progress).setThumbTintList(ColorStateList.valueOf(BLUE));
            }
        }

        void styleToolbar(View toolbar, Activity host) {
            int text = dark ? Color.WHITE : LABEL;
            toolbar.setBackgroundColor(dark ? DARK : LIGHT);
            toolbar.setElevation(0);
            try {
                toolbar.getClass().getMethod("setTitleTextColor", int.class).invoke(toolbar, text);
                toolbar.getClass().getMethod("setSubtitleTextColor", int.class).invoke(toolbar, dark ? Color.LTGRAY : SECONDARY);
                for (String kind : new String[] {"Navigation", "Overflow"}) {
                    Method getter = toolbar.getClass().getMethod("get" + kind + "Icon");
                    Drawable icon = (Drawable) getter.invoke(toolbar);
                    if (icon != null) {
                        Drawable.ConstantState state = icon.getConstantState();
                        Drawable tinted = state == null ? icon.mutate() : state.newDrawable(host.getResources()).mutate();
                        tinted.setTint(dark ? Color.WHITE : BLUE);
                        toolbar.getClass().getMethod("set" + kind + "Icon", Drawable.class).invoke(toolbar, tinted);
                    }
                }
            } catch (Exception optional) { }
        }
    }

    private static int dp(Activity host, int value) { return Math.round(value * host.getResources().getDisplayMetrics().density); }
    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(radius); return drawable;
    }
}
