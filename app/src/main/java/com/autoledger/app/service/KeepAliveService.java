package com.autoledger.app.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;
import android.content.pm.ServiceInfo;

import com.autoledger.app.data.DebugLog;
import com.autoledger.app.data.LedgerRepository;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class KeepAliveService extends Service {
    private static final String CHANNEL_ID = "autoledger_keepalive";
    private static final int NOTIFICATION_ID = 1;
    private static final int GUARD_REQUEST_CODE = 2401;
    static final long GUARD_DELAY_MS = 30_000L;
    static final long GUARD_INTERVAL_MS = 5L * 60L * 1000L;
    private static final long WATCHDOG_DELAY_MS = 45L * 1000L;
    private static final long WATCHDOG_INTERVAL_MS = 30L * 1000L;
    public static final String ACTION_GUARD = "com.autoledger.app.action.SERVICE_GUARD";

    private ScheduledExecutorService worker;
    private final BroadcastReceiver wakeGuardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ServiceGuard.run(KeepAliveService.this);
            scheduleGuard(KeepAliveService.this, GUARD_INTERVAL_MS);
        }
    };

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, KeepAliveService.class);
            intent.setAction("start");
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable error) {
            DebugLog.append(context, "keepalive start blocked " + error);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, KeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "autoledger-guard");
            thread.setDaemon(true);
            return thread;
        });
        createChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("自动记账后台监听")
                .setContentText("正在监听支付通知，保持服务稳定运行")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setDefaults(0)
                .setOngoing(true)
                .build();
        startForegroundCompat(notification);
        registerWakeGuardReceiver();
        scheduleGuard(this, GUARD_DELAY_MS);
        worker.execute(() -> ServiceGuard.run(this));
        worker.scheduleWithFixedDelay(
                () -> {
                    if (LedgerRepository.get(this).isKeepAliveEnabled()) {
                        ServiceGuard.run(this);
                        AccessibilityCaptureService.refreshKeepAliveOverlay(this);
                    }
                },
                WATCHDOG_DELAY_MS,
                WATCHDOG_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
        DebugLog.append(this, "keepalive service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        scheduleGuard(this, GUARD_DELAY_MS);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        scheduleGuard(this, GUARD_INTERVAL_MS);
        if (worker != null) {
            worker.execute(() -> ServiceGuard.run(this));
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(wakeGuardReceiver);
        } catch (Throwable ignored) {
        }
        if (worker != null) {
            worker.shutdown();
        }
        scheduleGuard(this, GUARD_INTERVAL_MS);
        super.onDestroy();
    }

    static void scheduleGuard(Context context, long delayMs) {
        try {
            AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
            Intent intent = new Intent(context, ServiceGuardReceiver.class)
                    .setAction(ACTION_GUARD);
            PendingIntent pending = PendingIntent.getBroadcast(
                    context,
                    GUARD_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | PendingIntent.FLAG_IMMUTABLE
            );
            long trigger = System.currentTimeMillis() + delayMs;
            if (alarmManager != null) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        trigger,
                        pending
                );
            }
        } catch (Throwable ignored) {
        }
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void registerWakeGuardReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    wakeGuardReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );
        } else {
            registerReceiver(wakeGuardReceiver, filter);
        }
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (channel == null) {
            channel = new NotificationChannel(
                    CHANNEL_ID,
                    "自动记账后台服务",
                    NotificationManager.IMPORTANCE_MIN
            );
        } else {
            channel.setImportance(NotificationManager.IMPORTANCE_MIN);
        }
        channel.setDescription("保持 AutoLedger 能持续监听支付通知");
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }
}
