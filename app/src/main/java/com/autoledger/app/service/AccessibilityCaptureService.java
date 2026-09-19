package com.autoledger.app.service;

import android.annotation.SuppressLint;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.autoledger.app.capture.CaptureChannel;
import com.autoledger.app.capture.CapturePackages;
import com.autoledger.app.capture.CaptureRouter;
import com.autoledger.app.data.DebugLog;
import com.autoledger.app.data.LedgerRepository;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AccessibilityCaptureService extends AccessibilityService {
    private static final String TAG = "AutoLedger";

    private static AccessibilityCaptureService runningInstance;
    private static final long[] RESULT_PROBE_DELAYS = {120L, 380L, 900L};
    private static final String[] ALIPAY_RESULT_ACTIVITY_MARKERS = {
            "nfccoderouteractivity", "nrespageactivity", "mspcontaineractivity",
            "onsitepayactivity", "payresult", "paymentresult", "payresultui"
    };
    private static final String[] WECHAT_RESULT_ACTIVITY_MARKERS = {
            "wallet", "pay", "remittance", "transfer", "liteapp", "transparentliteui",
            "receipt", "wxpay"
    };
    private static final String[] WECHAT_PAYMENT_HINTS = {
            "支付", "付款", "收款", "交易", "转账", "收款方", "商户", "金额"
    };
    private static final String[] UNIONPAY_RESULT_ACTIVITY_MARKERS = {
            "pay", "payment", "result", "cashier", "order", "trade", "webview",
            "unionpay", "uppay"
    };
    private static final String[] UNIONPAY_PAYMENT_HINTS = {
            "支付", "付款", "交易", "消费", "金额", "订单", "收银台"
    };
    private static final String[] OCR_BROWSING_MARKERS = {
            "交易详情", "账单详情", "交易单号", "商户单号", "支付时间",
            "当前状态", "收单机构", "申请电子凭证", "点击查看全部消息",
            "全部会话", "消息盒子", "使用零钱支付", "使用零钱通支付"
    };

    private static final String[] COMPLETION_MARKERS = {
            "付款成功", "支付成功", "交易成功", "收款成功", "已付款", "支付完成",
            "到账成功", "退款成功", "支付凭证", "扣款成功", "支付结果", "交易结果",
            "已完成支付", "付款完成"
    };

    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> scheduledResultProbes = new HashSet<>();
    private TextRecognizer textRecognizer;
    private WindowManager windowManager;
    private View keepAliveOverlay;
    private boolean overlayAttached;
    private volatile boolean ocrInFlight;
    private volatile boolean ocrSuccessForFlow;
    private volatile String lastForegroundPackage;
    private volatile long lastForegroundEventAt;
    private volatile long lastVisualCaptureAt;
    private volatile long lastWechatPaymentSignalAt;
    private volatile long lastWechatOcrScheduleAt;

    public static void refreshKeepAliveOverlay(Context context) {
        AccessibilityCaptureService service = runningInstance;
        if (service != null) {
            service.syncKeepAliveOverlay(context);
        }
    }

    public static boolean isRunning() {
        return runningInstance != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "autoledger-accessibility");
            thread.setDaemon(true);
            return thread;
        });
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        textRecognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build()
        );
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        runningInstance = this;
        if (LedgerRepository.get(this).isKeepAliveEnabled()) {
            KeepAliveService.start(this);
            attachKeepAliveOverlay();
        }
        DebugLog.append(this, "accessibility service connected overlay=" + overlayAttached);
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
        long eventAt = System.currentTimeMillis();
        lastForegroundPackage = packageName;
        lastForegroundEventAt = eventAt;

        String className = event.getClassName() == null
                ? ""
                : event.getClassName().toString();
        String visible = collectVisibleText(event, packageName);
        boolean windowChanged = event.getEventType()
                == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        if (CapturePackages.PACKAGE_WECHAT.equals(packageName)
                && windowChanged) {
            DebugLog.append(
                    this,
                    "wechat state class=" + className
                            + " text=" + truncate(visible, 500)
            );
        }
        if (CapturePackages.PACKAGE_ALIPAY.equals(packageName)
                && (windowChanged || looksMoneyOrResult(visible))) {
            DebugLog.append(
                    this,
                    "alipay state class=" + className
                            + " event=" + event.getEventType()
                            + " text=" + truncate(visible, 600)
            );
        }
        if (CapturePackages.PACKAGE_UNIONPAY.equals(packageName)
                && (windowChanged || looksMoneyOrResult(visible))) {
            DebugLog.append(
                    this,
                    "unionpay state class=" + className
                            + " event=" + event.getEventType()
                            + " text=" + truncate(visible, 600)
            );
        }

        if (CapturePackages.PACKAGE_ALIPAY.equals(packageName)
                && (windowChanged || looksMoneyOrResult(visible))) {
            scheduleResultProbe(
                    packageName,
                    className,
                    looksMoneyOrResult(visible)
            );
        } else if (CapturePackages.PACKAGE_WECHAT.equals(packageName)
                && (windowChanged
                || containsAny(visible, WECHAT_PAYMENT_HINTS)
                || looksMoneyOrResult(visible))) {
            scheduleResultProbe(
                    packageName,
                    className,
                    containsAny(visible, WECHAT_PAYMENT_HINTS)
                            || looksMoneyOrResult(visible)
            );
        } else if (CapturePackages.PACKAGE_UNIONPAY.equals(packageName)
                && (windowChanged
                || containsAny(visible, UNIONPAY_PAYMENT_HINTS)
                || looksMoneyOrResult(visible))) {
            scheduleResultProbe(
                    packageName,
                    className,
                    containsAny(visible, UNIONPAY_PAYMENT_HINTS)
                            || looksMoneyOrResult(visible)
            );
        }
        if (CapturePackages.PACKAGE_WECHAT.equals(packageName)
                && isWechatPaymentInterface(className, visible)) {
            scheduleWechatOcrFallback(className);
        }

        if (visible.length() < 6 || !looksCompleted(visible)) {
            return;
        }
        captureVisibleText(packageName, className, event.getEventType(), visible);
    }

    private void captureVisibleText(
            String packageName,
            String className,
            int eventType,
            String visibleText
    ) {
        if (executor == null) {
            return;
        }
        lastVisualCaptureAt = System.currentTimeMillis();
        Log.d(TAG, "accessibility capture pkg=" + packageName
                + " text=" + visibleText);
        DebugLog.append(this, "accessibility capture pkg=" + packageName
                + " type=" + eventType + " class=" + className
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

    private void scheduleResultProbe(
            String packageName,
            String className,
            boolean allowGenericProbe
    ) {
        boolean wechat = CapturePackages.PACKAGE_WECHAT.equals(packageName);
        boolean unionpay = CapturePackages.PACKAGE_UNIONPAY.equals(packageName);
        String lowerClass = className == null
                ? ""
                : className.toLowerCase(Locale.ROOT);
        boolean resultActivity = false;
        String[] markers = wechat
                ? WECHAT_RESULT_ACTIVITY_MARKERS
                : unionpay
                ? UNIONPAY_RESULT_ACTIVITY_MARKERS
                : ALIPAY_RESULT_ACTIVITY_MARKERS;
        for (String marker : markers) {
            if (lowerClass.contains(marker)) {
                resultActivity = true;
                break;
            }
        }
        if (!resultActivity && !allowGenericProbe) {
            return;
        }

        long bucket = System.currentTimeMillis() / 1_000L;
        String probeKey = packageName + "|" + className + "|" + bucket;
        synchronized (scheduledResultProbes) {
            if (!scheduledResultProbes.add(probeKey)) {
                return;
            }
        }
        for (long delay : RESULT_PROBE_DELAYS) {
            mainHandler.postDelayed(() -> {
                String visible = collectVisibleText(null, packageName);
                if (visible.isEmpty()) {
                    return;
                }
                DebugLog.append(
                        this,
                        (wechat ? "wechat" : unionpay ? "unionpay" : "alipay")
                                + " result probe delay=" + delay
                                + " class=" + className
                                + " text=" + truncate(visible, 600)
                );
                if (looksCompleted(visible)) {
                    captureVisibleText(
                            packageName,
                            className,
                            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                            visible
                    );
                }
            }, delay);
        }
        mainHandler.postDelayed(() -> {
            synchronized (scheduledResultProbes) {
                scheduledResultProbes.remove(probeKey);
            }
        }, 1_600L);
    }

    private static boolean isWechatPaymentInterface(
            String className,
            String visible
    ) {
        String compact = visible == null
                ? ""
                : visible.replaceAll("\\s+", "");
        if (containsAny(compact, OCR_BROWSING_MARKERS)) {
            return false;
        }
        if (compact.contains("微信支付")) {
            return true;
        }
        String lowerClass = className == null
                ? ""
                : className.toLowerCase(Locale.ROOT);
        boolean paymentClass = lowerClass.contains("walletpay")
                || lowerClass.contains("mallwalletpay")
                || lowerClass.contains("payresult")
                || lowerClass.contains("wxpayentry")
                || lowerClass.contains("remittance");
        return paymentClass
                && (compact.isEmpty()
                || compact.contains("支付")
                || compact.contains("付款"));
    }

    private void scheduleWechatOcrFallback(String className) {
        long now = System.currentTimeMillis();
        if (now - lastWechatOcrScheduleAt < 12_000L) {
            return;
        }
        lastWechatOcrScheduleAt = now;
        lastWechatPaymentSignalAt = now;
        ocrSuccessForFlow = false;
        mainHandler.postDelayed(
                () -> attemptWechatOcr(className, false),
                1_200L
        );
        mainHandler.postDelayed(
                () -> attemptWechatOcr(className, true),
                3_200L
        );
    }

    private void attemptWechatOcr(String className, boolean finalAttempt) {
        if (ocrSuccessForFlow
                || lastVisualCaptureAt >= lastWechatPaymentSignalAt
                || System.currentTimeMillis() - lastWechatPaymentSignalAt > 7_000L
                || !isWechatForeground()
                || ocrInFlight) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || textRecognizer == null) {
            if (finalAttempt) {
                CaptureFallbackNotifier.show(this);
            }
            return;
        }
        ocrInFlight = true;
        try {
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult screenshot) {
                            processOcrScreenshot(screenshot, className, finalAttempt);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            ocrInFlight = false;
                            DebugLog.append(
                                    AccessibilityCaptureService.this,
                                    "wechat ocr screenshot failed error=" + errorCode
                            );
                            if (finalAttempt) {
                                CaptureFallbackNotifier.show(
                                        AccessibilityCaptureService.this
                                );
                            }
                        }
                    }
            );
        } catch (Throwable error) {
            ocrInFlight = false;
            DebugLog.append(this, "wechat ocr screenshot exception " + error);
            if (finalAttempt) {
                CaptureFallbackNotifier.show(this);
            }
        }
    }

    private void processOcrScreenshot(
            AccessibilityService.ScreenshotResult screenshot,
            String className,
            boolean finalAttempt
    ) {
        Bitmap bitmap = null;
        try {
            android.hardware.HardwareBuffer buffer = screenshot.getHardwareBuffer();
            Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(
                    buffer,
                    screenshot.getColorSpace()
            );
            if (hardwareBitmap != null) {
                bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
            buffer.close();
        } catch (Throwable error) {
            DebugLog.append(this, "wechat ocr bitmap failed " + error);
        }
        if (bitmap == null) {
            ocrInFlight = false;
            if (finalAttempt) {
                CaptureFallbackNotifier.show(this);
            }
            return;
        }
        final Bitmap recognizedBitmap = bitmap;

        TextRecognizer recognizer = textRecognizer;
        if (recognizer == null) {
            recognizedBitmap.recycle();
            ocrInFlight = false;
            return;
        }
        recognizer.process(InputImage.fromBitmap(recognizedBitmap, 0))
                .addOnSuccessListener(getMainExecutor(), text -> {
                    String ocrText = text.getText().trim();
                    DebugLog.append(
                            this,
                            "wechat ocr class=" + className
                                    + " chars=" + ocrText.length()
                                    + " success=" + looksCompleted(ocrText)
                                    + " money=" + looksMoneyOrResult(ocrText)
                    );
                    boolean browsing = containsAny(
                            ocrText.replaceAll("\\s+", ""),
                            OCR_BROWSING_MARKERS
                    );
                    if (ocrText.isEmpty() || browsing) {
                        ocrInFlight = false;
                        recognizedBitmap.recycle();
                        if (finalAttempt && !browsing) {
                            CaptureFallbackNotifier.show(this);
                        }
                        return;
                    }
                    executor.execute(() -> {
                        int result = CaptureRouter.ingest(
                                getApplicationContext(),
                                CapturePackages.PACKAGE_WECHAT,
                                CaptureChannel.OCR,
                                null,
                                ocrText,
                                System.currentTimeMillis()
                        );
                        ocrInFlight = false;
                        recognizedBitmap.recycle();
                        if (result != CaptureRouter.RESULT_IGNORED) {
                            lastVisualCaptureAt = System.currentTimeMillis();
                            ocrSuccessForFlow = true;
                        } else if (finalAttempt) {
                            CaptureFallbackNotifier.show(
                                    AccessibilityCaptureService.this
                            );
                        }
                    });
                })
                .addOnFailureListener(getMainExecutor(), error -> {
                    ocrInFlight = false;
                    recognizedBitmap.recycle();
                    DebugLog.append(this, "wechat ocr failed " + error);
                    if (finalAttempt) {
                        CaptureFallbackNotifier.show(this);
                    }
                });
    }

    private boolean isWechatForeground() {
        return CapturePackages.PACKAGE_WECHAT.equals(lastForegroundPackage)
                && System.currentTimeMillis() - lastForegroundEventAt < 5_000L;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (runningInstance == this) {
            runningInstance = null;
        }
        detachKeepAliveOverlay();
        mainHandler.removeCallbacksAndMessages(null);
        if (textRecognizer != null) {
            textRecognizer.close();
            textRecognizer = null;
        }
        if (executor != null) {
            executor.shutdown();
        }
        super.onDestroy();
    }

    private void syncKeepAliveOverlay(Context context) {
        if (LedgerRepository.get(context).isKeepAliveEnabled()) {
            attachKeepAliveOverlay();
        } else {
            detachKeepAliveOverlay();
        }
    }

    @SuppressLint("WrongConstant")
    private synchronized void attachKeepAliveOverlay() {
        if (overlayAttached || windowManager == null) {
            return;
        }
        try {
            View overlay = new View(this);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            params.format = PixelFormat.TRANSLUCENT;
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            params.gravity = Gravity.START | Gravity.TOP;
            params.width = 1;
            params.height = 1;
            params.packageName = getPackageName();
            windowManager.addView(overlay, params);
            keepAliveOverlay = overlay;
            overlayAttached = true;
            DebugLog.append(this, "accessibility keepalive overlay attached");
        } catch (Throwable error) {
            overlayAttached = false;
            keepAliveOverlay = null;
            DebugLog.append(this, "accessibility keepalive overlay failed " + error);
        }
    }

    private synchronized void detachKeepAliveOverlay() {
        if (!overlayAttached || windowManager == null || keepAliveOverlay == null) {
            overlayAttached = false;
            keepAliveOverlay = null;
            return;
        }
        try {
            windowManager.removeView(keepAliveOverlay);
        } catch (Throwable ignored) {
        }
        keepAliveOverlay = null;
        overlayAttached = false;
        DebugLog.append(this, "accessibility keepalive overlay removed");
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max);
    }

    private static boolean looksCompleted(String text) {
        String compact = text.replaceAll("\\s+", "");
        String lower = compact.toLowerCase(Locale.ROOT);
        if (compact.contains("支付失败")
                || compact.contains("付款失败")
                || compact.contains("交易失败")
                || compact.contains("支付已取消")
                || compact.contains("付款已取消")) {
            return false;
        }
        if (lower.contains("payment successful")
                || lower.contains("payment success")
                || lower.contains("paid successfully")
                || lower.contains("transfer successful")
                || lower.contains("refund successful")) {
            return true;
        }
        for (String marker : COMPLETION_MARKERS) {
            if (compact.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String[] words) {
        if (text == null) {
            return false;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksMoneyOrResult(String text) {
        if (text == null || text.length() < 4) {
            return false;
        }
        boolean hasAmount = text.contains("¥")
                || text.contains("￥")
                || text.contains("元");
        if (!hasAmount) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("success")
                || text.contains("支付")
                || text.contains("付款")
                || text.contains("收款")
                || text.contains("交易")
                || text.contains("退款")
                || text.contains("实付")
                || text.contains("完成")
                || text.contains("订单");
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

    private String collectVisibleText(
            AccessibilityEvent event,
            String eventPackage
    ) {
        Set<String> texts = new LinkedHashSet<>();

        if (event != null) {
            for (CharSequence text : event.getText()) {
                addText(texts, text);
            }
            addText(texts, event.getContentDescription());
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                try {
                    collectSupportedRoot(source, texts);
                } finally {
                    source.recycle();
                }
            }
        }

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
