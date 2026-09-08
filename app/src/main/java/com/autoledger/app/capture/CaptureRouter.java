package com.autoledger.app.capture;

import android.content.Context;
import android.util.Log;

import com.autoledger.app.data.DebugLog;
import com.autoledger.app.data.LedgerRepository;

public final class CaptureRouter {
    private static final String TAG = "AutoLedger";

    public static final int RESULT_IGNORED = 0;
    public static final int RESULT_INSERTED = 1;
    public static final int RESULT_DUPLICATE = 2;

    private CaptureRouter() {
    }

    public static int ingest(
            Context context,
            String packageName,
            String channel,
            String title,
            String text,
            long occurredAt
    ) {
        RecognitionResult parsed = PaymentTextParser.parse(packageName, channel, title, text, occurredAt);
        if (parsed == null) {
            Log.d(TAG, "ingest ignored channel=" + channel + " pkg=" + packageName + " title=" + title + " text=" + text);
            DebugLog.append(context, "ingest ignored channel=" + channel + " pkg=" + packageName
                    + " title=" + title + " text=" + text);
            return RESULT_IGNORED;
        }
        int result = LedgerRepository.get(context).ingestCapture(parsed);
        Log.d(TAG, "ingest result=" + result
                + " channel=" + channel
                + " source=" + parsed.sourceKey
                + " amount=" + parsed.amountCents
                + " merchant=" + parsed.merchant);
        DebugLog.append(context, "ingest result=" + result
                + " channel=" + channel
                + " source=" + parsed.sourceKey
                + " amount=" + parsed.amountCents
                + " merchant=" + parsed.merchant);
        return result;
    }
}
