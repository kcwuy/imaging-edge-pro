package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** V3 entry point; the tested connection lifecycle is deliberately owned elsewhere. */
public final class V3Theme {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private V3Theme() { }
    public static void beforeCreate(Activity activity) { IosTheme.beforeCreate(activity); V3TabMotion.configure(activity); }
    public static void onResume(final Activity activity) {
        V3Shell.beforeResume(activity);
        V3TabMotion.onResume(activity);
        IosTheme.onResume(activity);
        MAIN.post(new Runnable() { public void run() {
            if (activity.isDestroyed() || activity.isFinishing() || !activity.hasWindowFocus()) {
                // onResume precedes focus; attachment, rather than focus, is the installation gate.
                if (activity.isDestroyed() || activity.isFinishing()) return;
            }
            try { V3Shell.install(activity); } catch (RuntimeException e) { Log.e("V3Theme", "Shell installation failed", e); }
            adapter("V3Remote", "apply", activity);
            adapter("V3Photos", "apply", activity);
            adapter("V3Settings", "apply", activity);
        }});
    }
    public static void onPause(Activity activity) {
        V3Shell.pause(activity); adapter("V3Remote", "pause", activity); adapter("V3Photos", "pause", activity);
        adapter("V3Settings", "pause", activity);
        IosTheme.onPause(activity);
    }
    public static void onDestroy(Activity activity) {
        V3Shell.destroy(activity); adapter("V3Remote", "destroy", activity); adapter("V3Photos", "destroy", activity);
        adapter("V3Settings", "destroy", activity);
        IosTheme.onDestroy(activity);
    }
    public static void onConfigurationChanged(Activity activity) {
        IosTheme.onConfigurationChanged(activity); onResume(activity);
    }
    public static boolean onBack(Activity activity) {
        if (V3Shell.onBack(activity)) return true;
        return back("V3Remote", activity) || back("V3Photos", activity);
    }
    private static void adapter(String type, String method, Activity activity) {
        try { Class.forName("com.sony.playmemories.mobile.redesign." + type).getMethod(method, Activity.class).invoke(null, activity); }
        catch (ClassNotFoundException optional) { }
        catch (Exception error) { Log.e("V3Theme", type + "." + method + " failed", error); }
    }
    private static boolean back(String type, Activity activity) {
        try { return Boolean.TRUE.equals(Class.forName("com.sony.playmemories.mobile.redesign." + type).getMethod("onBack", Activity.class).invoke(null, activity)); }
        catch (Exception optional) { return false; }
    }
}
