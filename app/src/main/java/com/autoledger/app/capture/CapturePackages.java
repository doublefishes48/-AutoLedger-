package com.autoledger.app.capture;

public final class CapturePackages {
    public static final String PACKAGE_WECHAT = "com.tencent.mm";
    public static final String PACKAGE_ALIPAY = "com.eg.android.AlipayGphone";
    public static final String PACKAGE_UNIONPAY = "com.unionpay";
    public static final String PACKAGE_UNIONPAY_TSM = "com.unionpay.tsmservice";

    private CapturePackages() {
    }

    public static boolean supportsNotification(String packageName) {
        return toSourceKey(packageName) != null;
    }

    public static boolean supportsAccessibility(String packageName) {
        String source = toSourceKey(packageName);
        return SourceKey.WECHAT.equals(source)
                || SourceKey.ALIPAY.equals(source)
                || SourceKey.UNIONPAY.equals(source);
    }

    public static String toSourceKey(String packageName) {
        if (packageName == null) {
            return null;
        }
        if (equalsOrChild(packageName, PACKAGE_WECHAT)) {
            return SourceKey.WECHAT;
        }
        if (equalsOrChild(packageName, PACKAGE_ALIPAY)) {
            return SourceKey.ALIPAY;
        }
        if (equalsOrChild(packageName, PACKAGE_UNIONPAY) || equalsOrChild(packageName, PACKAGE_UNIONPAY_TSM)) {
            return SourceKey.UNIONPAY;
        }
        return null;
    }

    private static boolean equalsOrChild(String packageName, String expected) {
        return packageName.equals(expected) || packageName.startsWith(expected + ".");
    }
}

