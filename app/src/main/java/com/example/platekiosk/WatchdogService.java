package com.example.platekiosk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

public class WatchdogService extends Service {
    private static final String CHANNEL_ID = "kiosk_watchdog";
    private static final int NOTIFICATION_ID = 1001;
    private static final long HEARTBEAT_INTERVAL_MS = 60_000L;
    private static final long RECOVERY_INTERVAL_MS = 30_000L;
    private static final String TAG = "PlateKiosk";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    private final Runnable heartbeatTask = new Runnable() {
        @Override
        public void run() {
            long heartbeatTime = System.currentTimeMillis();
            getSharedPreferences("health", MODE_PRIVATE)
                    .edit()
                    .putLong("lastHeartbeatUtcMs", heartbeatTime)
                    .apply();
            Log.i(TAG, "Kiosk heartbeat: " + heartbeatTime);
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private final Runnable recoveryTask = new Runnable() {
        @Override
        public void run() {
            recoverKioskIfActive();
            handler.postDelayed(this, RECOVERY_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        acquireWakeLockIfNeeded();
        handler.post(heartbeatTask);
        handler.post(recoveryTask);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(heartbeatTask);
        handler.removeCallbacks(recoveryTask);
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.watchdog_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.watchdog_channel_description));

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.watchdog_notification_title))
                .setContentText(getString(R.string.watchdog_notification_text))
                .setSmallIcon(R.drawable.ic_status)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void recoverKioskIfActive() {
        if (!KioskConfig.isKioskActive(this)) {
            releaseWakeLock();
            return;
        }

        acquireWakeLockIfNeeded();
        KioskPolicy.apply(this);

        Intent kioskIntent = new Intent(this, MainActivity.class);
        kioskIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        kioskIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(kioskIntent);
    }

    private void acquireWakeLockIfNeeded() {
        if (!KioskConfig.isKioskActive(this)
                || !KioskConfig.keepScreenOn(this)
                || (wakeLock != null && wakeLock.isHeld())) {
            return;
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock =
                powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        getPackageName() + ":kiosk-watchdog");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }
}
