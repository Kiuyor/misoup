package com.misoup.app.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.store.StoreService
import com.misoup.app.review.queue.QueueLoader
import com.misoup.app.review.queue.ReviewQueue
import com.misoup.app.review.stats.Stats
import com.misoup.app.review.stats.StatsCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 复习 tab 总览 ViewModel（T07/T09）：
 * 今日待复习横幅 + 学情统计卡片 + 队列列表。
 */
class ReviewViewModel(
    private val queueLoader: QueueLoader,
    private val store: StoreService,
    private val index: LibraryIndex
) : ViewModel() {

    data class UiState(
        val todayCount: Int = 0,                 // 今日待复习 N 题（横幅/角标）
        val queue: List<ReviewQueue.QueueItem> = emptyList(),
        val stats: Stats? = null,
        val loading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /** 刷新（进页面/复习完返回时调用；now 可注入便于测试） */
    fun refresh(now: Long = System.currentTimeMillis()) {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val metas = loadMetas()
            if (metas == null) {
                _uiState.update { it.copy(loading = false, error = "数据加载失败") }
                return@launch
            }
            val queue = ReviewQueue.build(ReviewQueue.fromMetas(metas), now)
            val stats = StatsCalculator.calculate(metas, now)
            _uiState.update {
                it.copy(
                    loading = false,
                    todayCount = queue.size,
                    queue = queue,
                    stats = stats
                )
            }
        }
    }

    /** 一次性读取全部正常条目 meta（统计 + 队列共用一份数据） */
    private suspend fun loadMetas(): List<MisoupMeta>? =
        runCatching {
            index.all()
                .filter { !it.needsRepair }
                .mapNotNull { store.readMetaById(it.id).getOrNull() }
        }.getOrNull()
}
