package com.eta.autoledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray

class LedgerDbHelper(private val appContext: Context) : SQLiteOpenHelper(appContext, DB_NAME, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entries (
                id INTEGER PRIMARY KEY,
                amount REAL NOT NULL,
                type TEXT NOT NULL,
                category TEXT NOT NULL,
                time_text TEXT NOT NULL,
                time_millis INTEGER NOT NULL,
                note TEXT NOT NULL,
                source TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_entries_time ON entries(time_millis DESC)")
        db.execSQL("CREATE INDEX idx_entries_type_time ON entries(type, time_millis DESC)")
        migrateFromPrefs(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 当前只有 v1；后续在这里做增量迁移。
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        val count = db.rawQuery("SELECT COUNT(*) FROM entries", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }
        if (count == 0L) migrateFromPrefs(db)
    }

    private fun migrateFromPrefs(db: SQLiteDatabase) {
        val prefs = appContext.getSharedPreferences("auto_ledger", Context.MODE_PRIVATE)
        val raw = prefs.getString("entries", "[]") ?: "[]"
        if (raw == "[]" || raw.isBlank()) return
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return
        db.beginTransaction()
        try {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val timeText = obj.optString("time", "")
                val millis = LedgerRepository.parseTime(timeText)?.timeInMillis ?: System.currentTimeMillis()
                db.execSQL(
                    "INSERT OR REPLACE INTO entries(id,amount,type,category,time_text,time_millis,note,source) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf(
                        obj.optLong("id", i.toLong()),
                        obj.optDouble("amount", 0.0),
                        obj.optString("type", "expense"),
                        obj.optString("category", "其他"),
                        timeText,
                        millis,
                        obj.optString("note", ""),
                        obj.optString("source", "自动记账"),
                    ),
                )
            }
            db.setTransactionSuccessful()
            prefs.edit().remove("entries").apply()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        const val DB_NAME = "ledger.db"
    }
}