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
    public void keepsRealWechatCoffeeAsPendingWithMerchant() {
        RecognitionResult result = PaymentTextParser.parse(
                CapturePackages.PACKAGE_WECHAT,
                CaptureChannel.NOTIFICATION,
                "luckincoffee瑞幸咖啡",
                "9.9元喝瑞幸 / [小程序] 9.9元点击领取",
                1000L
        );
        assertNotNull(result);
        assertEquals(990L, result.amountCents);
        assertEquals(LedgerEntry.DIRECTION_EXPENSE, result.direction);
        assertEquals("瑞幸咖啡", result.merchant);
        assertTrue(result.confidence < 0.82);
    }

    private static String resultText(RecognitionResult result) {
        return result == null ? "null" : result.rawText;
    }

}
