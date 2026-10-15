package com.sony.playmemories.mobile.redesign;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.Collection;

/** Owns only the lifecycle of the original Sony connection; implements no camera protocol. */
public final class ConnectionKeeper {
    private static final String TAG = "ConnectionKeeper";
    private static final String PREFS = "iep_connection_keeper";
    private static final String ENABLED = "background_connection_enabled";
    private static final String PREFIX = "com.sony.playmemories.mobile.";
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long POLL_MS = 1000L;
    private static final long RETRY_MS = 3000L;
    private static final long BACKGROUND_START_TIMEOUT_MS = 2000L;

    private static volatile Context appContext;
    private static WeakReference<Activity> currentActivity = new WeakReference<Activity>(null);
    private static WeakReference<ConnectionService> currentService = new WeakReference<ConnectionService>(null);
    private static boolean resumed;
    private static boolean serviceReady;
    private static boolean startPending;
    private static boolean pendingAccepted;
    private static boolean retained;
    private static boolean backgroundStartWaiting;
    private static boolean returningToForeground;
    private static int waitingGeneration;
    private static long waitingDeadline;
    private static boolean blockUntilDisconnected;
    private static boolean cleaningOriginal;
    private static int generation;
    private static int serviceGeneration;
    private static long lastStartAttempt;
    private static volatile boolean reflectionFailureLogged;

    private static final Runnable START_TIMEOUT = new Runnable() {
        @Override public void run() {
            synchronized (LOCK) {
                if (!backgroundStartWaiting || waitingGeneration != generation) return;
                long remaining = waitingDeadline - SystemClock.elapsedRealtime();
                if (remaining > 0L) {
                    MAIN.postDelayed(this, remaining);
                    return;
                }
            }
            // No acknowledgement arrived. Release the deferred original cleanup, with generation
            // invalidation before stopService so a late onStartCommand cannot resurrect it.
            stopOwnedService(true);
        }
    };

    private static final Runnable POLL = new Runnable() {
        @Override public void run() {
            Activity activity;
            synchronized (LOCK) {
                activity = resumed ? currentActivity.get() : null;
            }
            if (!usable(activity) || !isEnabled(activity)) return;
            if (hasCameraConnection(activity)) {
                expireForegroundPending();
                requestService(activity);
            } else {
                synchronized (LOCK) { blockUntilDisconnected = false; }
                stopOwnedService(false);
            }
            synchronized (LOCK) {
                if (resumed && currentActivity.get() == activity) {
                    MAIN.postDelayed(this, POLL_MS);
                }
            }
        }
    };

    private ConnectionKeeper() { }

    public static void setEnabled(Activity activity, boolean enabled) {
        if (activity == null) return;
        remember(activity);
        try {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(ENABLED, enabled).apply();
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not save background connection setting", error);
            stopOwnedService(true);
            return;
        }
        MAIN.removeCallbacks(POLL);
        if (!enabled) {
            stopOwnedService(true);
            return;
        }
        boolean isResumed;
        synchronized (LOCK) {
            isResumed = resumed && currentActivity.get() == activity;
            lastStartAttempt = 0L;
        }
        if (isResumed && usable(activity)) MAIN.post(POLL);
    }

