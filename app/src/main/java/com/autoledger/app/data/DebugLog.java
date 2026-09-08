package com.autoledger.app.data;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DebugLog {
    private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final long MAX_LOG_BYTES = 512L * 1024L;

    private DebugLog() {
    }

    public static void append(Context context, String line) {
        try {
            File current = new File(context.getFilesDir(), "debug.log");
            if (current.exists() && current.length() > MAX_LOG_BYTES) {
                File old = new File(context.getFilesDir(), "debug.log.old");
                if (!current.renameTo(old)) {
                    current.delete();
                }
            }
            FileOutputStream out = context.openFileOutput("debug.log", Context.MODE_APPEND);
            String message = FORMAT.format(new Date()) + " " + line + "\n";
            out.write(message.getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }
}
