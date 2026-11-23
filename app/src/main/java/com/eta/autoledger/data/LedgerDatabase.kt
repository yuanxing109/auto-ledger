package com.eta.autoledger.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LedgerEntry::class, SeenKeyEntity::class, RuleEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao

    abstract fun seenKeyDao(): SeenKeyDao

    abstract fun ruleDao(): RuleDao

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
                    )
                        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                        .addCallback(SeedCallback)
                        .build()
                    if (isNew) migrateLegacy(context, db)
                    db.also { instance = it }
                }
            }
        }

        /**
         * 清理过期的去重键。供 `HealthJobService` 的心跳节拍调用。
         *
         * 为什么是 10 分钟（[LedgerSchema.SEEN_KEY_TTL_MS]）：
         * 去重要挡住的是「同一条短信/通知在短时间内的重复投递」，观察到的重投延迟在
         * 秒到分钟级，10 分钟已有 10 倍余量。窗口再放大会把「10 分钟后用户真的又刷了
         * 一笔同额同商户」也当成重复 —— 那是真实消费，必须能记上。
         *
         * 返回清理行数，方便心跳日志里观察写入侧是否真的在工作。
         */
        suspend fun pruneSeenKeys(context: Context, now: Long = System.currentTimeMillis()): Int =
            get(context).seenKeyDao().deleteExpired(now - LedgerSchema.SEEN_KEY_TTL_MS)

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Bill ADD COLUMN external_id TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_Bill_external_id ON Bill(external_id)")
            }
        }

        /**
         * v2 → v3：周期记账。
         *
         * 两列都用 NOT NULL + DEFAULT 追加，老数据不需要回填：
         * 既有账单 is_recurring 全为 0、recurring_rule 全为 NULL，语义上就是「非周期」。
         * 不加索引——模板数量是个位数，全表扫 is_recurring 的代价可以忽略，
         * 而多一个索引会让每次插入多一次写放大。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Bill ADD COLUMN is_recurring INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Bill ADD COLUMN recurring_rule TEXT")
            }
        }

        /**
         * v3 → v4：去重表 + 规则表。
         *
         * 只加表，**不动 Bill**。这是刻意的：迁移里最危险的操作是改动既有表
         * （SQLite 的 ALTER TABLE 能力有限，改列类型要重建表 + 搬数据，一旦中途失败
         * 用户的历史账单就没了）。新建表是纯增量操作，失败重跑也只是空操作。
         *
         * DDL 全部来自 [LedgerSchema.V4_CREATE_STATEMENTS]，同一份语句也用于单测，
         * 保证「测过的」和「线上跑的」是同一份 SQL。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LedgerSchema.V4_CREATE_STATEMENTS.forEach { db.execSQL(it) }
            }
        }

        /**
         * 内置规则播种。
         *
         * 为什么放在 Room 回调而不是 `get()` 里直接调：
         * `onOpen` 由 Room 在自己的后台线程上调用，且发生在任何查询返回之前，
         * 所以这里 `runBlocking` 不会阻塞主线程、也不会出现「UI 已经读到空规则表」的窗口。
         * 放 `get()` 里则要看调用方在哪个线程，`LedgerRepository.dao()` 是被任意调用方用的，
         * 迟早有人在主线程调一次。
         *
         * 用裸 SQL 而不是 `ruleDao()`：回调时 Room 实例还没装配完，DAO 拿不到。
         * 规则数据仍由 [RuleSeed] 统一提供，这里只负责把它写进库，两边不会分叉。
         */
        private val SeedCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                runCatching { seedSystemRules(db) }
                // 播种可能刚插入了新规则，让引擎丢掉旧缓存 —— 否则首启后第一批通知
                // 仍会走内置兜底，用户会以为内置规则没生效。
                RuleEngine.invalidate()
            }
        }

        /**
         * `INSERT OR IGNORE` 写入内置规则。
         *
         * OR IGNORE 是关键：已存在（主键冲突）就跳过，**不覆盖**用户的 enabled 开关。
         * 用 OR REPLACE 会让「我明明关掉了这条内置规则，更新后又自己打开了」这种
         * 无从排查的问题反复出现。
         */
        private fun seedSystemRules(db: SupportSQLiteDatabase) {
            val sql =
                "INSERT OR IGNORE INTO `rule` " +
                    "(`id`,`app`,`type`,`name`,`creator`,`pattern_json`,`enabled`,`auto_record`,`updated_at`) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)"
            RuleSeed.systemRules().forEach { rule ->
                db.execSQL(
                    sql,
                    arrayOf<Any?>(
                        rule.id,
                        rule.app,
                        rule.type,
                        rule.name,
                        rule.creator,
                        rule.patternJson,
                        if (rule.enabled) 1 else 0,
                        if (rule.autoRecord) 1 else 0,
                        rule.updatedAt,
                    ),
                )
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
                // 迁移回调 RoomDatabase.Callback.onCreate 不是 suspend，只能同步落库；
                // 它本身跑在 Room 的初始化线程（非主线程），这里的 runBlocking 不会造成 ANR，是刻意保留的。
                runBlocking { database.ledgerDao().insertAll(list) }
            }
        }
    }
}