    public static boolean isEnabled(Context context) {
        if (context == null) return false;
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(ENABLED, true);
        } catch (RuntimeException error) {
            Log.e(TAG, "Could not read background connection setting", error);
            return false;
        }
    }

    /** True only after this process received a successful startForeground acknowledgement. */
    public static boolean isRunning() {
        synchronized (LOCK) { return serviceReady && currentService.get() != null; }
    }

    /** Read existing public state. Never invoke BaseCamera.isEnabled(), which is always true. */
    public static boolean hasCameraConnection(Context context) {
        if (context == null) return false;
        try {
            ClassLoader loader = context.getClassLoader();
            Class<?> wifiClass = Class.forName(PREFIX + "wifi.control.WifiControlUtil", false, loader);
            Object wifi = wifiClass.getMethod("getInstance").invoke(null);
            Object wifiState = wifiClass.getMethod("getWifiControlState").invoke(wifi);
            Class<?> wifiStates = Class.forName(PREFIX + "wifi.control.EnumWifiControlState", false, loader);
            boolean tethering = wifiState == wifiStates.getField("TetheringEnabled").get(null);
            if (wifiState != wifiStates.getField("Connected").get(null) && !tethering) return false;

            Class<?> managerClass = Class.forName(PREFIX + "camera.CameraManagerUtil", false, loader);
            Object manager = managerClass.getField("sInstance").get(null);
            if (manager == null) return false;
            if (tethering && !Class.forName(PREFIX + "camera.TetheringMultiCameraManager", false, loader)
                    .isInstance(manager)) return false;
            Object camera = manager.getClass().getMethod("getPrimaryCamera").invoke(manager);
            if (camera == null) return false;
            Object cameraState = camera.getClass().getField("mState").get(camera);
            if (!(cameraState instanceof Enum<?>)) return false;
            String state = ((Enum<?>) cameraState).name();
            // These are the healthy states in this exact APK. A switching session is still owned
            // by the existing protocol. Unknown and explicit failure states fail closed.
            return "Connected".equals(state) || "AuthenticationSucceeded".equals(state)
                    || "GetVersionSucceeded".equals(state) || "StartEventNotificationSucceeded".equals(state)
                    || "WebApiEnabled".equals(state) || "ApplicationSwitching".equals(state);
        } catch (Exception error) {
            logReflectionFailure(error);
            return false;
        } catch (LinkageError error) {
            logReflectionFailure(error);
            return false;
        }
    }

    public static void onResume(Activity activity) {
        if (!usable(activity)) return;
        remember(activity);
        synchronized (LOCK) {
            currentActivity = new WeakReference<Activity>(activity);
            resumed = true;
            returningToForeground = false;
        }
        MAIN.removeCallbacks(POLL);
        if (isEnabled(activity)) MAIN.post(POLL);
    }

    /** Called before the original onPause; attempt promotion while the Activity is still visible. */
    public static void onPause(Activity activity) {
        if (activity == null) return;
        remember(activity);
        if (usable(activity) && isEnabled(activity) && hasCameraConnection(activity)) {
            requestService(activity);
        }
        synchronized (LOCK) {
            returningToForeground = false;
            if (currentActivity.get() == activity) {
                resumed = false;
                currentActivity.clear();
            }
        }
        MAIN.removeCallbacks(POLL);
    }

    /** Called after the last foreground context is removed. A pending accepted start defers
     * original cleanup for at most two seconds; it does not claim a running service or retention. */
    public static boolean retainInBackground(Activity activity) {
        if (!usable(activity) || !isEnabled(activity) || !hasCameraConnection(activity)) return false;
        remember(activity);
        synchronized (LOCK) {
            if (blockUntilDisconnected) return false;
            if (serviceReady && currentService.get() != null) {
                retained = true;
                cancelStartWaiting();
                return true;
            }
            if (startPending && pendingAccepted) {
                if (!backgroundStartWaiting) {
                    backgroundStartWaiting = true;
                    waitingGeneration = generation;
                    waitingDeadline = SystemClock.elapsedRealtime() + BACKGROUND_START_TIMEOUT_MS;
                    MAIN.removeCallbacks(START_TIMEOUT);
                    MAIN.postDelayed(START_TIMEOUT, BACKGROUND_START_TIMEOUT_MS);
                }
                return true;
            }
            return false;
        }
    }

    /** Consumes a successful background hold, keeping the service ready for the next transition. */
    public static boolean resumeRetained() {
        Context context = appContext;
        boolean hadOwnership;
        synchronized (LOCK) { hadOwnership = retained || backgroundStartWaiting; }
        if (!hadOwnership) return false;
        boolean valid = context != null && isEnabled(context) && hasCameraConnection(context);
        synchronized (LOCK) {
            boolean acknowledged = retained && serviceReady && currentService.get() != null;
            boolean waiting = backgroundStartWaiting && startPending && pendingAccepted
                    && waitingGeneration == generation;
            valid = valid && (acknowledged || waiting) && !blockUntilDisconnected;
            retained = false;
            cancelStartWaiting();
            // CommonActivity.onStart registers its foreground context after this hook. Protect
            // that short interval from a failed service callback finishing the returning UI.
            returningToForeground = true;
        }
        if (!valid) stopOwnedService(false);
        return valid;
    }

    /** At the entrance to the original onAllContextsRemoved, before any original teardown. */
    public static void onOriginalCleanup() {
        MAIN.removeCallbacks(POLL);
        synchronized (LOCK) {
            blockUntilDisconnected = true;
            returningToForeground = false;
        }
        stopOwnedService(false);
    }

    /** The notification's explicit disconnect action calls the unmodified Sony disconnect API. */
    public static void disconnect(Context context) {
        if (context == null) return;
        remember(context);
        boolean wasRetained;
        synchronized (LOCK) {
            wasRetained = retained || backgroundStartWaiting;
            blockUntilDisconnected = true;
        }
        stopOwnedService(false);
        disconnectOriginal(context);
        if (wasRetained) cleanupOriginalIfBackground(context);
    }

    static void onServiceCreated(ConnectionService service) {
        remember(service);
    }

    static boolean onServiceAccepted(ConnectionService service, int token) {
        synchronized (LOCK) {
            if (token != generation || !startPending || blockUntilDisconnected) return false;
            currentService = new WeakReference<ConnectionService>(service);
            serviceGeneration = token;
            return true;
        }
    }

    static boolean acceptServiceStart(Context context, int token) {
        synchronized (LOCK) {
            if (token != generation || !startPending || blockUntilDisconnected) return false;
        }
        return isEnabled(context) && hasCameraConnection(context);
    }

    static boolean onServiceReady(ConnectionService service, int token) {
        if (!isEnabled(service) || !hasCameraConnection(service)) return false;
        boolean inBackground = !hasForegroundContext(service);
        synchronized (LOCK) {
            if (token != generation || !startPending || blockUntilDisconnected
                    || serviceGeneration != token || currentService.get() != service) return false;
            // The Looper can dispatch a delayed ACK ahead of the timeout Runnable. Check the
            // monotonic deadline here too, rather than treating callback delivery order as time.
            if (backgroundStartWaiting && SystemClock.elapsedRealtime() > waitingDeadline) return false;
            startPending = false;
            pendingAccepted = false;
            serviceReady = true;
            if (backgroundStartWaiting && waitingGeneration == token) {
                retained = inBackground && !resumed && !returningToForeground;
                cancelStartWaiting();
            }
            return true;
        }
    }

    static boolean hasNewerActiveStart(int token) {
        synchronized (LOCK) { return token != generation && (pendingAccepted || serviceReady); }
    }

    static void onServiceRejected(int token) {
        boolean cleanup = false;
        synchronized (LOCK) {
            // Do not cancel a newer request when an old Intent arrives after teardown.
            if (generation == token && !serviceReady) {
                cleanup = retained || backgroundStartWaiting;
                clearServiceState();
            }
        }
        if (cleanup && appContext != null) cleanupOriginalIfBackground(appContext);
    }

    static void onServiceFailure(ConnectionService service, int token, Throwable error) {
        Log.e(TAG, "Foreground connection service could not be started", error);
        onServiceEnded(service, token);
    }

    static void onServiceEnded(ConnectionService service, int token) {
        boolean wasRetained;
        synchronized (LOCK) {
            if (token != generation || serviceGeneration != token || currentService.get() != service) return;
            wasRetained = retained || backgroundStartWaiting;
            clearServiceState();
        }
        if (wasRetained) cleanupOriginalIfBackground(service);
    }

    static void onConnectionLost(ConnectionService service, int token) {
        boolean wasRetained;
        synchronized (LOCK) {
            if (token != generation || serviceGeneration != token || currentService.get() != service) return;
            wasRetained = retained || backgroundStartWaiting;
            blockUntilDisconnected = false;
        }
        stopOwnedService(false);
        if (wasRetained) cleanupOriginalIfBackground(service);
    }

    private static boolean usable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void remember(Context context) {
        Context application = context.getApplicationContext();
        appContext = application == null ? context : application;
    }

    private static void requestService(Activity activity) {
        if (!usable(activity) || !isEnabled(activity) || !hasCameraConnection(activity)) return;
        remember(activity);
        int token;
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (serviceReady || startPending || blockUntilDisconnected
                    || (lastStartAttempt != 0L && now - lastStartAttempt < RETRY_MS)) return;
            lastStartAttempt = now;
            startPending = true;
            pendingAccepted = false;
            token = ++generation;
        }
        try {
            Intent intent = new Intent(activity, ConnectionService.class)
                    .setAction(ConnectionService.ACTION_START)
                    .putExtra(ConnectionService.EXTRA_GENERATION, token);
            if (activity.startForegroundService(intent) == null) {
                throw new IllegalStateException("Android did not accept the foreground service");
            }
            synchronized (LOCK) {
                if (generation == token && startPending) pendingAccepted = true;
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Foreground connection service request failed", error);
            synchronized (LOCK) {
                if (generation == token) clearServiceState();
            }
        }
    }

    private static void clearServiceState() {
        generation++;
        serviceReady = false;
        startPending = false;
        pendingAccepted = false;
        retained = false;
        cancelStartWaiting();
        currentService.clear();
        serviceGeneration = 0;
    }

    private static void cancelStartWaiting() {
        backgroundStartWaiting = false;
        waitingGeneration = 0;
        waitingDeadline = 0L;
        MAIN.removeCallbacks(START_TIMEOUT);
    }

    private static void expireForegroundPending() {
        boolean expired;
        synchronized (LOCK) {
            expired = resumed && startPending && pendingAccepted && !serviceReady
                    && !backgroundStartWaiting
                    && SystemClock.elapsedRealtime() - lastStartAttempt >= RETRY_MS;
        }
        if (expired) stopOwnedService(false);
    }

    private static void stopOwnedService(boolean cleanupBackground) {
        Context context = appContext;
        ConnectionService service;
        boolean wasRetained;
        synchronized (LOCK) {
            service = currentService.get();
            wasRetained = retained || backgroundStartWaiting;
            if (service == null && !wasRetained && !startPending && !serviceReady) return;
            clearServiceState();
        }
        if (context != null) {
            try { context.stopService(new Intent(context, ConnectionService.class)); }
            catch (RuntimeException error) { Log.e(TAG, "Could not stop connection service", error); }
        }
        if (service != null) service.stopConnectionWork();
        if (cleanupBackground && wasRetained && context != null) cleanupOriginalIfBackground(context);
    }

    private static void disconnectOriginal(Context context) {
        try {
            Class<?> wifiClass = Class.forName(PREFIX + "wifi.control.WifiControlUtil", false,
                    context.getClassLoader());
            Object wifi = wifiClass.getMethod("getInstance").invoke(null);
            wifiClass.getMethod("disconnectFromCamera").invoke(wifi);
        } catch (Exception error) { logReflectionFailure(error); }
        catch (LinkageError error) { logReflectionFailure(error); }
    }

    private static void cleanupOriginalIfBackground(Context context) {
        synchronized (LOCK) {
            if (cleaningOriginal || resumed || returningToForeground) return;
            cleaningOriginal = true;
        }
        try {
            Class<?> managerClass = Class.forName(PREFIX + "ContextManager", false, context.getClassLoader());
            Object manager = managerClass.getField("sInstance").get(null);
            if (manager == null) return;
            Object queue = managerClass.getField("mForegroundContexts").get(manager);
            if (!(queue instanceof Collection<?>) || !((Collection<?>) queue).isEmpty()) return;
            managerClass.getMethod("onAllContextsRemoved", boolean.class).invoke(manager, false);
        } catch (Exception error) {
            logReflectionFailure(error);
            disconnectOriginal(context);
        } catch (LinkageError error) {
            logReflectionFailure(error);
            disconnectOriginal(context);
        } finally {
            synchronized (LOCK) { cleaningOriginal = false; }
        }
    }

    private static boolean hasForegroundContext(Context context) {
        synchronized (LOCK) { if (resumed || returningToForeground) return true; }
        try {
            Class<?> managerClass = Class.forName(PREFIX + "ContextManager", false, context.getClassLoader());
            Object manager = managerClass.getField("sInstance").get(null);
            if (manager == null) return false;
            Object queue = managerClass.getField("mForegroundContexts").get(manager);
            return queue instanceof Collection<?> && !((Collection<?>) queue).isEmpty();
        } catch (Exception error) { logReflectionFailure(error); }
        catch (LinkageError error) { logReflectionFailure(error); }
        // If original lifecycle state cannot be inspected, do not claim a background retention.
        return true;
    }

    private static void logReflectionFailure(Throwable error) {
        if (!reflectionFailureLogged) {
            reflectionFailureLogged = true;
            Log.e(TAG, "Original connection state could not be inspected; retention disabled", error);
        }
    }
}
