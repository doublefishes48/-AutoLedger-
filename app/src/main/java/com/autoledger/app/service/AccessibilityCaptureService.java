package com.autoledger.app.service;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.autoledger.app.capture.CaptureChannel;
import com.autoledger.app.capture.CapturePackages;
import com.autoledger.app.capture.CaptureRouter;
import com.autoledger.app.data.DebugLog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AccessibilityCaptureService extends AccessibilityService {
    private static final String TAG = "AutoLedger";

    private static final String[] COMPLETION_MARKERS = {
            "付款成功", "支付成功", "交易成功", "收款成功", "已付款", "支付完成",
            "到账成功", "退款成功", "支付凭证", "扣款成功"
    };

    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "autoledger-accessibility");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || executor == null) {
            return;
        }
        String packageName = event.getPackageName() == null
                ? null
                : event.getPackageName().toString();
        if (!CapturePackages.supportsAccessibility(packageName)) {
            return;
        }

        String className = event.getClassName() == null
                ? ""
                : event.getClassName().toString();
        String visible = collectVisibleText(packageName);
        if (CapturePackages.PACKAGE_WECHAT.equals(packageName)
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            DebugLog.append(
                    this,
                    "wechat state class=" + className
                            + " text=" + truncate(visible, 500)
            );
        }

        if (visible.length() < 6 || !looksCompleted(visible)) {
            return;
        }
        final String visibleText = visible;
        Log.d(TAG, "accessibility capture pkg=" + packageName
                + " text=" + visibleText);
        DebugLog.append(this, "accessibility capture pkg=" + packageName
                + " type=" + event.getEventType() + " class=" + className
                + " text=" + visibleText);
        executor.execute(() -> CaptureRouter.ingest(
                getApplicationContext(),
                packageName,
                CaptureChannel.ACCESSIBILITY,
                null,
                visibleText,
                System.currentTimeMillis()
        ));
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdown();
        }
        super.onDestroy();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max);
    }

    private static boolean looksCompleted(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("payment successful")
                || lower.contains("payment success")
                || lower.contains("paid successfully")
                || lower.contains("transfer successful")
                || lower.contains("refund successful")) {
            return true;
        }
        for (String marker : COMPLETION_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static void addText(Set<String> texts, CharSequence value) {
        if (value != null) {
            String text = value.toString().trim();
            if (!text.isEmpty() && texts.size() < 500) {
                texts.add(text);
            }
        }
    }

    private static String joinTexts(Set<String> texts) {
        StringBuilder builder = new StringBuilder();
        for (String text : texts) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(text);
        }
        return builder.toString();
    }

    private String collectVisibleText(String eventPackage) {
        Set<String> texts = new LinkedHashSet<>();

        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null) {
            try {
                collectSupportedRoot(activeRoot, texts);
            } finally {
                activeRoot.recycle();
            }
        }

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    try {
                        collectSupportedRoot(root, texts);
                    } finally {
                        root.recycle();
                    }
                }
            }
        }
        return joinTexts(texts).trim();
    }

    private void collectSupportedRoot(AccessibilityNodeInfo root, Set<String> texts) {
        String rootPackage = root.getPackageName() == null
                ? null
                : root.getPackageName().toString();
        if (!CapturePackages.supportsAccessibility(rootPackage)) {
            return;
        }
        collectText(root, texts, 0);
    }

    @SuppressWarnings("deprecation")
    private static void collectText(AccessibilityNodeInfo node, Set<String> texts, int depth) {
        if (node == null || depth > 12 || texts.size() >= 500) {
            return;
        }
        addText(texts, node.getText());
        addText(texts, node.getContentDescription());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            collectText(child, texts, depth + 1);
            if (child != null) {
                child.recycle();
            }
        }
    }
}
