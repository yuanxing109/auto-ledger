package com.eta.autoledger.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eta.autoledger.data.LedgerEntry
import com.eta.autoledger.data.LedgerRepository
import com.eta.autoledger.data.Summary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BillsUiState(
    val bills: List<LedgerEntry> = emptyList(),
    val isLoading: Boolean = true,
    val hasMore: Boolean = false,
    val totalCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val pageSize = 50
    private val page = MutableStateFlow(1)
    private val loading = MutableStateFlow(true)
    private val loadMoreRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var loadingMore = false

    private val billsFlow = page.flatMapLatest { pages ->
        LedgerRepository.observePaging(context, pageSize * pages, 0)
    }
    private val summaryFlow = LedgerRepository.observeSummary(context)
    private val countFlow = LedgerRepository.observeCount(context)

    val billsState: StateFlow<BillsUiState> = combine(
        billsFlow,
        countFlow,
        loading,
    ) { bills, count, isLoading ->
        BillsUiState(
            bills = bills,
            isLoading = isLoading,
            hasMore = bills.size < count,
            totalCount = count,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        BillsUiState(),
    )

    val summaryState: StateFlow<Summary> = summaryFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        Summary(),
    )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            LedgerRepository.repairTimestamps(context)
        }
        viewModelScope.launch {
            billsFlow.first()
            loading.value = false
        }
        // 触底加载防抖：300ms 内只处理最后一次请求
        viewModelScope.launch {
            loadMoreRequests
                .debounce(300)
                .collect {
                    if (!loadingMore && billsState.value.hasMore) {
                        loadingMore = true
                        page.value += 1
                        kotlinx.coroutines.delay(300)
                        loadingMore = false
                    }
                }
        }
    }

    fun loadMore() {
        loadMoreRequests.tryEmit(Unit)
    }

    fun updateEntry(entry: LedgerEntry) {
        viewModelScope.launch(Dispatchers.IO) { LedgerRepository.update(context, entry) }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch(Dispatchers.IO) { LedgerRepository.delete(context, id) }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) { LedgerRepository.clear(context) }
    }
}