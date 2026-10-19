package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

/** Reuses a verified native session instead of rediscovering a camera already owned by Sony. */
public final class V3SessionRouter {
    private static final String PREFIX = "com.sony.playmemories.mobile.";
    public static final int NONE = 0, READY = 1, WAIT = 2, STALE = 3;
    private V3SessionRouter() { }
    public static final class Snapshot {
        public final int state;
        public final Object camera;
        public final boolean ptp;
        public final String detail;
        Snapshot(int state, Object camera, boolean ptp, String detail) {
            this.state = state; this.camera = camera; this.ptp = ptp; this.detail = detail;
        }
    }
    /** One queued navigation, bound to the same camera and user task; never retries an action. */
    public static final class Request {
        public final Object camera;
        public final String task;
        private final long deadline;
        private boolean consumed;
        public Request(Object camera, String task, long now) { this.camera = camera; this.task = task; deadline = now + 12000L; }
        public int step(Object currentCamera, String currentTask, int state, boolean blocked, long now) {
            if (consumed) return NONE;
            if (currentCamera != camera || !task.equals(currentTask) || now >= deadline || state == NONE || state == STALE) {
                consumed = true; return STALE;
            }
            if (state == WAIT || blocked) return WAIT;
            consumed = true; return READY;
        }
        public void cancel() { consumed = true; }
    }
    public static Snapshot inspect(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return empty();
            Class<?> managers = load(activity, "camera.CameraManagerUtil");
            if (!Boolean.TRUE.equals(call(managers, "isSingleMode"))) return empty();
            Object manager = field(managers, "sInstance");
            if (manager == null) return empty();
            Object camera = call(manager, "getPrimaryCamera");
            if (camera == null) return empty();
            Object wifi = call(load(activity, "wifi.control.WifiControlUtil"), "getInstance");
            if (!"Connected".equals(String.valueOf(call(wifi, "getWifiControlState")))) return empty();
            String state = String.valueOf(field(camera, "mState"));
            if (!healthy(state)) return empty();
            Object client = call(camera, "getPtpIpClient");
            if (client == null) return new Snapshot("ApplicationSwitching".equals(state) ? WAIT : READY, camera, false, state);
            String status = String.valueOf(field(client, "mPtpIpClientStatus"));
            boolean tornDown = Boolean.TRUE.equals(field(client, "mTearDown"));
            boolean opened = Boolean.TRUE.equals(field(camera, "mIsPtpSessionOpened"));
            int result = classify(status, tornDown, opened);
            return new Snapshot(result, camera, true, status);
        } catch (Exception unavailable) { return empty(); }
        catch (LinkageError unavailable) { return empty(); }
    }
    /** Pure state decision; never treats Wi-Fi association as a usable PTP session. */
    public static int classify(String status, boolean tornDown, boolean opened) {
        if (tornDown) return STALE;
        if ("Initialized".equals(status)) return opened ? READY : WAIT;
        if ("SwitchingSession".equals(status) || "Uninitialized".equals(status)) return WAIT;
        return STALE;
    }
    /** Uses Sony's original cameraAdded dispatcher (timer cleanup, model selection, native function).
     * True means accepted; an invoked native callback is never replayed after an exception. */
    public static boolean reuseWifi(Activity activity, Object expectedCamera) {
        if (activity == null || !activity.getClass().getName().equals(PREFIX + "devicelist.WiFiActivity")) return false;
        Snapshot current = inspect(activity);
        if (current.state != READY || current.camera != expectedCamera) return false;
        try {
            Object functions = field(activity, "mFunctions"), model = field(current.camera, "mModel");
            if (!(functions instanceof Map)) return false;
            Object function = ((Map<?, ?>) functions).get(model);
            if (function == null) return false;
            if (Boolean.TRUE.equals(call(function, "isRunning"))) return true;
            call(activity, "cameraAdded", current.camera);
            return true;
        } catch (InvocationTargetException alreadyInvoked) { return true; }
        catch (Exception unavailable) { return false; }
        catch (LinkageError unavailable) { return false; }
    }
    private static boolean healthy(String state) {
        return "Connected".equals(state) || "AuthenticationSucceeded".equals(state)
                || "GetVersionSucceeded".equals(state) || "StartEventNotificationSucceeded".equals(state)
                || "WebApiEnabled".equals(state) || "ApplicationSwitching".equals(state);
    }
    private static Snapshot empty() { return new Snapshot(NONE, null, false, ""); }
    private static Class<?> load(Activity activity, String suffix) throws Exception { return Class.forName(PREFIX + suffix, false, activity.getClassLoader()); }
    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field value = current.getDeclaredField(name); value.setAccessible(true); return value.get(target instanceof Class ? null : target); }
            catch (NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static Object call(Object target, String name, Object... args) throws Exception {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        for (Method method : type.getMethods()) {
            if (!name.equals(method.getName()) || args.length != method.getParameterTypes().length) continue;
            boolean matches = true;
            for (int i = 0; i < args.length; i++) if (args[i] != null && !method.getParameterTypes()[i].isInstance(args[i])) matches = false;
            if (matches) { method.setAccessible(true); return method.invoke(target instanceof Class ? null : target, args); }
        }
        throw new NoSuchMethodException(name);
    }
}
