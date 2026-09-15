package com.autoledger.app.service;

import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;

import com.autoledger.app.data.DebugLog;
import com.autoledger.app.data.LedgerRepository;

public final class ServiceGuard {
    private static final String SETTING_ENABLED_NOTIFICATION_LISTENERS =
            "enabled_notification_listeners";
    private static final long PROCESS_START_MS = SystemClock.elapsedRealtime();
    private static final long STARTUP_GRACE_MS = 8_000L;

    private ServiceGuard() {
    }

    public static boolean run(Context context) {
        if (!LedgerRepository.get(context).isKeepAliveEnabled()) {
            return false;
        }

        boolean notificationEnabled = isEnabled(
                context,
                SETTING_ENABLED_NOTIFICATION_LISTENERS,
                new ComponentName(context, NotificationCaptureService.class)
        );
        boolean accessibilityEnabled = isEnabled(
                context,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                new ComponentName(context, AccessibilityCaptureService.class)
        );
        boolean notificationConnected = notificationEnabled
                && NotificationCaptureService.isConnected();
        boolean accessibilityConnected = accessibilityEnabled
                && AccessibilityCaptureService.isRunning();
        if (notificationConnected && accessibilityConnected) {
            return true;
        }

        if (!ShizukuSupport.grantWriteSecureSettings(context)) {
            DebugLog.append(context, "service guard needs shizuku/write secure settings");
            return false;
        }

        boolean fixedNotification = true;
        if (!notificationEnabled) {
            fixedNotification = addEnabledComponent(
                    context,
                    SETTING_ENABLED_NOTIFICATION_LISTENERS,
                    NotificationCaptureService.class
            );
            if (fixedNotification && ShizukuSupport.isPermissionGranted()) {
                ComponentName component = new ComponentName(
                        context,
                        NotificationCaptureService.class
                );
                boolean shellBound = ShizukuSupport.runShell(
                        "cmd notification allow_listener "
                                + component.flattenToString()
                );
                DebugLog.append(
                        context,
                        "service guard listener shell bound=" + shellBound
                );
            }
            if (fixedNotification) {
                requestNotificationRebind(context);
            }
        } else if (!notificationConnected
                && SystemClock.elapsedRealtime() - PROCESS_START_MS >= STARTUP_GRACE_MS) {
            fixedNotification = forceNotificationRebind(context);
        }

        boolean fixedAccessibility = true;
        if (!accessibilityEnabled) {
            fixedAccessibility = addEnabledComponent(
                    context,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    AccessibilityCaptureService.class
            );
            if (fixedAccessibility) {
                Settings.Secure.putInt(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1
                );
            }
        } else if (!accessibilityConnected
                && SystemClock.elapsedRealtime() - PROCESS_START_MS >= STARTUP_GRACE_MS) {
            fixedAccessibility = forceAccessibilityRebind(context);
        }

        boolean result = fixedNotification && fixedAccessibility;
        DebugLog.append(
                context,
                "service guard result=" + result
                        + " notification=" + fixedNotification
                        + " listenerConnected=" + NotificationCaptureService.isConnected()
                        + " accessibility=" + fixedAccessibility
                        + " accessibilityRunning=" + AccessibilityCaptureService.isRunning()
        );
        return result;
    }

    private static boolean forceNotificationRebind(Context context) {
        try {
            requestNotificationRebind(context);
            if (ShizukuSupport.isPermissionGranted()) {
                ComponentName component = new ComponentName(
                        context,
                        NotificationCaptureService.class
                );
                String command = "cmd notification disallow_listener "
                        + component.flattenToString()
                        + "; cmd notification allow_listener "
                        + component.flattenToString();
                ShizukuSupport.runShell(command);
            }
            DebugLog.append(context, "service guard forced notification rebind");
            return true;
        } catch (Throwable error) {
            DebugLog.append(context, "service guard notification rebind failed " + error);
            return false;
        }
    }

    private static void requestNotificationRebind(Context context) {
        try {
            NotificationListenerService.requestRebind(
                    new ComponentName(context, NotificationCaptureService.class)
            );
        } catch (Throwable ignored) {
        }
    }

    private static boolean forceAccessibilityRebind(Context context) {
        try {
            ComponentName component = new ComponentName(
                    context,
                    AccessibilityCaptureService.class
            );
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            String without = removeComponent(enabled, component);
            Settings.Secure.putString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    without
            );
            Settings.Secure.putInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
            );

            Runnable restore = () -> {
                try {
                    Thread.sleep(900L);
                    addEnabledComponent(
                            context,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            AccessibilityCaptureService.class
                    );
                    Settings.Secure.putInt(
                            context.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_ENABLED,
                            1
                    );
                    DebugLog.append(context, "service guard forced accessibility rebind");
                } catch (Throwable error) {
                    DebugLog.append(
                            context,
                            "service guard accessibility rebind restore failed " + error
                    );
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                new Thread(restore, "autoledger-a11y-rebind").start();
            } else {
                restore.run();
            }
            return true;
        } catch (Throwable error) {
            DebugLog.append(context, "service guard accessibility rebind failed " + error);
            return false;
        }
    }

    private static boolean isEnabled(
            Context context,
            String setting,
            ComponentName component
    ) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                setting
        );
        return enabled != null && enabled.contains(component.flattenToString());
    }

    private static boolean addEnabledComponent(
            Context context,
            String setting,
            Class<?> serviceClass
    ) {
        try {
            ComponentName component = new ComponentName(context, serviceClass);
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    setting
            );
            if (enabled != null && enabled.contains(component.flattenToString())) {
                return true;
            }
            String merged = enabled == null || enabled.trim().isEmpty()
                    ? component.flattenToString()
                    : enabled + ":" + component.flattenToString();
            return Settings.Secure.putString(
                    context.getContentResolver(),
                    setting,
                    merged
            );
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String removeComponent(String enabled, ComponentName component) {
        if (enabled == null || enabled.trim().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String item : enabled.split(":")) {
            if (item.trim().isEmpty()
                    || item.equals(component.flattenToString())) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(':');
            }
            builder.append(item);
        }
        return builder.toString();
    }
}
