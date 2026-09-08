package com.autoledger.app.data;

public final class RawCaptureRecord {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_IGNORED = "IGNORED";

    public final long id;
    public final String fingerprint;
    public final String channel;
    public final String packageName;
    public final String sourceKey;
    public final String title;
    public final String rawText;
    public final long amountCents;
    public final String direction;
    public final String category;
    public final String merchant;
    public final long occurredAt;
    public final double confidence;
    public final String status;

    public RawCaptureRecord(
            long id,
            String fingerprint,
            String channel,
            String packageName,
            String sourceKey,
            String title,
            String rawText,
            long amountCents,
            String direction,
            String category,
            String merchant,
            long occurredAt,
            double confidence,
            String status
    ) {
        this.id = id;
        this.fingerprint = fingerprint;
        this.channel = channel;
        this.packageName = packageName;
        this.sourceKey = sourceKey;
        this.title = title;
        this.rawText = rawText;
        this.amountCents = amountCents;
        this.direction = direction;
        this.category = category;
        this.merchant = merchant;
        this.occurredAt = occurredAt;
        this.confidence = confidence;
        this.status = status;
    }
}
