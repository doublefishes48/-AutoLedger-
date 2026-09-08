package com.autoledger.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.autoledger.app.data.LedgerRepository;

public class ServiceGuardReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!LedgerRepository.get(context).isKeepAliveEnabled()) {
            return;
        }
        String action = intent == null ? "" : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || KeepAliveService.ACTION_GUARD.equals(action)) {
            KeepAliveService.start(context);
        }
        ServiceGuard.run(context);
    }
}
