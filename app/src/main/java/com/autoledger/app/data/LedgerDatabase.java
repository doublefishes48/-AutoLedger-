package com.autoledger.app.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class LedgerDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "autoledger.db";
    private static final int DB_VERSION = 10;
    private static volatile LedgerDatabase instance;

    public static final String TABLE_TRANSACTIONS = "transactions";
    public static final String TABLE_RAW_CAPTURES = "raw_captures";
    public static final String TABLE_SETTINGS = "settings";

    public static LedgerDatabase get(Context context) {
        if (instance == null) {
            synchronized (LedgerDatabase.class) {
                if (instance == null) {
                    instance = new LedgerDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private LedgerDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE " + TABLE_TRANSACTIONS + " ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "amount_cents INTEGER NOT NULL,"
                        + "direction TEXT NOT NULL,"
                        + "category TEXT NOT NULL,"
                        + "merchant TEXT NOT NULL DEFAULT '',"
                        + "account TEXT NOT NULL DEFAULT '',"
                        + "note TEXT NOT NULL DEFAULT '',"
                        + "source_ref TEXT NOT NULL DEFAULT '',"
                        + "occurred_at INTEGER NOT NULL,"
                        + "source_key TEXT NOT NULL,"
                        + "created_at INTEGER NOT NULL)"
        );
        db.execSQL(
                "CREATE TABLE " + TABLE_RAW_CAPTURES + " ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "fingerprint TEXT NOT NULL UNIQUE,"
                        + "channel TEXT NOT NULL,"
                        + "package_name TEXT NOT NULL DEFAULT '',"
                        + "source_key TEXT NOT NULL,"
                        + "title TEXT NOT NULL DEFAULT '',"
                        + "raw_text TEXT NOT NULL DEFAULT '',"
                        + "amount_cents INTEGER NOT NULL,"
                        + "direction TEXT NOT NULL,"
                        + "category TEXT NOT NULL,"
                        + "merchant TEXT NOT NULL DEFAULT '',"
                        + "account TEXT NOT NULL DEFAULT '',"
                        + "occurred_at INTEGER NOT NULL,"
                        + "confidence REAL NOT NULL,"
                        + "status TEXT NOT NULL,"
                        + "created_at INTEGER NOT NULL)"
        );
        db.execSQL(
                "CREATE TABLE " + TABLE_SETTINGS + " ("
                        + "key TEXT PRIMARY KEY,"
                        + "value TEXT NOT NULL)"
        );
        db.execSQL("CREATE INDEX idx_transactions_occurred_at ON " + TABLE_TRANSACTIONS + "(occurred_at)");
        db.execSQL(
                "CREATE INDEX idx_transactions_source_ref ON " + TABLE_TRANSACTIONS
                        + "(source_key, source_ref) WHERE source_ref <> ''"
        );
        db.execSQL("CREATE INDEX idx_raw_captures_status ON " + TABLE_RAW_CAPTURES + "(status)");

        seedSettings(db);
    }

    private static void seedSettings(SQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO settings(key, value) VALUES('source_enabled_WECHAT','1')");
        db.execSQL("INSERT OR IGNORE INTO settings(key, value) VALUES('source_enabled_ALIPAY','1')");
        db.execSQL("INSERT OR IGNORE INTO settings(key, value) VALUES('source_enabled_UNIONPAY','1')");
        db.execSQL("INSERT OR IGNORE INTO settings(key, value) VALUES('source_enabled_ROOT_HOOK','0')");
        db.execSQL("INSERT OR IGNORE INTO settings(key, value) VALUES('auto_confirm','1')");
        db.execSQL("INSERT OR IGNORE INTO settings(key, value) VALUES('keep_alive_enabled','1')");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(
                    "ALTER TABLE " + TABLE_TRANSACTIONS
                            + " ADD COLUMN source_ref TEXT NOT NULL DEFAULT ''"
            );
        }
        if (oldVersion < 3) {
            purgePromotionCaptures(db);
        }
        if (oldVersion < 4) {
            purgeKnownParseFailures(db);
        }
        if (oldVersion < 5) {
            dedupeUnreasonableHistory(db);
        }
        if (oldVersion < 6) {
            purgeWechatProductPages(db);
        }
        if (oldVersion < 7) {
            dedupeSameDayExactHistory(db);
        }
        if (oldVersion < 8) {
            purgeMessageListDuplicates(db);
        }
        if (oldVersion < 9) {
            purgeWechatBrowsingHistory(db);
        }
        if (oldVersion < 10) {
            purgeAlipayEvidenceDuplicates(db);
        }
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_transactions_source_ref ON " + TABLE_TRANSACTIONS
                        + "(source_key, source_ref) WHERE source_ref <> ''"
        );
    }

    private static void purgePromotionCaptures(SQLiteDatabase db) {
        String falseCapture =
                "("
                        + "raw_text LIKE '%抽奖%'"
                        + " OR raw_text LIKE '%立减券%'"
                        + " OR raw_text LIKE '%红包%'"
                        + " OR raw_text LIKE '%优惠券%'"
                        + " OR raw_text LIKE '%代金券%'"
                        + " OR raw_text LIKE '%免单券%'"
                        + " OR raw_text LIKE '%点击领取%'"
                        + " OR raw_text LIKE '%待领取%'"
                        + " OR raw_text LIKE '%会员中心%'"
                        + " OR raw_text LIKE '%待支付%'"
                        + " OR raw_text LIKE '%待付款%'"
                        + " OR raw_text LIKE '%摇一摇%'"
                        + ") AND NOT ("
                        + "raw_text LIKE '%支付成功%'"
                        + " OR raw_text LIKE '%付款成功%'"
                        + " OR raw_text LIKE '%交易成功%'"
                        + " OR raw_text LIKE '%支付完成%'"
                        + " OR raw_text LIKE '%付款完成%'"
                        + " OR raw_text LIKE '%你有一笔%'"
                        + " OR (raw_text LIKE '%支出%' AND raw_text NOT LIKE '%待支付%')"
                        + ")";

        db.execSQL(
                "DELETE FROM " + TABLE_TRANSACTIONS
                        + " WHERE EXISTS (SELECT 1 FROM " + TABLE_RAW_CAPTURES
                        + " WHERE " + TABLE_RAW_CAPTURES + ".source_key=" + TABLE_TRANSACTIONS + ".source_key"
                        + " AND " + TABLE_RAW_CAPTURES + ".amount_cents=" + TABLE_TRANSACTIONS + ".amount_cents"
                        + " AND " + TABLE_RAW_CAPTURES + ".direction=" + TABLE_TRANSACTIONS + ".direction"
                        + " AND ABS(" + TABLE_RAW_CAPTURES + ".occurred_at-" + TABLE_TRANSACTIONS + ".occurred_at)<300000"
                        + " AND " + falseCapture
                        + ")"
        );
        db.execSQL(
                "UPDATE " + TABLE_RAW_CAPTURES
                        + " SET status='IGNORED' WHERE " + falseCapture
        );
    }

    private static void purgeKnownParseFailures(SQLiteDatabase db) {
        String knownBadCapture =
                "amount_cents=202600"
                        + " AND raw_text LIKE '%京东%'"
                        + " AND raw_text LIKE '%支付时间%'"
                        + " AND raw_text LIKE '%2026年%'";
        db.execSQL(
                "DELETE FROM " + TABLE_TRANSACTIONS
                        + " WHERE EXISTS (SELECT 1 FROM " + TABLE_RAW_CAPTURES
                        + " WHERE " + TABLE_RAW_CAPTURES + ".source_key=" + TABLE_TRANSACTIONS + ".source_key"
                        + " AND " + TABLE_RAW_CAPTURES + ".amount_cents=" + TABLE_TRANSACTIONS + ".amount_cents"
                        + " AND " + TABLE_RAW_CAPTURES + ".direction=" + TABLE_TRANSACTIONS + ".direction"
                        + " AND ABS(" + TABLE_RAW_CAPTURES + ".occurred_at-" + TABLE_TRANSACTIONS + ".occurred_at)<300000"
                        + " AND " + knownBadCapture
                        + ")"
        );
        db.execSQL(
                "UPDATE " + TABLE_RAW_CAPTURES
                        + " SET status='IGNORED' WHERE " + knownBadCapture
        );
    }

    private static void dedupeUnreasonableHistory(SQLiteDatabase db) {
        db.execSQL(
                "DELETE FROM " + TABLE_TRANSACTIONS
                        + " WHERE merchant GLOB '[0-9][0-9]:[0-9][0-9]'"
                        + " OR merchant LIKE '¥%'"
                        + " OR merchant LIKE '￥%'"
        );
        db.execSQL(
                "DELETE FROM " + TABLE_TRANSACTIONS
                        + " WHERE EXISTS ("
                        + "SELECT 1 FROM " + TABLE_TRANSACTIONS + " earlier"
                        + " WHERE earlier.id<" + TABLE_TRANSACTIONS + ".id"
                        + " AND earlier.source_key=" + TABLE_TRANSACTIONS + ".source_key"
                        + " AND earlier.direction=" + TABLE_TRANSACTIONS + ".direction"
                        + " AND earlier.amount_cents=" + TABLE_TRANSACTIONS + ".amount_cents"
                        + " AND earlier.merchant=" + TABLE_TRANSACTIONS + ".merchant"
                        + " AND " + TABLE_TRANSACTIONS + ".occurred_at>=earlier.occurred_at"
                        + " AND " + TABLE_TRANSACTIONS + ".occurred_at-earlier.occurred_at<=3600000"
                        + ")"
        );
    }

    private static void purgeWechatProductPages(SQLiteDatabase db) {
        String productPage =
                "("
                        + "raw_text LIKE '%特价团%'"
                        + " OR raw_text LIKE '%已售%'"
                        + " OR raw_text LIKE '%加倍补%'"
                        + " OR raw_text LIKE '%购买须知%'"
                        + " OR raw_text LIKE '%团购详情%'"
                        + " OR raw_text LIKE '%商品详情%'"
                        + " OR raw_text LIKE '%购买团购券%'"
                        + " OR raw_text LIKE '%团购券%'"
                        + " OR raw_text LIKE '%券后%'"
                        + ")";
        db.execSQL(
                "DELETE FROM " + TABLE_TRANSACTIONS
                        + " WHERE EXISTS (SELECT 1 FROM " + TABLE_RAW_CAPTURES
                        + " WHERE " + TABLE_RAW_CAPTURES + ".source_key=" + TABLE_TRANSACTIONS + ".source_key"
                        + " AND " + TABLE_RAW_CAPTURES + ".amount_cents=" + TABLE_TRANSACTIONS + ".amount_cents"
                        + " AND " + TABLE_RAW_CAPTURES + ".direction=" + TABLE_TRANSACTIONS + ".direction"
                        + " AND ABS(" + TABLE_RAW_CAPTURES + ".occurred_at-" + TABLE_TRANSACTIONS + ".occurred_at)<300000"
                        + " AND " + TABLE_RAW_CAPTURES + ".source_key='WECHAT'"
                        + " AND " + productPage
                        + ")"
        );
        db.execSQL(
                "UPDATE " + TABLE_RAW_CAPTURES
                        + " SET status='IGNORED' WHERE source_key='WECHAT' AND " + productPage
        );
    }

    private static void dedupeSameDayExactHistory(SQLiteDatabase db) {
        db.execSQL(
                "DELETE FROM " + TABLE_TRANSACTIONS
                        + " WHERE EXISTS ("
                        + "SELECT 1 FROM " + TABLE_TRANSACTIONS + " earlier"
                        + " WHERE earlier.id<" + TABLE_TRANSACTIONS + ".id"
                        + " AND earlier.source_key=" + TABLE_TRANSACTIONS + ".source_key"
                        + " AND earlier.direction=" + TABLE_TRANSACTIONS + ".direction"
                        + " AND earlier.amount_cents=" + TABLE_TRANSACTIONS + ".amount_cents"
                        + " AND earlier.merchant=" + TABLE_TRANSACTIONS + ".merchant"
                        + " AND date(earlier.occurred_at/1000,'unixepoch','localtime')="
                        + "date(" + TABLE_TRANSACTIONS + ".occurred_at/1000,'unixepoch','localtime')"
                        + ")"
        );
    }

    private static void purgeMessageListDuplicates(SQLiteDatabase db) {
        db.execSQL(
                "UPDATE " + TABLE_TRANSACTIONS
                        + " SET merchant='正新鸡排'"
                        + " WHERE EXISTS ("
                        + "SELECT 1 FROM " + TABLE_RAW_CAPTURES
                        + " WHERE " + TABLE_RAW_CAPTURES + ".source_key=" + TABLE_TRANSACTIONS + ".source_key"
                        + " AND " + TABLE_RAW_CAPTURES + ".amount_cents=" + TABLE_TRANSACTIONS + ".amount_cents"
                        + " AND " + TABLE_RAW_CAPTURES + ".direction=" + TABLE_TRANSACTIONS + ".direction"
                        + " AND ABS(" + TABLE_RAW_CAPTURES + ".occurred_at-" + TABLE_TRANSACTIONS + ".occurred_at)<300000"
                        + " AND " + TABLE_RAW_CAPTURES + ".raw_text LIKE '%正新鸡排%'"
                        + " AND " + TABLE_RAW_CAPTURES + ".raw_text LIKE '%付款成功%10.00%'"
                        + ")"
        );
        db.execSQL(
                "DELETE FROM " + TABLE_TRANSACTIONS
                        + " WHERE merchant IN ("
                        + "'全部会话','消息盒子','消息','首页','我的','更多','全部',"
                        + "'收付款','扫一扫','卡包','出行','通讯录'"
                        + ") OR merchant GLOB '消息([0-9]*'"
        );
        db.execSQL(
                "DELETE FROM " + TABLE_TRANSACTIONS
                        + " WHERE merchant='' AND EXISTS ("
                        + "SELECT 1 FROM " + TABLE_TRANSACTIONS + " better"
                        + " WHERE better.source_key=" + TABLE_TRANSACTIONS + ".source_key"
                        + " AND better.direction=" + TABLE_TRANSACTIONS + ".direction"
                        + " AND better.amount_cents=" + TABLE_TRANSACTIONS + ".amount_cents"
                        + " AND better.merchant<>''"
                        + " AND ABS(better.occurred_at-" + TABLE_TRANSACTIONS + ".occurred_at)<1800000"
                        + ")"
        );
        dedupeSameDayExactHistory(db);
    }

    private static void purgeWechatBrowsingHistory(SQLiteDatabase db) {
        String historyPage =
                "("
                        + "raw_text LIKE '%交易详情%'"
                        + " OR raw_text LIKE '%账单详情%'"
                        + " OR raw_text LIKE '%交易单号%'"
                        + " OR raw_text LIKE '%商户单号%'"
                        + " OR raw_text LIKE '%支付时间%'"
                        + " OR raw_text LIKE '%当前状态%'"
                        + " OR raw_text LIKE '%收单机构%'"
                        + " OR raw_text LIKE '%申请电子凭证%'"
                        + " OR raw_text LIKE '%使用零钱支付%'"
                        + " OR raw_text LIKE '%使用零钱通支付%'"
                        + ")";
        db.execSQL(
                "DELETE FROM " + TABLE_TRANSACTIONS
                        + " WHERE EXISTS (SELECT 1 FROM " + TABLE_RAW_CAPTURES
                        + " WHERE " + TABLE_RAW_CAPTURES + ".source_key=" + TABLE_TRANSACTIONS + ".source_key"
                        + " AND " + TABLE_RAW_CAPTURES + ".amount_cents=" + TABLE_TRANSACTIONS + ".amount_cents"
                        + " AND " + TABLE_RAW_CAPTURES + ".direction=" + TABLE_TRANSACTIONS + ".direction"
                        + " AND ABS(" + TABLE_RAW_CAPTURES + ".occurred_at-" + TABLE_TRANSACTIONS + ".occurred_at)<300000"
                        + " AND " + TABLE_RAW_CAPTURES + ".source_key='WECHAT'"
                        + " AND " + historyPage
                        + ")"
        );
        db.execSQL(
                "UPDATE " + TABLE_RAW_CAPTURES
                        + " SET status='IGNORED'"
                        + " WHERE source_key='WECHAT' AND " + historyPage
        );
        db.execSQL(
                "UPDATE " + TABLE_TRANSACTIONS
                        + " SET merchant=''"
                        + " WHERE merchant LIKE '%尾号%银行卡%'"
                        + " OR merchant GLOB '[0-9]*月[0-9]*日'"
        );
        dedupeSameDayExactHistory(db);
    }

    private static void purgeAlipayEvidenceDuplicates(SQLiteDatabase db) {
        db.execSQL(
                "UPDATE " + TABLE_RAW_CAPTURES
                        + " SET status='IGNORED'"
                        + " WHERE source_key='ALIPAY'"
                        + " AND raw_text LIKE '%付款成功%'"
                        + " AND (raw_text LIKE '%1小时前%'"
                        + " OR raw_text LIKE '%分钟前%'"
                        + " OR raw_text LIKE '%小时前%')"
        );
        db.execSQL(
                "DELETE FROM " + TABLE_TRANSACTIONS
                        + " WHERE merchant='' AND EXISTS ("
                        + "SELECT 1 FROM " + TABLE_TRANSACTIONS + " better"
                        + " WHERE better.source_key=" + TABLE_TRANSACTIONS + ".source_key"
                        + " AND better.direction=" + TABLE_TRANSACTIONS + ".direction"
                        + " AND better.amount_cents=" + TABLE_TRANSACTIONS + ".amount_cents"
                        + " AND better.merchant<>''"
                        + " AND ABS(better.occurred_at-" + TABLE_TRANSACTIONS + ".occurred_at)<300000"
                        + ")"
        );
        dedupeSameDayExactHistory(db);
    }
}
