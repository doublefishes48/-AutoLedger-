package com.autoledger.app.service;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.autoledger.app.capture.CaptureChannel;
import com.autoledger.app.capture.CapturePackages;
import com.autoledger.app.capture.CaptureRouter;
import com.autoledger.app.data.DebugLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotificationCaptureService extends NotificationListenerService {
    private static final String TAG = "AutoLedger";
    private static volatile boolean connected;

    private ExecutorService executor;

    public static boolean isConnected() {
        return connected;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        connected = false;
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "autoledger-notification");
            thread.setDaemon(true);
            return thread;
        });
        DebugLog.append(this, "notification listener created");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = true;
        DebugLog.append(this, "notification listener connected");
        executor.execute(() -> {
            try {
                StatusBarNotification[] active = getActiveNotifications();
                if (active == null || active.length == 0) {
                    return;
                }
                int replayed = 0;
                for (StatusBarNotification sbn : active) {
                    if (sbn != null && CapturePackages.supportsNotification(sbn.getPackageName())) {
                        enqueueNotification(sbn);
                        replayed++;
                    }
                }
                if (replayed > 0) {
                    DebugLog.append(this, "notification listener replayed active=" + replayed);
                }
            } catch (Throwable error) {
                DebugLog.append(this, "notification listener replay failed " + error);
            }
        });
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        DebugLog.append(this, "notification listener disconnected");
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        enqueueNotification(sbn);
    }

    private void enqueueNotification(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        String packageName = sbn.getPackageName();
        if (!CapturePackages.supportsNotification(packageName)) {
            return;
        }

        Bundle extras = sbn.getNotification() == null ? null : sbn.getNotification().extras;
        if (extras == null) {
            return;
        }
        String title = text(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = concat(
                text(extras.getCharSequence(Notification.EXTRA_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_INFO_TEXT)),
                text(extras.getCharSequence(Notification.EXTRA_TITLE_BIG)),
                text(extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES))
        );
        final long occurredAt = sbn.getPostTime();
        Log.d(TAG, "notification from=" + packageName + " title=" + title + " text=" + text);
        DebugLog.append(this, "notification parsed pkg=" + packageName + " title=" + title + " text=" + text);

        executor.execute(() -> CaptureRouter.ingest(
                getApplicationContext(),
                packageName,
                CaptureChannel.NOTIFICATION,
                title,
                text,
                occurredAt
        ));
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (executor != null) {
            executor.shutdown();
        }
        super.onDestroy();
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String text(CharSequence[] values) {
        if (values == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (CharSequence value : values) {
            if (value != null && !value.toString().isEmpty()) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private static String concat(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isEmpty()) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(part);
            }
        }
        return builder.toString();
    }
}
