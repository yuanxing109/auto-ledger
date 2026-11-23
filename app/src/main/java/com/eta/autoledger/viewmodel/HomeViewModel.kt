package com.eta.autoledger.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.Summary
import com.eta.autoledger.ui.model.DayGroupUi
import com.eta.autoledger.ui.model.groupEntries
import com.eta.autoledger.ui.model.mergeIntoTail
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 账目内容版本号：只随「内容真的变了」递增，与翻页无关。
 *
 * [count] 覆盖新增/删除（Room 的 COUNT 流会自己推），[editTick] 覆盖「改金额但条数不变」的编辑。
 * 预算卡这类派生数据拿它当 key，就不会因为翻页而重算。
 */
data class LedgerDataVersion(val count: Int = 0, val editTick: Int = 0)

data class BillsUiState(
    val bills: List<LedgerEntry> = emptyList(),
    val groups: List<DayGroupUi> = emptyList(),
    val isLoading: Boolean = true,
    val hasMore: Boolean = false,
    val totalCount: Int = 0,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    /** 每页固定 20 条。旧实现是 LIMIT = pageSize * pages + OFFSET 0，第 10 页要扫 500 行只显示最后 20 行。 */
    private val pageSize = 20

    /** 首屏（第 0 页）走 Flow，数据变化时自动刷新。 */
    private val firstPage = LedgerRepository.observePaging(context, pageSize, 0)
    /** 之后的页按游标一次拿一页，追加在这里。 */
    private val extraPages = MutableStateFlow<List<LedgerEntry>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val exhausted = MutableStateFlow(false)
    private val loadMoreRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private var loadingMore = false

    /**
     * 已分组结果。翻页时只把新页并进尾部（[mergeIntoTail]），不重算已加载部分。
     *
     * 原实现放在 UI：`remember(entries) { groupEntries(entries) }`。
     * `loadMore` 产生新 List 使 key 失效 → 每次翻页把已加载的 N 条全部重新分组，
     * 翻到第 k 页单次开销为 N，累计 ΣN ≈ O(n²)。迁到这里后每次翻页只处理新页（O(pageSize)）。
     */
    private val groups = MutableStateFlow<List<DayGroupUi>>(emptyList())

    /** 内容编辑计数：改金额/分类但条数不变时，只有它能反映「数据变了」。 */
    private val editTick = MutableStateFlow(0)

    /**
     * 给「依赖账目内容的派生计算」用的版本号。
     *
     * 之前首页预算卡用 `LaunchedEffect(budgetStartDay, entries.size, billsState.totalCount)`：
     * `entries.size` 会随翻页增长，于是每翻一页就白跑一次预算汇总 SQL —— 而翻页并没有改任何账目。
     * 换成这个版本号后，只有真正新增/删除（count 变）或编辑（editTick 变）才会重算。
     */
    val dataVersion: StateFlow<LedgerDataVersion> = combine(
        LedgerRepository.observeCount(context),
        editTick,
    ) { count, tick -> LedgerDataVersion(count = count, editTick = tick) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            LedgerDataVersion(),
        )

    private val billsFlow = combine(firstPage, extraPages) { first, extra ->
        // 首屏刷新（新账插入、旧账删除）可能与已加载的追加页重叠，按 id 去重。
        val seen = HashSet<Long>(first.size + extra.size)
        (first + extra).filter { seen.add(it.id) }
    }

    val billsState: StateFlow<BillsUiState> = combine(
        billsFlow,
        LedgerRepository.observeCount(context),
        loading,
        exhausted,
        groups,
    ) { bills, count, isLoading, reachedEnd, grouped ->
        BillsUiState(
            bills = bills,
            groups = grouped,
            isLoading = isLoading,
            hasMore = !reachedEnd && bills.size < count,
            totalCount = count,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        BillsUiState(),
    )

    val summaryState: StateFlow<Summary> = LedgerRepository.observeSummary(context).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        Summary(),
    )

    init {
        viewModelScope.launch(Dispatchers.IO) { LedgerRepository.repairTimestamps(context) }
        // 首屏：全量分组一次。之后翻页只走增量合并，不再进这里。
        // 分组本身是 CPU 活（每条账目要算日期键、金额文本、分类配色），首屏 20 条虽小，
        // 但账单多的用户首次启动仍是几百条，放到 Default 上做，别占主线程。
        viewModelScope.launch {
            firstPage.collect { first ->
                groups.value = withContext(Dispatchers.Default) { groupEntries(first) }
                loading.value = false
            }
        }
        // 账单总量变了：新账插在顶部会让游标页位移，直接丢掉追加页，从首屏重新开始翻。
        viewModelScope.launch {
            LedgerRepository.observeCount(context)
                .drop(1)
                .distinctUntilChanged()
                .collect {
                    if (extraPages.value.isNotEmpty()) extraPages.value = emptyList()
                    exhausted.value = false
                }
        }
        // 触底加载防抖：300ms 内只处理最后一次请求
        viewModelScope.launch {
            loadMoreRequests.debounce(300).collect {
                if (!loadingMore && billsState.value.hasMore) {
                    loadingMore = true
                    try {
                        loadNextPage()
                    } finally {
                        loadingMore = false
                    }
                }
            }
        }
    }

    private suspend fun loadNextPage() {
        val last = billsState.value.bills.lastOrNull() ?: return
        val cursorTime = if (last.timeMillis > 0) {
            last.timeMillis
        } else {
            LedgerRepository.parseTime(last.time)?.timeInMillis ?: 0L
        }
        val more = LedgerRepository.pageBefore(context, cursorTime, last.id, pageSize)
        if (more.isEmpty()) {
            exhausted.value = true
            return
        }
        extraPages.value = (extraPages.value + more).distinctBy { it.id }
        // 增量合并：只对新页做转换并接到末组，已加载部分不重算。
        // 原实现放在 UI 的 remember(entries) 里，每次翻页都全量重分组 → 累计 O(n²)。
        // 合并本身仍是 CPU 活（新页每条都要算日期键/金额文本/配色），挪到 Default 上，
        // 主线程只负责最后一次性赋值。LedgerGrouping 的格式化器已换成线程安全的
        // java.time.DateTimeFormatter，正是为了这里能安全地跨线程调用。
        val merged = withContext(Dispatchers.Default) { mergeIntoTail(groups.value, more) }
        groups.value = merged
        if (more.size < pageSize) exhausted.value = true
    }

    fun loadMore() {
        loadMoreRequests.tryEmit(Unit)
    }

    fun updateEntry(entry: LedgerEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            LedgerRepository.update(context, entry)
            // 第 20 条之后的账目在 extraPages 缓存里，不随首屏 Flow 刷新，这里就地替换，
            // 否则编辑后列表仍显示旧值（更新不改 count，不会触发 extraPages 清空）。
            extraPages.update { list ->
                if (list.any { it.id == entry.id }) {
                    list.map { if (it.id == entry.id) entry else it }
                } else {
                    list
                }
            }
            // 编辑不改条数，count 流不会动，靠这个 tick 通知预算卡等派生数据重算。
            editTick.update { it + 1 }
        }
    }

    /**
     * 删除账目，返回的 Deferred 在删除**真正落库后**才完成。
     *
     * 调用方（Snackbar 撤销流程）必须先 await 再弹提示：
     * 否则用户可能在 delete 尚未完成时点撤销，restoreEntry 的 insert 会被
     * `OnConflictStrategy.IGNORE` 静默丢弃（记录还在，id 冲突），
     * 随后 delete 完成又把这条删掉——用户点了撤销却什么都没恢复。
     */
    fun deleteEntry(id: Long): Deferred<Unit> = viewModelScope.async(Dispatchers.IO) {
        LedgerRepository.delete(context, id)
        extraPages.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * 撤销删除：把被删的账目按原 id 写回。
     *
     * 与 [deleteEntry] 配对使用——Snackbar 的「撤销」动作在删除后触发，
     * 需要原样恢复（含 id、时间、来源），所以走 `add` 的显式 id 分支而非重新生成。
     */
    fun restoreEntry(entry: LedgerEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            LedgerRepository.restore(context, entry)
        }
    }

    fun clearAll() {
        extraPages.value = emptyList()
        exhausted.value = false
        viewModelScope.launch(Dispatchers.IO) { LedgerRepository.clear(context) }
    }
}