package com.autoledger.app.service;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

import com.autoledger.app.data.DebugLog;
import com.autoledger.app.data.LedgerRepository;

public final class ServiceGuard {
    private static final String SETTING_ENABLED_NOTIFICATION_LISTENERS =
            "enabled_notification_listeners";

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
        if (notificationEnabled && accessibilityEnabled) {
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
        }

        boolean result = fixedNotification && fixedAccessibility;
        DebugLog.append(
                context,
                "service guard result=" + result
                        + " notification=" + fixedNotification
                        + " accessibility=" + fixedAccessibility
        );
        return result;
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
}
