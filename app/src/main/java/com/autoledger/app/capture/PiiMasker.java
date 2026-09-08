package com.autoledger.app.capture;

public final class PiiMasker {
    private PiiMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String result = text;
        result = result.replaceAll("(?<![0-9])1[3-9][0-9]{9}(?![0-9])", "[手机]");
        result = result.replaceAll("[0-9]{13,19}", "[卡号]");
        if (result.length() > 300) {
            result = result.substring(0, 300);
        }
        return result;
    }
}

