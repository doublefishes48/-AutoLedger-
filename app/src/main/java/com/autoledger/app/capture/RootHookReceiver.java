package com.autoledger.app.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.autoledger.app.data.LedgerRepository;

public class RootHookReceiver extends BroadcastReceiver {
    public static final String ACTION_ROOT_CAPTURE = "com.autoledger.app.action.ROOT_CAPTURE";

    @Override
    public void onReceive(Context context, Intent intent) {
        LedgerRepository repository = LedgerRepository.get(context);
        if (!repository.isSourceEnabled(SourceKey.ROOT_HOOK)) {
            return;
        }

        String packageName = intent.getStringExtra("package_name");
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        long occurredAt = intent.getLongExtra("occurred_at", System.currentTimeMillis());

        CaptureRouter.ingest(
                context,
                packageName,
                CaptureChannel.ROOT_HOOK_FUTURE,
                title,
                text,
                occurredAt
        );
    }
}

