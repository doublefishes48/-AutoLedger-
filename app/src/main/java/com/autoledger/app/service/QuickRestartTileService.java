package com.autoledger.app.service;

import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.autoledger.app.data.DebugLog;
import com.autoledger.app.data.LedgerRepository;

public class QuickRestartTileService extends TileService {
    private static final long GUARD_COOLDOWN_MS = 2_000L;
    private long lastGuardAt;

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        DebugLog.append(this, "quick restart tile added");
    }

    @Override
    public void onTileRemoved() {
        super.onTileRemoved();
        DebugLog.append(this, "quick restart tile removed");
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        repairIfNeeded();
        refreshTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        repairIfNeeded();
    }

    private void repairIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastGuardAt < GUARD_COOLDOWN_MS) {
            return;
        }
        lastGuardAt = now;
        if (LedgerRepository.get(this).isKeepAliveEnabled()) {
            KeepAliveService.start(this);
        }
        new Thread(() -> {
            boolean fixed = ServiceGuard.run(this);
            new Handler(Looper.getMainLooper()).post(() -> {
                DebugLog.append(this, "quick restart result=" + fixed);
                refreshTileState();
            });
        }, "autoledger-quick-restart").start();
    }

    private void refreshTileState() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean notificationOn = isServiceEnabled(
                "enabled_notification_listeners",
                NotificationCaptureService.class
        );
        boolean accessibilityOn = isServiceEnabled(
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                AccessibilityCaptureService.class
        );
        tile.setState(notificationOn && accessibilityOn
                ? Tile.STATE_ACTIVE
                : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    private boolean isServiceEnabled(String setting, Class<?> serviceClass) {
        try {
            ComponentName component = new ComponentName(this, serviceClass);
            String enabled = Settings.Secure.getString(getContentResolver(), setting);
            return enabled != null && enabled.contains(component.flattenToString());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
