package com.autoledger.app.data;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LedgerRepositoryTest {
    @Test
    public void largeExpenseOnlyRequiresReviewAfterHistoryExists() {
        assertFalse(LedgerRepository.exceedsHistoricalExpenseMaximum(100_000L, 0L));
        assertFalse(LedgerRepository.exceedsHistoricalExpenseMaximum(20_000L, 10_000L));
        assertTrue(LedgerRepository.exceedsHistoricalExpenseMaximum(20_001L, 10_000L));
    }

    @Test
    public void dateMisparseWouldBeHeldForReview() {
        assertTrue(
                LedgerRepository.exceedsHistoricalExpenseMaximum(202_600L, 35_905L)
        );
    }

    @Test
    public void multipleAmountsAndTimeLikeMerchantsAreSuspicious() {
        assertTrue(
                LedgerRepository.countCurrencyAmounts(
                        "支付成功 ￥9.90 ￥12.90 ￥27.58 交易详情"
                ) >= 3
        );
        assertTrue(LedgerRepository.isSuspiciousMerchant("13:02"));
        assertTrue(LedgerRepository.isSuspiciousMerchant("¥10.00"));
        assertFalse(LedgerRepository.isSuspiciousMerchant("塔斯汀"));
    }

    @Test
    public void transferAwaitingRecipientConfirmationRequiresReview() {
        assertTrue(
                LedgerRepository.isAwaitingRecipientConfirmation(
                        "支付成功 待饮水确认收款 ￥14.00 完成"
                )
        );
        assertFalse(
                LedgerRepository.isAwaitingRecipientConfirmation(
                        "支付成功 亿印图文设计店 ￥0.40 返回商家"
                )
        );
    }
}
