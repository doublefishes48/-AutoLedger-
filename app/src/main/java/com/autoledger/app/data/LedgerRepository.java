package com.autoledger.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.autoledger.app.capture.CategoryCatalog;
import com.autoledger.app.capture.CaptureChannel;
import com.autoledger.app.capture.CapturePackages;
import com.autoledger.app.capture.CsvBillImporter;
import com.autoledger.app.capture.RecognitionResult;
import com.autoledger.app.capture.SourceKey;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public final class LedgerRepository {
    private static final String TABLE_TRANSACTIONS = LedgerDatabase.TABLE_TRANSACTIONS;
    private static final String TABLE_RAW_CAPTURES = LedgerDatabase.TABLE_RAW_CAPTURES;
    private static final String TABLE_SETTINGS = LedgerDatabase.TABLE_SETTINGS;
    private static final double AUTO_CONFIRM_THRESHOLD = 0.82;
    private static final long LARGE_EXPENSE_MULTIPLIER = 2L;
    private static final long RECENT_DUPLICATE_WINDOW_MS = 12L * 60L * 60L * 1000L;
    private static final long RECENT_AMOUNT_WINDOW_MS = 30L * 60L * 1000L;
    private static final long EVIDENCE_MERGE_WINDOW_MS = 5L * 60L * 1000L;
    private static final int AMOUNT_CONFLICT_THRESHOLD = 3;
    private static final java.util.regex.Pattern CURRENCY_AMOUNT = java.util.regex.Pattern.compile(
            "[¥￥]\\s*([0-9]{1,9}(?:\\.[0-9]{1,2})?)"
                    + "|([0-9]{1,9}(?:\\.[0-9]{1,2})?)\\s*元"
    );

    private static volatile LedgerRepository instance;
    private final Context context;
    private final LedgerDatabase database;

    public static LedgerRepository get(Context context) {
        if (instance == null) {
            synchronized (LedgerRepository.class) {
                if (instance == null) {
                    instance = new LedgerRepository(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private LedgerRepository(Context context) {
        this.context = context.getApplicationContext();
        this.database = LedgerDatabase.get(context);
    }

    public static class Summary {
        public long expenseCents;
        public long incomeCents;
        public int pendingCount;
    }

    public static class BillImportResult {
        public int imported;
        public int duplicate;
        public int skipped;
    }

    public List<LedgerEntry> recentTransactions(int limit) {
        List<LedgerEntry> result = new ArrayList<>();
        synchronized (database) {
            SQLiteDatabase db = database.getReadableDatabase();
            Cursor cursor = db.rawQuery(
                    "SELECT * FROM " + TABLE_TRANSACTIONS
                            + " ORDER BY occurred_at DESC, id DESC LIMIT ?",
                    new String[]{String.valueOf(limit)}
            );
            try {
                while (cursor.moveToNext()) {
                    result.add(readTransaction(cursor));
                }
            } finally {
                cursor.close();
            }
        }
        return result;
    }

    public List<LedgerEntry> transactionsForMonth(Calendar month, int limit) {
        long[] bounds = monthBounds(month);
        return transactionsForRange(bounds[0], bounds[1], limit);
    }

    public List<LedgerEntry> transactionsForDay(Calendar day, int limit) {
        long[] bounds = dayBounds(day);
        return transactionsForRange(bounds[0], bounds[1], limit);
    }

    private List<LedgerEntry> transactionsForRange(long start, long end, int limit) {
        List<LedgerEntry> result = new ArrayList<>();
        synchronized (database) {
            SQLiteDatabase db = database.getReadableDatabase();
            Cursor cursor = db.rawQuery(
                    "SELECT * FROM " + TABLE_TRANSACTIONS
                            + " WHERE occurred_at>=? AND occurred_at<?"
                            + " ORDER BY occurred_at DESC, id DESC LIMIT ?",
                    new String[]{
                            String.valueOf(start),
                            String.valueOf(end),
                            String.valueOf(limit)
                    }
            );
            try {
                while (cursor.moveToNext()) {
                    result.add(readTransaction(cursor));
                }
            } finally {
                cursor.close();
            }
        }
        return result;
    }

    public List<RawCaptureRecord> pendingCaptures() {
        List<RawCaptureRecord> result = new ArrayList<>();
        synchronized (database) {
            SQLiteDatabase db = database.getReadableDatabase();
            Cursor cursor = db.query(
                    TABLE_RAW_CAPTURES,
                    null,
                    "status=?",
                    new String[]{RawCaptureRecord.STATUS_PENDING},
                    null,
                    null,
                    "created_at DESC, id DESC",
                    "200"
            );
            try {
                while (cursor.moveToNext()) {
                    result.add(readRawCapture(cursor));
                }
            } finally {
                cursor.close();
            }
        }
        return result;
    }

    public Summary loadSummary() {
        return loadSummary(Calendar.getInstance());
    }

    public Summary loadSummary(Calendar month) {
        long[] bounds = monthBounds(month);
        return loadSummaryRange(bounds[0], bounds[1]);
    }

    public Summary loadDaySummary(Calendar day) {
        long[] bounds = dayBounds(day);
        return loadSummaryRange(bounds[0], bounds[1]);
    }

    private Summary loadSummaryRange(long start, long end) {
        Summary summary = new Summary();
        synchronized (database) {
            SQLiteDatabase db = database.getReadableDatabase();
            Cursor cursor = db.rawQuery(
                    "SELECT "
                            + "COALESCE(SUM(CASE WHEN direction='EXPENSE' THEN amount_cents ELSE 0 END),0) AS expense,"
                            + "COALESCE(SUM(CASE WHEN direction='INCOME' THEN amount_cents ELSE 0 END),0) AS income "
                            + "FROM " + TABLE_TRANSACTIONS
                            + " WHERE occurred_at>=? AND occurred_at<?",
                    new String[]{String.valueOf(start), String.valueOf(end)}
            );
            try {
                if (cursor.moveToFirst()) {
                    summary.expenseCents = cursor.getLong(cursor.getColumnIndexOrThrow("expense"));
                    summary.incomeCents = cursor.getLong(cursor.getColumnIndexOrThrow("income"));
                }
            } finally {
                cursor.close();
            }

            Cursor count = db.rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE_RAW_CAPTURES + " WHERE status=?",
                    new String[]{RawCaptureRecord.STATUS_PENDING}
            );
            try {
                if (count.moveToFirst()) {
                    summary.pendingCount = count.getInt(0);
                }
            } finally {
                count.close();
            }
        }
        return summary;
    }

    private static long[] monthBounds(Calendar source) {
        Calendar calendar = (Calendar) source.clone();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.set(Calendar.DAY_OF_MONTH, 1);
        long start = calendar.getTimeInMillis();
        calendar.add(Calendar.MONTH, 1);
        return new long[]{start, calendar.getTimeInMillis()};
    }

    private static long[] dayBounds(Calendar source) {
        Calendar calendar = (Calendar) source.clone();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long start = calendar.getTimeInMillis();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        return new long[]{start, calendar.getTimeInMillis()};
    }

    public void addManualTransaction(
            long amountCents,
            String direction,
            String category,
            String merchant,
            String account,
            String note
    ) {
        synchronized (database) {
            SQLiteDatabase db = database.getWritableDatabase();
            ContentValues values = transactionValues(
                    amountCents,
                    direction,
                    category == null ? CategoryCatalog.OTHER : category,
                    merchant == null ? "" : merchant,
                    account == null ? "手动" : account,
                    note == null ? "" : note,
                    System.currentTimeMillis(),
                    SourceKey.MANUAL
            );
            db.insert(TABLE_TRANSACTIONS, null, values);
        }
    }

    public BillImportResult importBillRows(List<CsvBillImporter.BillRow> rows) {
        BillImportResult result = new BillImportResult();
        if (rows == null || rows.isEmpty()) {
            return result;
        }
        synchronized (database) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                for (CsvBillImporter.BillRow row : rows) {
                    if (row == null
                            || row.sourceKey == null
                            || row.amountCents <= 0
                            || (!LedgerEntry.DIRECTION_EXPENSE.equals(row.direction)
                            && !LedgerEntry.DIRECTION_INCOME.equals(row.direction))) {
                        result.skipped++;
                        continue;
                    }
                    String sourceRef = row.orderNo == null ? "" : row.orderNo.trim();
                    if (hasExactDuplicate(db, row, sourceRef)) {
                        result.duplicate++;
                        continue;
                    }

                    String account = row.account == null || row.account.isEmpty()
                            ? sourceLabel(row.sourceKey)
                            : row.account;
                    ContentValues values = transactionValues(
                            row.amountCents,
                            row.direction,
                            row.category == null ? CategoryCatalog.OTHER : row.category,
                            row.merchant == null ? "" : row.merchant,
                            account,
                            row.note == null ? "" : row.note,
                            row.occurredAt,
                            row.sourceKey
                    );
                    values.put("source_ref", sourceRef);
                    long inserted = db.insertWithOnConflict(
                            TABLE_TRANSACTIONS,
                            null,
                            values,
                            SQLiteDatabase.CONFLICT_IGNORE
                    );
                    if (inserted == -1) {
                        result.duplicate++;
                    } else {
                        result.imported++;
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        return result;
    }

    public void updateTransactionCategory(long id, String category) {
        synchronized (database) {
            ContentValues values = new ContentValues();
            values.put("category", category);
            database.getWritableDatabase().update(
                    TABLE_TRANSACTIONS,
                    values,
                    "id=?",
                    new String[]{String.valueOf(id)}
            );
        }
    }

    public void deleteTransaction(long id) {
        synchronized (database) {
            database.getWritableDatabase().delete(
                    TABLE_TRANSACTIONS,
                    "id=?",
                    new String[]{String.valueOf(id)}
            );
        }
    }

    public int ingestCapture(RecognitionResult result) {
        if (!isSourceEnabled(result.sourceKey)) {
            return 0;
        }

        int writeResult;
        synchronized (database) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues raw = new ContentValues();
                raw.put("fingerprint", fingerprintFor(result));
                raw.put("channel", result.channel);
                raw.put("package_name", result.packageName == null ? "" : result.packageName);
                raw.put("source_key", result.sourceKey == null ? SourceKey.MANUAL : result.sourceKey);
                raw.put("title", result.title == null ? "" : result.title);
                raw.put("raw_text", result.rawText == null ? "" : result.rawText);
                raw.put("amount_cents", result.amountCents);
                raw.put("direction", result.direction);
                raw.put("category", result.category);
                raw.put("merchant", result.merchant);
                raw.put("account", sourceLabel(result.sourceKey));
                raw.put("occurred_at", result.occurredAt);
                raw.put("confidence", result.confidence);
                raw.put("status", RawCaptureRecord.STATUS_PENDING);
                raw.put("created_at", System.currentTimeMillis());

                long rawId = db.insertWithOnConflict(
                        TABLE_RAW_CAPTURES,
                        null,
                        raw,
                        SQLiteDatabase.CONFLICT_IGNORE
                );
                if (rawId == -1) {
                    writeResult = 2;
                } else {
                    String reviewReason = reviewReason(db, result);
                    if (isAutoConfirmEnabled()
                            && result.confidence >= AUTO_CONFIRM_THRESHOLD
                            && reviewReason == null) {
                        ContentValues transaction = transactionValues(
                                result.amountCents,
                                result.direction,
                                result.category,
                                result.merchant,
                                sourceLabel(result.sourceKey),
                                "",
                                result.occurredAt,
                                result.sourceKey
                        );
                        db.insert(TABLE_TRANSACTIONS, null, transaction);
                        markCaptureStatus(db, rawId, RawCaptureRecord.STATUS_CONFIRMED);
                    } else if (reviewReason != null) {
                        DebugLog.append(
                                context,
                                "capture held for confirmation reason=" + reviewReason
                                        + " amount=" + result.amountCents
                        );
                    }
                    writeResult = 1;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
        return writeResult;
    }

    private static String reviewReason(
            SQLiteDatabase db,
            RecognitionResult result
    ) {
        if (LedgerEntry.DIRECTION_EXPENSE.equals(result.direction)
                && exceedsHistoricalExpenseMaximum(
                result.amountCents,
                historicalMaxExpense(db)
        )) {
            return "large_expense";
        }
        if (CaptureChannel.ACCESSIBILITY.equals(result.channel)
                && isAwaitingRecipientConfirmation(result.rawText)) {
            return "awaiting_recipient_confirmation";
        }
        if (isSuspiciousMerchant(result.merchant)) {
            return "suspicious_merchant";
        }
        if (CaptureChannel.ACCESSIBILITY.equals(result.channel)
                && countCurrencyAmounts(result.rawText) >= AMOUNT_CONFLICT_THRESHOLD) {
            return "amount_conflict";
        }
        if (hasRecentAmountDuplicate(db, result)) {
            return "amount_duplicate";
        }
        if (hasRecentDuplicate(db, result)) {
            return "recent_duplicate";
        }
        return null;
    }

    static boolean isAwaitingRecipientConfirmation(String text) {
        if (text == null) {
            return false;
        }
        String compact = text.replaceAll("\\s+", "");
        return compact.contains("确认收款")
                || compact.contains("等待对方确认")
                || compact.contains("待对方确认")
                || compact.contains("待入账")
                || compact.contains("处理中");
    }

    static boolean exceedsHistoricalExpenseMaximum(
            long amountCents,
            long historicalMaximumCents
    ) {
        return historicalMaximumCents > 0
                && amountCents > historicalMaximumCents * LARGE_EXPENSE_MULTIPLIER;
    }

    private static long historicalMaxExpense(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery(
                "SELECT COALESCE(MAX(amount_cents),0) FROM " + TABLE_TRANSACTIONS
                        + " WHERE direction='EXPENSE'",
                null
        );
        try {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } finally {
            cursor.close();
        }
    }

    static int countCurrencyAmounts(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        java.util.Set<String> amounts = new java.util.HashSet<>();
        java.util.regex.Matcher matcher = CURRENCY_AMOUNT.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value == null) {
                value = matcher.group(2);
            }
            if (value != null) {
                amounts.add(value);
            }
        }
        return amounts.size();
    }

    static boolean isSuspiciousMerchant(String merchant) {
        if (merchant == null || merchant.isEmpty()) {
            return false;
        }
        String value = merchant.trim();
        return value.matches("\\d{1,2}:\\d{2}")
                || value.matches("\\d{1,2}月\\d{1,2}日")
                || value.startsWith("¥")
                || value.startsWith("￥")
                || (value.contains("尾号") && value.contains("银行卡"))
                || value.contains("点击")
                || value.contains("领取")
                || value.equals("全部会话")
                || value.equals("消息盒子")
                || value.equals("消息")
                || value.equals("首页")
                || value.equals("我的")
                || value.equals("更多")
                || value.equals("全部")
                || value.equals("收付款")
                || value.equals("扫一扫")
                || value.equals("卡包")
                || value.equals("出行")
                || value.equals("通讯录")
                || value.matches("消息\\(\\d+.*");
    }

    private static boolean hasRecentAmountDuplicate(
            SQLiteDatabase db,
            RecognitionResult result
    ) {
        Cursor cursor = db.query(
                TABLE_TRANSACTIONS,
                new String[]{"id"},
                "source_key=? AND direction=? AND amount_cents=?"
                        + " AND occurred_at>=? AND occurred_at<=?",
                new String[]{
                        result.sourceKey,
                        result.direction,
                        String.valueOf(result.amountCents),
                        String.valueOf(result.occurredAt - RECENT_AMOUNT_WINDOW_MS),
                        String.valueOf(result.occurredAt)
                },
                null,
                null,
                null,
                "1"
        );
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    private static boolean hasRecentDuplicate(
            SQLiteDatabase db,
            RecognitionResult result
    ) {
        Cursor cursor = db.query(
                TABLE_TRANSACTIONS,
                new String[]{"id"},
                "source_key=? AND direction=? AND amount_cents=? AND merchant=?"
                        + " AND occurred_at>=? AND occurred_at<=?",
                new String[]{
                        result.sourceKey,
                        result.direction,
                        String.valueOf(result.amountCents),
                        result.merchant == null ? "" : result.merchant,
                        String.valueOf(result.occurredAt - RECENT_DUPLICATE_WINDOW_MS),
                        String.valueOf(result.occurredAt)
                },
                null,
                null,
                null,
                "1"
        );
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    public void confirmRawCapture(long rawCaptureId) {
        synchronized (database) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                RawCaptureRecord raw = findRawCapture(db, rawCaptureId);
                if (raw == null || !RawCaptureRecord.STATUS_PENDING.equals(raw.status)) {
                    db.setTransactionSuccessful();
                    return;
                }
                ContentValues transaction = transactionValues(
                        raw.amountCents,
                        raw.direction,
                        raw.category,
                        raw.merchant,
                        sourceLabel(raw.sourceKey),
                        "",
                        raw.occurredAt,
                        raw.sourceKey
                );
                db.insert(TABLE_TRANSACTIONS, null, transaction);
                markCaptureStatus(db, raw.id, RawCaptureRecord.STATUS_CONFIRMED);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }

    public boolean canOverwriteRecentTransaction(RawCaptureRecord raw) {
        if (raw == null || raw.merchant == null || raw.merchant.isEmpty()) {
            return false;
        }
        synchronized (database) {
            SQLiteDatabase db = database.getReadableDatabase();
            long transactionId = findRecentTransactionId(db, raw);
            if (transactionId < 0) {
                return false;
            }
            Cursor cursor = db.query(
                    TABLE_TRANSACTIONS,
                    new String[]{"merchant"},
                    "id=?",
                    new String[]{String.valueOf(transactionId)},
                    null,
                    null,
                    null
            );
            try {
                if (!cursor.moveToFirst()) {
                    return false;
                }
                String existingMerchant = cursor.getString(0);
                return existingMerchant == null
                        || existingMerchant.isEmpty()
                        || !existingMerchant.equals(raw.merchant);
            } finally {
                cursor.close();
            }
        }
    }

    public boolean overwriteRecentTransaction(long rawCaptureId) {
        synchronized (database) {
            SQLiteDatabase db = database.getWritableDatabase();
            db.beginTransaction();
            try {
                RawCaptureRecord raw = findRawCapture(db, rawCaptureId);
                if (raw == null
                        || raw.status == null
                        || !RawCaptureRecord.STATUS_PENDING.equals(raw.status)
                        || raw.merchant == null
                        || raw.merchant.isEmpty()) {
                    return false;
                }
                long transactionId = findRecentTransactionId(db, raw);
                if (transactionId < 0) {
                    return false;
                }
                ContentValues values = new ContentValues();
                values.put("merchant", raw.merchant);
                values.put("category", raw.category);
                db.update(
                        TABLE_TRANSACTIONS,
                        values,
                        "id=?",
                        new String[]{String.valueOf(transactionId)}
                );
                markCaptureStatus(db, rawCaptureId, RawCaptureRecord.STATUS_CONFIRMED);
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }
    }

    private static long findRecentTransactionId(
            SQLiteDatabase db,
            RawCaptureRecord raw
    ) {
        Cursor cursor = db.query(
                TABLE_TRANSACTIONS,
                new String[]{"id"},
                "source_key=? AND direction=? AND amount_cents=?"
                        + " AND occurred_at>=? AND occurred_at<=?",
                new String[]{
                        raw.sourceKey,
                        raw.direction,
                        String.valueOf(raw.amountCents),
                        String.valueOf(raw.occurredAt - EVIDENCE_MERGE_WINDOW_MS),
                        String.valueOf(raw.occurredAt + EVIDENCE_MERGE_WINDOW_MS)
                },
                null,
                null,
                "occurred_at DESC, id DESC",
                "1"
        );
        try {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
            return -1L;
        } finally {
            cursor.close();
        }
    }

    public void ignoreRawCapture(long rawCaptureId) {
        synchronized (database) {
            markCaptureStatus(
                    database.getWritableDatabase(),
                    rawCaptureId,
                    RawCaptureRecord.STATUS_IGNORED
            );
        }
    }

    public boolean isSourceEnabled(String sourceKey) {
        if (SourceKey.MANUAL.equals(sourceKey)) {
            return true;
        }
        String value = getSetting("source_enabled_" + sourceKey);
        if (SourceKey.ROOT_HOOK.equals(sourceKey)) {
            return "1".equals(value);
        }
        return value == null || "1".equals(value);
    }

    public void setSourceEnabled(String sourceKey, boolean enabled) {
        setSetting("source_enabled_" + sourceKey, enabled ? "1" : "0");
    }

    public boolean isAutoConfirmEnabled() {
        return !"0".equals(getSetting("auto_confirm"));
    }

    public void setAutoConfirmEnabled(boolean enabled) {
        setSetting("auto_confirm", enabled ? "1" : "0");
    }

    public boolean isKeepAliveEnabled() {
        return !"0".equals(getSetting("keep_alive_enabled"));
    }

    public void setKeepAliveEnabled(boolean enabled) {
        setSetting("keep_alive_enabled", enabled ? "1" : "0");
    }

    public boolean isHideFromRecentsEnabled() {
        return "1".equals(getSetting("hide_from_recents"));
    }

    public void setHideFromRecentsEnabled(boolean enabled) {
        setSetting("hide_from_recents", enabled ? "1" : "0");
    }

    private String getSetting(String key) {
        synchronized (database) {
            Cursor cursor = database.getReadableDatabase().query(
                    TABLE_SETTINGS,
                    new String[]{"value"},
                    "key=?",
                    new String[]{key},
                    null,
                    null,
                    null
            );
            try {
                if (cursor.moveToFirst()) {
                    return cursor.getString(0);
                }
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    private void setSetting(String key, String value) {
        synchronized (database) {
            ContentValues values = new ContentValues();
            values.put("key", key);
            values.put("value", value);
            database.getWritableDatabase().insertWithOnConflict(
                    TABLE_SETTINGS,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
            );
        }
    }

    private RawCaptureRecord findRawCapture(SQLiteDatabase db, long id) {
        Cursor cursor = db.query(
                TABLE_RAW_CAPTURES,
                null,
                "id=?",
                new String[]{String.valueOf(id)},
                null,
                null,
                null
        );
        try {
            return cursor.moveToFirst() ? readRawCapture(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    private static boolean hasExactDuplicate(
            SQLiteDatabase db,
            CsvBillImporter.BillRow row,
            String sourceRef
    ) {
        String[] projection = new String[]{"id"};
        if (!sourceRef.isEmpty()) {
            Cursor refCursor = db.query(
                    TABLE_TRANSACTIONS,
                    projection,
                    "source_key=? AND source_ref=?",
                    new String[]{row.sourceKey, sourceRef},
                    null,
                    null,
                    null,
                    "1"
            );
            try {
                if (refCursor.moveToFirst()) {
                    return true;
                }
            } finally {
                refCursor.close();
            }
        }
        String selection = "source_key=? AND direction=? AND amount_cents=? AND merchant=? AND occurred_at=?";
        String[] args = new String[]{
                row.sourceKey,
                row.direction,
                String.valueOf(row.amountCents),
                row.merchant == null ? "" : row.merchant,
                String.valueOf(row.occurredAt)
        };
        Cursor cursor = db.query(
                TABLE_TRANSACTIONS,
                projection,
                selection,
                args,
                null,
                null,
                null,
                "1"
        );
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    private static void markCaptureStatus(SQLiteDatabase db, long rawId, String status) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        db.update(
                TABLE_RAW_CAPTURES,
                values,
                "id=?",
                new String[]{String.valueOf(rawId)}
        );
    }

    private static LedgerEntry readTransaction(Cursor cursor) {
        return new LedgerEntry(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("amount_cents")),
                cursor.getString(cursor.getColumnIndexOrThrow("direction")),
                cursor.getString(cursor.getColumnIndexOrThrow("category")),
                cursor.getString(cursor.getColumnIndexOrThrow("merchant")),
                cursor.getString(cursor.getColumnIndexOrThrow("account")),
                cursor.getString(cursor.getColumnIndexOrThrow("note")),
                cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_key"))
        );
    }

    private static RawCaptureRecord readRawCapture(Cursor cursor) {
        return new RawCaptureRecord(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("fingerprint")),
                cursor.getString(cursor.getColumnIndexOrThrow("channel")),
                cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                cursor.getString(cursor.getColumnIndexOrThrow("source_key")),
                cursor.getString(cursor.getColumnIndexOrThrow("title")),
                cursor.getString(cursor.getColumnIndexOrThrow("raw_text")),
                cursor.getLong(cursor.getColumnIndexOrThrow("amount_cents")),
                cursor.getString(cursor.getColumnIndexOrThrow("direction")),
                cursor.getString(cursor.getColumnIndexOrThrow("category")),
                cursor.getString(cursor.getColumnIndexOrThrow("merchant")),
                cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at")),
                cursor.getDouble(cursor.getColumnIndexOrThrow("confidence")),
                cursor.getString(cursor.getColumnIndexOrThrow("status"))
        );
    }

    private static ContentValues transactionValues(
            long amountCents,
            String direction,
            String category,
            String merchant,
            String account,
            String note,
            long occurredAt,
            String sourceKey
    ) {
        ContentValues values = new ContentValues();
        values.put("amount_cents", amountCents);
        values.put("direction", direction);
        values.put("category", category);
        values.put("merchant", merchant == null ? "" : merchant);
        values.put("account", account == null ? "" : account);
        values.put("note", note == null ? "" : note);
        values.put("occurred_at", occurredAt);
        values.put("source_key", sourceKey);
        values.put("created_at", System.currentTimeMillis());
        return values;
    }

    private static String fingerprintFor(RecognitionResult result) {
        String raw = (result.sourceKey == null ? "" : result.sourceKey)
                + "|" + result.direction
                + "|" + result.amountCents
                + "|" + normalizeMerchant(result.merchant)
                + "|" + (result.occurredAt / 60_000L);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.substring(0, 32);
        } catch (Exception ignored) {
            return String.valueOf(raw.hashCode());
        }
    }

    private static String normalizeMerchant(String merchant) {
        return merchant == null ? "" : merchant.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String sourceLabel(String sourceKey) {
        if (SourceKey.WECHAT.equals(sourceKey)) {
            return "微信";
        }
        if (SourceKey.ALIPAY.equals(sourceKey)) {
            return "支付宝";
        }
        if (SourceKey.UNIONPAY.equals(sourceKey)) {
            return "云闪付";
        }
        if (SourceKey.ROOT_HOOK.equals(sourceKey)) {
            return "Root抓取";
        }
        return "手动";
    }

    private static String sourceKeyForRaw(RawCaptureRecord raw) {
        String source = CapturePackages.toSourceKey(raw.packageName);
        return source == null ? raw.sourceKey : source;
    }
}
