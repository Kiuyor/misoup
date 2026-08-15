package com.misoup.app.ui.library

import androidx.lifecycle.ViewModel
import com.misoup.app.data.index.IndexedEntry
import com.misoup.app.data.index.LibraryIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 错题库列表 ViewModel（T01-T04）
 *
 * 数据流：LibraryIndex.query(filter, keyword) → 分组（LibraryGrouping）→ StateFlow。
 * 搜索输入即时进 [UiState.keywordInput]，防抖 300ms 后才生效为 [UiState.keyword]。
 */
class LibraryViewModel(
    private val index: LibraryIndex,
    private val ocrRetry: OcrRetryHandler?,
    private val debouncer: DebounceScheduler
) : ViewModel() {

    /** 列表 ⇄ 瀑布流 */
    enum class ViewMode { LIST, STAGGERED }

    data class UiState(
        val groups: List<SubjectGroup> = emptyList(),
        val filterState: FilterState = FilterState(),
        /** 输入框即时值（未防抖） */
        val keywordInput: String = "",
        /** 已生效的搜索词（防抖后） */
        val keyword: String = "",
        val searching: Boolean = false,
        val viewMode: ViewMode = ViewMode.LIST,
        val total: Int = 0,
        /** 筛选栏可选科目（当前库内出现） */
        val availableSubjects: List<String> = emptyList(),
        /** 筛选栏可选标签（按出现频次降序） */
        val availableTags: List<String> = emptyList(),
        val message: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 重新查询索引并重组分组（返回列表页时由 UI 调用，保证编辑后即时刷新） */
    fun refresh() {
        val s = _uiState.value
        val results = index.query(s.filterState.toFilter(), s.keyword.takeIf { it.isNotBlank() })
        val all = index.all()
        _uiState.update {
            it.copy(
                groups = LibraryGrouping.groupBySubject(results),
                total = results.size,
                availableSubjects = LibraryGrouping.subjectsOf(all),
                availableTags = all.flatMap { e -> e.tags }
                    .groupingBy { tag -> tag }
                    .eachCount()
                    .toList()
                    .sortedByDescending { (_, count) -> count }
                    .map { (tag, _) -> tag }
            )
        }
    }

    // ---------- 搜索（T03） ----------

    fun onSearchInput(value: String) {
        _uiState.update { it.copy(keywordInput = value, searching = true) }
        debouncer.submit(value) { settled ->
            _uiState.update { it.copy(keyword = settled, searching = false) }
            refresh()
        }
    }

    fun clearSearch() {
        debouncer.cancel()
        _uiState.update { it.copy(keywordInput = "", keyword = "", searching = false) }
        refresh()
    }

    // ---------- 筛选（T02） ----------

    fun toggleFilter(dim: FilterDim, value: String) {
        _uiState.update { it.copy(filterState = it.filterState.toggle(dim, value)) }
        refresh()
    }

    fun cycleRepairFilter() {
        _uiState.update { it.copy(filterState = it.filterState.cycleRepairFilter()) }
        refresh()
    }

    fun clearFilters() {
        _uiState.update { it.copy(filterState = it.filterState.clear()) }
        refresh()
    }

    // ---------- 视图切换（T04） ----------

    fun toggleViewMode() {
        _uiState.update {
            it.copy(viewMode = if (it.viewMode == ViewMode.LIST) ViewMode.STAGGERED else ViewMode.LIST)
        }
    }

    // ---------- 识别重试（T10，列表徽标点击） ----------

    fun retryOcr(id: String) {
        ocrRetry?.retry(id)
        _uiState.update { it.copy(message = "已重新提交识别") }
    }

    /** 当前筛选/搜索命中的条目（删除等联动用） */
    fun currentEntries(): List<IndexedEntry> = _uiState.value.groups.flatMap { it.entries }

    override fun onCleared() {
        debouncer.cancel()
        super.onCleared()
    }
}
