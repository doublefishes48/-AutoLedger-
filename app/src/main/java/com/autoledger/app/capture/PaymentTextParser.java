package com.autoledger.app.capture;

import com.autoledger.app.data.LedgerEntry;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaymentTextParser {
    private static final Pattern AMOUNT_SYMBOL = Pattern.compile(
            "[¥￥]\\s*([0-9]{1,9}(?:\\.[0-9]{1,2})?)"
    );
    private static final Pattern AMOUNT_YUAN = Pattern.compile(
            "([0-9]{1,9}(?:\\.[0-9]{1,2})?)\\s*(?:元|块钱|RMB|CNY)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern AMOUNT_CONTEXT = Pattern.compile(
            "(?:支付成功|付款成功|交易成功|收款成功|退款成功|退款|消费|支出|付款|收款|到账|转入|收入|收到)"
                    + "[^0-9]{0,18}?([0-9]{1,9}(?:\\.[0-9]{1,2})?)(?:\\s*元)?"
    );

    private static final String[] STRONG_INCOME_WORDS = {
            "成功收款", "收款成功", "收到转账", "收到红包", "红包到账", "红包入账", "红包存入",
            "红包已到账", "转账到账", "到账成功", "到账", "入账", "收到", "退款", "返现",
            "向你付款", "向您付款", "收入", "转入"
    };
    private static final String[] HARD_IGNORE_WORDS = {
            "最高", "立减", "立减券", "优惠券", "代金券", "抽奖", "点我抽奖", "待领取",
            "今晚失效", "即将失效", "积分兑", "会员中心", "任务达标", "抽取", "领券",
            "红包", "点击领取", "免费领取", "无门槛券"
    };
    private static final String[] KNOWN_FOOD_MERCHANTS = {
            "瑞幸咖啡", "luckincoffee瑞幸咖啡", "蜜雪冰城", "幸运咖", "星巴克", "麦当劳",
            "肯德基", "汉堡王", "塔斯汀", "喜茶", "奈雪的茶", "茶百道", "古茗", "瑞幸",
            "书亦烧仙草", "霸王茶姬", "一点点", "沪上阿姨", "益禾堂", "coco都可"
    };

    private PaymentTextParser() {
    }

    public static RecognitionResult parse(
            String packageName,
            String channel,
            String title,
            String text,
            long occurredAt
    ) {
        String sourceKey = CapturePackages.toSourceKey(packageName);
        if (sourceKey == null) {
            return null;
        }
        String joined = join(title, text);
        if (joined.trim().isEmpty()) {
            return null;
        }

        boolean refund = hasRefundSignal(joined, title);
        boolean income = hasIncomeSignal(joined);
        boolean expense = hasExpenseSignal(joined);
        boolean officialTitle = isOfficialLedgerTitle(title);
        boolean paymentSignal = refund || income || expense || officialTitle;
        String knownMerchant = knownMerchant(joined, title);
        if (shouldIgnore(joined, knownMerchant)) {
            return null;
        }

        Long amountCents = extractAmount(joined);
        if (amountCents == null || amountCents <= 0) {
            return null;
        }

        boolean softCandidate = knownMerchant != null && !paymentSignal;
        String direction = detectDirection(
                title,
                joined,
                refund,
                income,
                expense,
                officialTitle,
                softCandidate
        );
        if (direction == null) {
            return null;
        }

        String merchant = extractMerchant(
                sourceKey,
                title,
                joined,
                direction
        );
        if (merchant.isEmpty() && knownMerchant != null) {
            merchant = knownMerchant;
        }
        String category = classify(joined, merchant, direction);
        double confidence = confidence(
                sourceKey,
                channel,
                title,
                joined,
                merchant,
                direction,
                paymentSignal,
                softCandidate,
                amountCents
        );

        return new RecognitionResult(
                sourceKey,
                channel,
                packageName,
                title == null ? "" : PiiMasker.mask(title),
                PiiMasker.mask(joined),
                amountCents,
                direction,
                category,
                merchant,
                occurredAt,
                confidence
        );
    }

    private static String join(String title, String text) {
        StringBuilder builder = new StringBuilder();
        if (title != null) {
            builder.append(title).append(' ');
        }
        if (text != null) {
            builder.append(text);
        }
        return builder.toString().replaceAll("[\\s\\u00A0]+", " ").trim();
    }

    private static boolean shouldIgnore(
            String text,
            String knownMerchant
    ) {
        if (knownMerchant != null && !knownMerchant.isEmpty()) {
            return false;
        }
        if (!containsAny(text, HARD_IGNORE_WORDS)) {
            return false;
        }
        if (containsAny(
                text,
                "红包到账", "红包入账", "红包存入", "收到红包", "红包已到账", "红包收款"
        )) {
            return false;
        }
        if (containsAny(
                text,
                "支付成功", "付款成功", "交易成功", "收款成功", "退款成功",
                "消费", "支出", "退款"
        ) && !containsAny(
                text,
                "最高", "立减", "立减券", "优惠券", "代金券", "抽奖",
                "点我抽奖", "任务达标", "会员中心", "领券", "积分兑",
                "今晚失效", "即将失效", "待领取", "抽取", "免费领取", "无门槛券"
        )) {
            return false;
        }
        return true;
    }

    private static Long extractAmount(String text) {
        Matcher symbol = AMOUNT_SYMBOL.matcher(text);
        if (symbol.find()) {
            return cents(symbol.group(1));
        }
        Matcher yuan = AMOUNT_YUAN.matcher(text);
        if (yuan.find()) {
            return cents(yuan.group(1));
        }
        Matcher context = AMOUNT_CONTEXT.matcher(text);
        if (context.find()) {
            return cents(context.group(1));
        }
        return null;
    }

    private static Long cents(String token) {
        try {
            double yuan = Double.parseDouble(token.replace(",", ""));
            return Math.round(yuan * 100.0);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean hasExpenseSignal(String text) {
        return containsAny(
                text,
                "支付成功", "付款成功", "交易成功", "支付完成", "支付了", "已支付",
                "已付款", "扣款成功", "扣款", "消费", "支出", "付款给",
                "微信支付", "购买成功"
        )
                || Pattern.compile("向\\s*(?!你|您)[^\\s，。]+\\s*(?:付款|支付)")
                .matcher(text)
                .find();
    }

    private static boolean hasIncomeSignal(String text) {
        return containsAny(text, STRONG_INCOME_WORDS);
    }

    private static boolean hasRefundSignal(String text, String title) {
        return containsAny(text, "退款", "返现", "退货退款")
                || title != null && title.contains("退款");
    }

    private static boolean isOfficialLedgerTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return false;
        }
        return containsAny(
                title,
                "微信支付", "微信收款", "微信到账", "支付宝到账", "支付助手",
                "交易提醒", "付款提醒", "退款提醒", "账单", "付款成功", "收款成功",
                "支付成功", "交易成功", "退款"
        );
    }

    private static String detectDirection(
            String title,
            String text,
            boolean refund,
            boolean income,
            boolean expense,
            boolean officialTitle,
            boolean softCandidate
    ) {
        if (refund) {
            return LedgerEntry.DIRECTION_INCOME;
        }
        if (income && !expense) {
            return LedgerEntry.DIRECTION_INCOME;
        }
        if (expense && !income) {
            return LedgerEntry.DIRECTION_EXPENSE;
        }
        if (officialTitle
                && title != null
                && containsAny(title, "退款", "到账", "收款", "收入", "转入")) {
            return LedgerEntry.DIRECTION_INCOME;
        }
        if (title != null
                && !containsAny(title, "支付宝")
                && containsAny(title, "微信支付", "支付成功", "付款成功", "交易成功",
                "付款", "支付", "消费")) {
            return LedgerEntry.DIRECTION_EXPENSE;
        }
        if (officialTitle) {
            return LedgerEntry.DIRECTION_EXPENSE;
        }
        if (softCandidate) {
            return LedgerEntry.DIRECTION_EXPENSE;
        }
        return null;
    }

    private static String extractMerchant(
            String sourceKey,
            String title,
            String text,
            String direction
    ) {
        String known = knownMerchant(text, title);
        if (!known.isEmpty()) {
            return known;
        }

        Pattern[] patterns = LedgerEntry.DIRECTION_EXPENSE.equals(direction)
                ? expenseMerchantPatterns()
                : incomeMerchantPatterns();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                String merchant = cleanMerchant(matcher.group(1));
                if (isUsefulMerchant(merchant, sourceKey)) {
                    return merchant;
                }
            }
        }
        return "";
    }

    private static Pattern[] expenseMerchantPatterns() {
        return new Pattern[]{
                Pattern.compile("向\\s*(?!你|您)([^\\s，。：:]{1,40})\\s*(?:付款|支付)"),
                Pattern.compile("付款给\\s*([^\\s，。：:]{1,40})"),
                Pattern.compile("在\\s*([^\\s，。：:]{1,24}?)\\s*(?:消费|购买)"),
                Pattern.compile("(?:商户|商家|收款方)\\s*[:：]\\s*([^\\s，。]{1,40})"),
                Pattern.compile("向商户\\s*([^\\s，。]{1,40})?\\s*付款")
        };
    }

    private static Pattern[] incomeMerchantPatterns() {
        return new Pattern[]{
                Pattern.compile("收到\\s*(?:来自\\s*)?([^\\s，。]{1,24})\\s*(?:的)?(?:转账|红包)"),
                Pattern.compile("([^\\s，。]{1,24})\\s*(?:向你|向您)付款"),
                Pattern.compile("(?:来自|由)\\s*([^\\s，。]{1,24})"),
                Pattern.compile("(?:付款方|转出方|转账方)\\s*[:：]\\s*([^\\s，。]{1,40})")
        };
    }

    private static String knownMerchant(String text, String title) {
        String combined = (text == null ? "" : text) + " " + (title == null ? "" : title);
        for (String merchant : KNOWN_FOOD_MERCHANTS) {
            if (combined.contains(merchant)) {
                return canonicalMerchant(merchant);
            }
        }
        return "";
    }

    private static String canonicalMerchant(String merchant) {
        if (merchant.toLowerCase(Locale.ROOT).contains("luckincoffee")
                || merchant.contains("瑞幸")) {
            return "瑞幸咖啡";
        }
        if (merchant.toLowerCase(Locale.ROOT).contains("coco")) {
            return "CoCo都可";
        }
        return merchant;
    }

    private static String cleanMerchant(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value
                .replaceAll("^\\p{P}+|\\p{P}+$", "")
                .trim();
        if (cleaned.startsWith("尾号") || cleaned.contains("银行卡")) {
            return "";
        }
        return cleaned;
    }

    private static boolean isUsefulMerchant(String merchant, String sourceKey) {
        if (merchant == null || merchant.isEmpty()) {
            return false;
        }
        if (merchant.length() < 2 || merchant.length() > 40) {
            return false;
        }
        if (merchant.matches("[0-9.]+")) {
            return false;
        }
        if ((SourceKey.WECHAT.equals(sourceKey)
                && merchant.contains("微信支付"))
                || merchant.contains("支付助手")
                || merchant.contains("支付宝")) {
            return false;
        }
        return true;
    }

    static String classify(String text, String merchant, String direction) {
        String combined = (text + " " + merchant).toLowerCase(Locale.ROOT);
        if (LedgerEntry.DIRECTION_INCOME.equals(direction)) {
            if (containsAny(combined, "退款", "返现")) {
                return CategoryCatalog.REFUND;
            }
            if (containsAny(combined, "工资", "薪资", "salary")) {
                return CategoryCatalog.SALARY;
            }
            if (containsAny(combined, "红包")) {
                return CategoryCatalog.RED_PACKET;
            }
            if (containsAny(combined, "理财", "利息", "收益")) {
                return CategoryCatalog.FINANCE;
            }
            if (containsAny(combined, "转账", "转入")) {
                return CategoryCatalog.TRANSFER;
            }
            return CategoryCatalog.OTHER;
        }

        if (containsAny(combined, "餐", "饭", "食", "咖啡", "奶茶", "外卖", "早餐",
                "午餐", "晚餐", "麦当劳", "肯德基", "瑞幸", "蜜雪", "星巴克")) {
            return CategoryCatalog.FOOD;
        }
        if (containsAny(combined, "交通", "地铁", "公交", "打车", "出租", "滴滴",
                "高铁", "火车", "加油", "停车", "出行")) {
            return CategoryCatalog.TRANSPORT;
        }
        if (containsAny(combined, "订阅", "会员", "vip", "云服务", "视频会员", "chatgpt")) {
            return CategoryCatalog.SUBSCRIPTION;
        }
        if (containsAny(combined, "电影", "游戏", "娱乐", "k歌", "门票", "steam")) {
            return CategoryCatalog.ENTERTAINMENT;
        }
        if (containsAny(combined, "房租", "租房", "物业", "房贷")) {
            return CategoryCatalog.HOUSING;
        }
        if (containsAny(combined, "医院", "医疗", "药房", "药店", "诊所", "挂号")) {
            return CategoryCatalog.MEDICAL;
        }
        if (containsAny(combined, "教育", "课程", "学费", "培训", "网课")) {
            return CategoryCatalog.EDUCATION;
        }
        if (containsAny(combined, "话费", "流量", "联通", "移动", "电信", "通讯")) {
            return CategoryCatalog.COMMUNICATION;
        }
        if (containsAny(combined, "超市", "购物", "商城", "淘宝", "京东", "拼多多",
                "便利店", "服装", "百货")) {
            return CategoryCatalog.SHOPPING;
        }
        if (containsAny(combined, "转账", "红包")) {
            return CategoryCatalog.TRANSFER;
        }
        return CategoryCatalog.OTHER;
    }

    private static double confidence(
            String source,
            String channel,
            String title,
            String text,
            String merchant,
            String direction,
            boolean paymentSignal,
            boolean softCandidate,
            long amountCents
    ) {
        double score = 0.32;
        if (source != null) {
            score += 0.06;
        }
        if (amountCents > 0) {
            score += 0.10;
        }
        if (merchant != null && !merchant.isEmpty()) {
            score += 0.14;
        }
        if (text != null && text.length() >= 8) {
            score += 0.06;
        }
        if (CaptureChannel.NOTIFICATION.equals(channel)) {
            score += 0.03;
        }
        if (paymentSignal) {
            score += 0.22;
        }
        if (title != null && !title.trim().isEmpty()) {
            score += 0.04;
        }
        if (softCandidate) {
            score -= 0.30;
        }
        if (merchant != null
                && !merchant.isEmpty()
                && containsAny(text, merchant)) {
            score += 0.06;
        }
        return Math.max(0.0, Math.min(0.96, score));
    }

    private static boolean containsAny(String text, String... words) {
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
}
