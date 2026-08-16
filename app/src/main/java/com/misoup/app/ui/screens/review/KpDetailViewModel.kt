package com.misoup.app.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.ai.mastery.WeakKpAggregator
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.StoreService
import com.misoup.app.review.stats.Mastery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 知识点详情 ViewModel（模块 07 T06）：
 * 薄弱知识点 → 关联错题列表（点击直达详情页）。
 * 复用模块 03 [Mastery] 聚合；无该知识点 → 空列表（不崩）。
 */
class KpDetailViewModel(
    private val store: StoreService,
    private val index: LibraryIndex,
) : ViewModel() {

    /** 关联错题行（点击直达详情） */
    data class RelatedEntry(
        val id: String,
        val dirName: String,
        val subject: String,
        val summary: String,
        val reviewCount: Int,
    )

    data class UiState(
        val kp: String = "",
        val mastery: Mastery.KpMastery? = null,
        val entries: List<RelatedEntry> = emptyList(),
        val loading: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun load(kp: String) {
        _state.update { UiState(kp = kp, loading = true) }
        viewModelScope.launch {
            val metas = runCatching {
                index.all()
                    .filter { !it.needsRepair }
                    .mapNotNull { store.readMetaById(it.id).getOrNull() }
            }.getOrDefault(emptyList())
            val related = WeakKpAggregator.relatedEntries(metas, kp)
            val entries = related.map { meta ->
                val entry = index.get(meta.id)
                RelatedEntry(
                    id = meta.id,
                    dirName = entry?.dirName ?: meta.id,
                    subject = meta.subject,
                    summary = entry?.summary.orEmpty(),
                    reviewCount = meta.reviews.size,
                )
            }
            _state.update {
                it.copy(
                    loading = false,
                    // 无关联错题 → 不展示掌握度（避免「未收录知识点」显示中性 0.5 误导）
                    mastery = if (related.isEmpty()) null else Mastery.of(metas, kp),
                    entries = entries,
                )
            }
        }
    }
}
