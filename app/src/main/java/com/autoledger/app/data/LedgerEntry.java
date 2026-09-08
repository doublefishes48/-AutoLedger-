package com.autoledger.app.data;

public final class LedgerEntry {
    public static final String DIRECTION_EXPENSE = "EXPENSE";
    public static final String DIRECTION_INCOME = "INCOME";

    public final long id;
    public final long amountCents;
    public final String direction;
    public final String category;
    public final String merchant;
    public final String account;
    public final String note;
    public final long occurredAt;
    public final String sourceKey;

    public LedgerEntry(
            long id,
            long amountCents,
            String direction,
            String category,
            String merchant,
            String account,
            String note,
            long occurredAt,
            String sourceKey
    ) {
        this.id = id;
        this.amountCents = amountCents;
        this.direction = direction;
        this.category = category;
        this.merchant = merchant;
        this.account = account;
        this.note = note;
        this.occurredAt = occurredAt;
        this.sourceKey = sourceKey;
    }

    public boolean isExpense() {
        return DIRECTION_EXPENSE.equals(direction);
    }
}

