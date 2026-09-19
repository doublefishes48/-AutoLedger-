package com.autoledger.app.capture;

import com.autoledger.app.data.LedgerEntry;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PaymentTextParserTest {
    @Test
    public void rejectsAlipayFlashSaleCoupon() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_ALIPAY,
                CaptureChannel.NOTIFICATION,
                "支付宝卡包",
                "你有淘宝闪购14元红包今晚失效",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    @Test
    public void rejectsKnownMerchantPromotionMessage() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_WECHAT,
                CaptureChannel.NOTIFICATION,
                "瑞幸首席幸运官lucky",
                "9.9元喝瑞幸 [小程序] 点击领取 点关注赢免单券",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    @Test
    public void rejectsWechatGroupBuyingProductPage() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_WECHAT,
                CaptureChannel.ACCESSIBILITY,
                null,
                "塔斯汀中国汉堡 特价团 已售198万+ ¥13.99 ¥24 加倍补",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    @Test
    public void rejectsGroupBuyingCheckoutThatContainsPaymentSuccessText() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_WECHAT,
                CaptureChannel.ACCESSIBILITY,
                null,
                "团购详情 购买须知 购买团购券支付成功 ¥12.00",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    @Test
    public void rejectsWechatPaymentHistoryBrowsingPage() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_WECHAT,
                CaptureChannel.ACCESSIBILITY,
                null,
                "支付成功 ￥10.00 交易详情 支付时间 2026年8月19日 交易单号 [卡号]",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    @Test
    public void rejectsAlipayMessageListPage() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_ALIPAY,
                CaptureChannel.ACCESSIBILITY,
                null,
                "消息盒子 正新鸡排 付款成功￥10.00 点击查看全部消息 首页 消息 我的",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    @Test
    public void rejectsAlipayRecentActivityPage() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_ALIPAY,
                CaptureChannel.ACCESSIBILITY,
                null,
                "水果捞 付款成功￥9.90 1小时前 首页 理财 消息 我的",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    @Test
    public void rejectsPendingPaymentAmount() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_ALIPAY,
                CaptureChannel.ACCESSIBILITY,
                "待支付",
                "待支付 待支付金额1.50元",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    @Test
    public void rejectsHistoricalDateAsAmount() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_WECHAT,
                CaptureChannel.ACCESSIBILITY,
                null,
                "支付成功 支付时间 2026年08月19日 20:45:02 商品 京东 支付方式 零钱通",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    @Test
    public void rejectsUnionPayLotteryCoupon() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_UNIONPAY,
                CaptureChannel.NOTIFICATION,
                "玩赚中心：任务达标提醒",
                "恭喜您获得1次抽奖机会，抽最高620元立减券，点我抽奖~",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    @Test
    public void acceptsUnionPayCardConsumption() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_UNIONPAY,
                CaptureChannel.NOTIFICATION,
                "支付助手：付款成功",
                "您尾号为3264的银行卡于08日00时39分消费3.90元",
                1000L
        );
        assertNotNull(result);
        assertEquals(390L, result.amountCents);
        assertEquals(LedgerEntry.DIRECTION_EXPENSE, result.direction);
    }

    @Test
    public void acceptsAlipayRefund() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_ALIPAY,
                CaptureChannel.NOTIFICATION,
                "退款提醒",
                "你收到一笔9.80元退款，点此查看账单详情！",
                1000L
        );
        assertNotNull(result);
        assertEquals(980L, result.amountCents);
        assertEquals(LedgerEntry.DIRECTION_INCOME, result.direction);
    }

    @Test
    public void acceptsAlipayPaymentWithRewardTail() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_ALIPAY,
                CaptureChannel.NOTIFICATION,
                "交易提醒",
                "你有一笔4.82元的支出，点击领取6个支付宝积分。",
                1000L
        );
        assertNotNull("raw=" + resultText(result), result);
        assertEquals(482L, result.amountCents);
        assertEquals(LedgerEntry.DIRECTION_EXPENSE, result.direction);
    }

    @Test
    public void acceptsAlipayAutomaticDeductionWithRewardTail() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_ALIPAY,
                CaptureChannel.NOTIFICATION,
                "交易提醒",
                "你在北京极智简单科技有限公司有一笔3.00元的免密/自动扣款支付，点击领取6个支付宝积分。",
                1000L
        );
        assertNotNull("raw=" + resultText(result), result);
        assertEquals(300L, result.amountCents);
        assertEquals(LedgerEntry.DIRECTION_EXPENSE, result.direction);
        assertEquals("北京极智简单科技有限公司", result.merchant);
    }

    @Test
    public void autoConfirmsAlipayScanSuccessPage() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_ALIPAY,
                CaptureChannel.ACCESSIBILITY,
                null,
                "支付成功 回首页 ￥ 2.50 完成",
                1000L
        );
        assertNotNull("raw=" + resultText(result), result);
        assertEquals(250L, result.amountCents);
        assertEquals(LedgerEntry.DIRECTION_EXPENSE, result.direction);
        assertTrue(result.confidence >= 0.82);
    }

    @Test
    public void autoConfirmsWechatOcrSuccessPage() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_WECHAT,
                CaptureChannel.OCR,
                null,
                "支付成功 蜜雪冰城 ￥2.00 完成",
                1000L
        );
        assertNotNull("raw=" + resultText(result), result);
        assertEquals(200L, result.amountCents);
        assertEquals("蜜雪冰城", result.merchant);
        assertTrue(result.confidence >= 0.82);
    }

    @Test
    public void acceptsWechatSuccessSplitAcrossTextNodes() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_WECHAT,
                CaptureChannel.ACCESSIBILITY,
                null,
                "支付 成功 拼多多 ￥0.20 返回商家",
                1000L
        );
        assertNotNull("raw=" + resultText(result), result);
        assertEquals(20L, result.amountCents);
        assertEquals(LedgerEntry.DIRECTION_EXPENSE, result.direction);
        assertEquals("拼多多", result.merchant);
        assertTrue(result.confidence >= 0.82);
    }

    @Test
    public void usesResultMerchantInsteadOfWechatPayCodeMenu() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_WECHAT,
                CaptureChannel.ACCESSIBILITY,
                null,
                "收付款 付款码 付款条码，可向收银员出示，双击可全屏展示付款码数字 "
                        + "付款二维码,双击可全屏展示 二维码收款 面对面红包 向银行卡或手机号转账 "
                        + "赞赏码 群收款 支付成功 蜜雪冰城 ￥7.00 完成",
                1000L
        );
        assertNotNull("raw=" + resultText(result), result);
        assertEquals(700L, result.amountCents);
        assertEquals("蜜雪冰城", result.merchant);
    }

    @Test
    public void usesMerchantImmediatelyBeforePaymentSuccess() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_ALIPAY,
                CaptureChannel.ACCESSIBILITY,
                null,
                "瑞幸咖啡 菜鸟 蜜雪冰城 正新鸡排 付款成功￥10.00",
                1000L
        );
        assertNotNull("raw=" + resultText(result), result);
        assertEquals(1000L, result.amountCents);
        assertEquals("正新鸡排", result.merchant);
    }

    @Test
    public void rejectsWechatCoffeePromotionWithMerchant() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_WECHAT,
                CaptureChannel.NOTIFICATION,
                "luckincoffee瑞幸咖啡",
                "9.9元喝瑞幸 / [小程序] 9.9元点击领取",
                1000L
        );
        assertNull("raw=" + resultText(result), result);
    }

    private static String resultText(RecognitionResult result) {
        return result == null ? "null" : result.rawText;
    }

}
