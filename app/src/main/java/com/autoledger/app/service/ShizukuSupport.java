package com.autoledger.app.service;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

public final class ShizukuSupport {
    private ShizukuSupport() {
    }

    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isPermissionGranted() {
        try {
            return isAvailable()
                    && Shizuku.checkSelfPermission()
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean canWriteSecureSettings(Context context) {
        try {
            return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean requestPermission(int requestCode) {
        try {
            if (!isAvailable() || isPermissionGranted()) {
                return isPermissionGranted();
            }
            Shizuku.requestPermission(requestCode);
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void addPermissionResultListener(
            Shizuku.OnRequestPermissionResultListener listener
    ) {
        try {
            Shizuku.addRequestPermissionResultListener(listener);
        } catch (Throwable ignored) {
        }
    }

    public static void removePermissionResultListener(
            Shizuku.OnRequestPermissionResultListener listener
    ) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener);
        } catch (Throwable ignored) {
        }
    }

    public static boolean grantWriteSecureSettings(Context context) {
        try {
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            return runShell("pm grant "
                    + context.getPackageName()
                    + " "
                    + Manifest.permission.WRITE_SECURE_SETTINGS);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean runShell(String command) {
        try {
            if (!isPermissionGranted()) {
                return false;
            }
            Class<?> shizuku = Class.forName("rikka.shizuku.Shizuku");
            Method newProcess = shizuku.getDeclaredMethod(
                    "newProcess",
                    String[].class,
                    String[].class,
                    String.class
            );
            newProcess.setAccessible(true);
            Object process = newProcess.invoke(
                    null,
                    new String[]{"sh", "-c", command},
                    null,
                    null
            );
            Method waitFor = process.getClass().getMethod("waitFor");
            Object exitCode = waitFor.invoke(process);
            return exitCode instanceof Number
                    && ((Number) exitCode).intValue() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
