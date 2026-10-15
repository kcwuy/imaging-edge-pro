package com.sony.playmemories.mobile.redesign;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/** Visible process owner for an already established camera connection. No reconnection protocol. */
public final class ConnectionService extends Service {
    static final String ACTION_START = "com.sony.playmemories.mobile.redesign.KEEP_CONNECTION";
    static final String ACTION_DISCONNECT = "com.sony.playmemories.mobile.redesign.DISCONNECT";
    static final String EXTRA_GENERATION = "connection_generation";
    private static final String TAG = "ConnectionService";
    private static final String CHANNEL = "iep_camera_connection";
    private static final int NOTIFICATION_ID = 0x494550;
    private static final long CHECK_MS = 2000L;
    private static final long LOCK_TIMEOUT_MS = 60000L;
    private static final long LOCK_RENEW_MS = 20000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private boolean promoted;
    private boolean stopping;
    private long lastLockAcquire;
    private int ownerGeneration = -1;

    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (stopping || !promoted) return;
            if (!ConnectionKeeper.isEnabled(ConnectionService.this)
                    || !ConnectionKeeper.hasCameraConnection(ConnectionService.this)) {
                ConnectionKeeper.onConnectionLost(ConnectionService.this, ownerGeneration);
                stopConnectionWork();
                return;
            }
            try {
                long now = SystemClock.elapsedRealtime();
                if (wakeLock == null || !wakeLock.isHeld() || now - lastLockAcquire >= LOCK_RENEW_MS) {
                    acquireLock();
                }
            } catch (RuntimeException error) {
                ConnectionKeeper.onServiceFailure(ConnectionService.this, ownerGeneration, error);
                stopConnectionWork();
                return;
            }
            handler.postDelayed(this, CHECK_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        ConnectionKeeper.onServiceCreated(this);
        // A start request carries a generation token. onStartCommand validates it before promotion,
        // so a delayed create cannot resurrect a connection after original teardown.
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            ConnectionKeeper.disconnect(this);
            stopConnectionWork();
            return START_NOT_STICKY;
        }
        int token = intent == null ? -1 : intent.getIntExtra(EXTRA_GENERATION, -1);
        if (intent != null && ACTION_START.equals(intent.getAction())
                && ConnectionKeeper.hasNewerActiveStart(token)) {
            // Android may deliver old and new START commands to one Service instance. An old
            // command must not release resources or stopSelf for a newer acknowledged owner.
            return START_NOT_STICKY;
        }
        if (stopping || intent == null || !ACTION_START.equals(intent.getAction())
                || !ConnectionKeeper.acceptServiceStart(this, token)) {
            ConnectionKeeper.onServiceRejected(token);
            stopConnectionWork();
            return START_NOT_STICKY;
        }
        if (!ConnectionKeeper.onServiceAccepted(this, token)) {
            ConnectionKeeper.onServiceRejected(token);
            stopConnectionWork();
            return START_NOT_STICKY;
        }
        ownerGeneration = token;
        try {
            Notification notification = createNotification();
            acquireLock();
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            promoted = true;
            if (!ConnectionKeeper.onServiceReady(this, token)) {
                ConnectionKeeper.onServiceRejected(token);
                stopConnectionWork();
                return START_NOT_STICKY;
            }
            handler.removeCallbacks(monitor);
            handler.post(monitor);
        } catch (RuntimeException error) {
            ConnectionKeeper.onServiceFailure(this, ownerGeneration, error);
            stopConnectionWork();
        }
        // A restarted process cannot inherit the previous sockets or camera authentication.
        return START_NOT_STICKY;
    }

    private Notification createNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) throw new IllegalStateException("Notification manager unavailable");
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "相机连接", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持已建立的相机连接");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);

        Intent open = new Intent().setClassName(getPackageName(),
                "com.sony.playmemories.mobile.home.HomeActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent launch = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent disconnect = new Intent(this, ConnectionService.class).setAction(ACTION_DISCONNECT);
        PendingIntent stop = PendingIntent.getService(this, NOTIFICATION_ID + 1, disconnect,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int icon = getApplicationInfo().icon;
        if (icon == 0) icon = android.R.drawable.stat_notify_sync;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(icon)
                .setContentTitle("Imaging Edge · 相机已连接")
                .setContentText("后台保持连接已开启，轻点返回相机。")
                .setContentIntent(launch)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "断开连接", stop).build());
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void acquireLock() {
        if (wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) throw new IllegalStateException("Power manager unavailable");
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "ImagingEdgeRedesign:CameraConnection");
            wakeLock.setReferenceCounted(false);
        }
        // Bounded and renewed only for an observed connection. This does not bypass Doze.
        wakeLock.acquire(LOCK_TIMEOUT_MS);
        lastLockAcquire = SystemClock.elapsedRealtime();
    }

    /** Also invoked immediately by the keeper when disabling or entering original cleanup. */
    void stopConnectionWork() {
        if (stopping) return;
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        releaseLock();
        if (promoted) {
            try { stopForeground(STOP_FOREGROUND_REMOVE); }
            catch (RuntimeException error) { Log.e(TAG, "Could not remove connection notification", error); }
            promoted = false;
        }
        stopSelf();
    }

    private void releaseLock() {
        if (wakeLock == null) return;
        try { if (wakeLock.isHeld()) wakeLock.release(); }
        catch (RuntimeException error) { Log.e(TAG, "Could not release connection wake lock", error); }
        wakeLock = null;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        ConnectionKeeper.disconnect(this);
        stopConnectionWork();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        stopConnectionWork();
        ConnectionKeeper.onServiceEnded(this, ownerGeneration);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
