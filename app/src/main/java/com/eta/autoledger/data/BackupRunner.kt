package com.eta.autoledger.data

import android.content.Context
import com.eta.autoledger.AppPrefs
import com.eta.autoledger.util.AppDateFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份/恢复的统一入口：手动按钮、定时任务、命令行调试都走这里。
 *
 * 策略：
 * - 固定名 `autoledger-backup.json` 每次覆盖，恢复时永远读最新一份；
 * - 另按天写 `autoledger-backup-yyyyMMdd.json`，云端保留最近 [KEEP_DAILY] 份，
 *   防止某次备份时数据库正好是坏的、把唯一一份好备份覆盖掉。
 */
object BackupRunner {

    const val FILE_LATEST = "autoledger-backup.json"
    private const val KEEP_DAILY = 7
    private const val AUTO_INTERVAL_MS = 20L * 60 * 60 * 1000

    data class Result(val ok: Boolean, val message: String)

    /** 手动/定时备份。调用方需在 IO 线程之外，内部已切 Dispatchers.IO。 */
    suspend fun backup(context: Context, prefs: AppPrefs): Result = withContext(Dispatchers.IO) {
        val target = WebDavClient.target(prefs) ?: return@withContext Result(false, "请先填写 WebDAV 地址、账号与应用密码")
        runCatching {
            val entries = LedgerRepository.load(context, includeTemplates = true)
            if (entries.isEmpty()) return@withContext Result(false, "本地没有账目，未上传")
            val bytes = BackupCodec.encode(entries)
            val day = AppDateFormats.format(System.currentTimeMillis(), AppDateFormats.basicDate)
            WebDavClient.ensureDir(target)
            WebDavClient.upload(target, FILE_LATEST, bytes)
            WebDavClient.upload(target, "autoledger-backup-$day.json", bytes)
            prune(target)
            val size = "%.1f".format(bytes.size / 1024.0)
            prefs.davLastAt = System.currentTimeMillis()
            prefs.davLastResult = "成功 · ${entries.size} 笔 · $size KB"
            Result(true, "已备份 ${entries.size} 笔（$size KB）")
        }.getOrElse {
            val msg = it.message ?: it.javaClass.simpleName
            prefs.davLastResult = "失败 · $msg"
            Result(false, msg)
        }
    }

    /** 恢复的两段式：先下载并算出「哪些是新的、哪些疑似重复」，再让用户决定怎么写库。 */
    sealed class Stage {
        data class Ready(val plan: ImportDedup.Plan, val incoming: Int) : Stage()
        data class Failed(val message: String) : Stage()
    }

    suspend fun prepareRestore(context: Context, prefs: AppPrefs): Stage = withContext(Dispatchers.IO) {
        val target = WebDavClient.target(prefs) ?: return@withContext Stage.Failed("请先填写 WebDAV 地址、账号与应用密码")
        runCatching {
            val raw = WebDavClient.download(target, FILE_LATEST)
                ?: return@withContext Stage.Failed("云端没有找到 " + FILE_LATEST)
            val entries = BackupCodec.decode(raw)
            if (entries.isEmpty()) return@withContext Stage.Failed("备份文件里没有可用账目")
            val plan = ImportDedup.plan(context, entries, aiCheck = true)
            prefs.davLastResult = "恢复预览 · " + plan.total + " 笔"
            Stage.Ready(plan = plan, incoming = entries.size)
        }.getOrElse {
            val msg = it.message ?: it.javaClass.simpleName
            prefs.davLastResult = "恢复失败 · " + msg
            Stage.Failed(msg)
        }
    }

    suspend fun commitRestore(
        context: Context,
        prefs: AppPrefs,
        plan: ImportDedup.Plan,
        decisions: List<ImportDedup.Action>,
    ): Result = withContext(Dispatchers.IO) {
        runCatching {
            val r = ImportDedup.commit(context, plan, decisions)
            prefs.davLastResult = "恢复 · 新增 " + r.added + " 笔"
            val extra = buildString {
                if (r.merged > 0) append("，合并 ").append(r.merged).append(" 笔")
                if (r.skipped > 0) append("，跳过 ").append(r.skipped).append(" 笔")
            }
            Result(true, "恢复完成：新增 " + r.added + " 笔" + extra)
        }.getOrElse {
            val msg = it.message ?: it.javaClass.simpleName
            prefs.davLastResult = "恢复失败 · " + msg
            Result(false, msg)
        }
    }

    /** 不需要交互的恢复（定时/命令行）：全部按「合并」处理，行为与旧版一致。 */
    suspend fun restore(context: Context, prefs: AppPrefs): Result {
        val stage = prepareRestore(context, prefs)
        return when (stage) {
            is Stage.Failed -> Result(false, stage.message)
            is Stage.Ready -> commitRestore(
                context,
                prefs,
                stage.plan,
                ImportDedup.decisions(stage.plan, ImportDedup.Action.MERGE),
            )
        }
    }

    /** 定时任务每次触发都调它：没开自动备份、或离上次不足 20 小时就跳过。 */
    suspend fun autoBackupIfDue(context: Context, prefs: AppPrefs): Result? {
        if (!prefs.davAuto || !prefs.davConfigured()) return null
        val now = System.currentTimeMillis()
        if (now - prefs.davLastAt < AUTO_INTERVAL_MS) return null
        return backup(context, prefs)
    }

    private fun prune(target: WebDavClient.Target) {
        runCatching {
            val daily = WebDavClient.list(target)
                .filter { it.startsWith("autoledger-backup-") && it.endsWith(".json") }
                .sortedDescending()
            daily.drop(KEEP_DAILY).forEach { WebDavClient.delete(target, it) }
        }
    }
}
