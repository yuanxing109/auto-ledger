package com.eta.autoledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LedgerEntry::class], version = 2, exportSchema = false)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    companion object {
        @Volatile
        private var instance: LedgerDatabase? = null

        fun get(context: Context): LedgerDatabase {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val newFile = context.getDatabasePath("auto_ledger.db")
                    val isNew = !newFile.exists()
                    val db = Room.databaseBuilder(
                        context.applicationContext,
                        LedgerDatabase::class.java,
                        "auto_ledger.db",
                    ).addMigrations(MIGRATION_1_2).build()
                    if (isNew) migrateLegacy(context, db)
                    db.also { instance = it }
                }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Bill ADD COLUMN external_id TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_Bill_external_id ON Bill(external_id)")
            }
        }

        private fun migrateLegacy(context: Context, database: LedgerDatabase) {
            val old = context.getDatabasePath("ledger.db")
            if (!old.exists()) return
            val list = runCatching {
                SQLiteDatabase.openDatabase(old.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    buildList {
                        db.rawQuery("SELECT id,amount,type,category,time_text,time_millis,note,source FROM entries", null).use { c ->
                            while (c.moveToNext()) {
                                add(
                                    LedgerEntry(
                                        id = c.getLong(0),
                                        amount = c.getDouble(1),
                                        type = c.getString(2),
                                        category = c.getString(3),
                                        time = c.getString(4),
                                        timeMillis = c.getLong(5),
                                        note = c.getString(6),
                                        source = c.getString(7),
                                    ),
                                )
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
            if (list.isNotEmpty()) {
                runBlocking { database.ledgerDao().insertAll(list) }
            }
        }
    }
}