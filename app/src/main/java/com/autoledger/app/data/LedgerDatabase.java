package com.autoledger.app.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class LedgerDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "autoledger.db";
    private static final int DB_VERSION = 2;
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
        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_transactions_source_ref ON " + TABLE_TRANSACTIONS
                        + "(source_key, source_ref) WHERE source_ref <> ''"
        );
    }
}
