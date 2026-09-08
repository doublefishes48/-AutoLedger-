package com.autoledger.app.capture;

public final class RecognitionResult {
    public final String sourceKey;
    public final String channel;
    public final String packageName;
    public final String title;
    public final String rawText;
    public final long amountCents;
    public final String direction;
    public final String category;
    public final String merchant;
    public final long occurredAt;
    public final double confidence;

    public RecognitionResult(
            String sourceKey,
            String channel,
            String packageName,
            String title,
            String rawText,
            long amountCents,
            String direction,
            String category,
            String merchant,
            long occurredAt,
            double confidence
    ) {
        this.sourceKey = sourceKey;
        this.channel = channel;
        this.packageName = packageName;
        this.title = title;
        this.rawText = rawText;
        this.amountCents = amountCents;
        this.direction = direction;
        this.category = category;
        this.merchant = merchant;
        this.occurredAt = occurredAt;
        this.confidence = confidence;
    }
}

