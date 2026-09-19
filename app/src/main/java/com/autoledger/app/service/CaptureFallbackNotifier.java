package com.autoledger.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.autoledger.app.MainActivity;

public final class CaptureFallbackNotifier {
    private static final String CHANNEL_ID = "autoledger_capture_fallback";
    private static final int NOTIFICATION_ID = 2;
    private static long lastShownAt;

    private CaptureFallbackNotifier() {
    }

    public static void show(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastShownAt < 30_000L) {
            return;
        }
        lastShownAt = now;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "自动记账补记提醒",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("微信支付页面无法自动识别时提醒补记");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);

        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_MANUAL_FROM_CAPTURE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                context,
                6202,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("疑似微信支付未识别")
                .setContentText("点击补记这笔账")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000L)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }
}
