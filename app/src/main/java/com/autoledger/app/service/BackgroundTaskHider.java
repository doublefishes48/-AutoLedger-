package com.autoledger.app.service;

import android.app.ActivityManager;
import android.content.Context;

import com.autoledger.app.data.DebugLog;
import com.autoledger.app.data.LedgerRepository;

import java.util.List;

public final class BackgroundTaskHider {
    private BackgroundTaskHider() {
    }

    public static void apply(Context context) {
        boolean hide = LedgerRepository.get(context).isHideFromRecentsEnabled();
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return;
            }
            List<ActivityManager.AppTask> tasks = manager.getAppTasks();
            int changed = 0;
            for (ActivityManager.AppTask task : tasks) {
                if (task.getTaskInfo() != null) {
                    task.setExcludeFromRecents(hide);
                    changed++;
                }
            }
            if (changed > 0) {
                DebugLog.append(
                        context,
                        "background task hide=" + hide + " tasks=" + changed
                );
            }
        } catch (Throwable ignored) {
            DebugLog.append(context, "background task hide failed");
        }
    }
}
