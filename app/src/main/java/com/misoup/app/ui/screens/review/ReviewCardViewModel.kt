package com.misoup.app.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.data.store.StoreService
import com.misoup.app.review.fsrs.Fsrs
import com.misoup.app.review.mask.MaskRect
import com.misoup.app.review.mask.MaskSelectionState
import com.misoup.app.review.mask.MaskStore
import com.misoup.app.review.queue.QueueLoader
import com.misoup.app.review.queue.ReviewQueue
import com.misoup.app.review.scheduler.ReviewSubmitter
import com.misoup.app.ui.library.LibraryTextFormat
import com.misoup.app.ui.library.QuestionText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 重做卡片页 ViewModel（T04/T05/T06 UI 侧）：
 * 队列逐张 → 题干+原图 → 遮罩（默认整图/自定义框选）→ 显示答案动画 → 4 档自评提交。
 *
 * 状态机（纯逻辑可单测）：
 * - 遮罩：[MaskSelectionState]（长按进入框选，拖动更新，松手定稿持久化 .mask.json）
 * - 揭开：[revealed] + [revealProgress]（动画进度 0..1，钳制）
 * - 提交：submitGrade → ReviewSubmitter → 成功推进下一张；失败保留当前卡并报错
 */
class ReviewCardViewModel(
    private val queueLoader: QueueLoader,
    private val submitter: ReviewSubmitter,
    private val store: StoreService,
    private val maskStore: MaskStore,
    private val nowProvider: () -> Long = System::currentTimeMillis
) : ViewModel() {

    data class UiState(
        val queue: List<ReviewQueue.QueueItem> = emptyList(),
        val currentIndex: Int = 0,
        val text: QuestionText = QuestionText(),
        val imagePath: String? = null,
        val mask: MaskSelectionState = MaskSelectionState(),
        val revealed: Boolean = false,          // 是否已点"显示答案"
        val revealProgress: Float = 0f,         // 揭开动画进度 0..1
        val submitting: Boolean = false,
        val done: Boolean = false,              // 队列复习完毕
        val error: String? = null
    ) {
        val current: ReviewQueue.QueueItem? get() = queue.getOrNull(currentIndex)
        val queueProgress: String get() = "${currentIndex + 1}/${queue.size}"
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 加载今日队列并定位到第一张 */
    fun load() {
        viewModelScope.launch {
            val queue = queueLoader.loadToday(nowProvider()).getOrElse {
                _uiState.update { s -> s.copy(error = "队列加载失败") }
                return@launch
            }
            _uiState.update { it.copy(queue = queue, currentIndex = 0, done = queue.isEmpty()) }
            loadCurrent()
        }
    }

    /** 加载当前卡详情（文本分段 + 原图 + 自定义遮罩） */
    private fun loadCurrent() {
        val item = _uiState.value.current ?: return
        val id = item.id
        viewModelScope.launch {
            val text = store.readTextById(id).getOrNull().orEmpty()
            val imagePath = store.imagePathById(id).getOrNull()
            val mask = maskStore.load(id)?.let { MaskSelectionState(committed = it) }
                ?: MaskSelectionState()
            _uiState.update {
                it.copy(
                    text = LibraryTextFormat.parse(text),
                    imagePath = imagePath,
                    mask = mask,
                    revealed = false,
                    revealProgress = 0f,
                    error = null
                )
            }
        }
    }

    // ---------- 显示答案（T04） ----------

    /** 点击"显示答案"：置 revealed，动画进度由 UI 逐帧回调 */
    fun reveal() {
        _uiState.update { it.copy(revealed = true) }
    }

    /** 揭开动画进度回调（UI 每帧调用；钳制 0..1，非法值按边界处理） */
    fun onRevealProgress(progress: Float) {
        _uiState.update { it.copy(revealProgress = progress.coerceIn(0f, 1f)) }
    }

    // ---------- 手动框选（T05） ----------

    /** 长按图片进入框选模式 */
    fun enterSelectMode() {
        _uiState.update { it.copy(mask = it.mask.enter()) }
    }

    /** 拖动更新框选矩形 */
    fun updateSelection(rect: MaskRect) {
        _uiState.update { it.copy(mask = it.mask.update(rect)) }
    }

    /** 松手定稿：非空矩形持久化到 .mask.json */
    fun commitSelection() {
        val s = _uiState.value
        val committed = s.mask.commit()
        _uiState.update { it.copy(mask = committed) }
        committed.committed?.let { rect ->
            s.current?.let { item -> maskStore.save(item.id, rect) }
        }
    }

    /** 取消框选（回到默认整图遮罩） */
    fun cancelSelection() {
        _uiState.update { it.copy(mask = it.mask.cancel()) }
    }

    // ---------- 自评提交（T06） ----------

    /** 4 档自评：1=Again 2=Hard 3=Good 4=Easy → 提交并推进 */
    fun submitGrade(grade: Int) {
        val s = _uiState.value
        val id = s.current?.id ?: return
        if (s.submitting) return
        _uiState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            submitter.submit(id, grade, nowProvider()).fold(
                onSuccess = {
                    val cur = _uiState.value
                    val nextIndex = cur.currentIndex + 1
                    val done = nextIndex >= cur.queue.size
                    _uiState.update {
                        it.copy(
                            submitting = false,
                            currentIndex = if (done) cur.currentIndex else nextIndex,
                            done = done
                        )
                    }
                    if (!done) loadCurrent()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(submitting = false, error = "提交失败: ${e.message}") }
                }
            )
        }
    }

    /** 当前卡的生效遮罩（自定义优先，无则整图） */
    fun effectiveMask(): MaskRect = _uiState.value.mask.effective()

    companion object {
        /** 默认 FSRS 参数（settings 可调，UI 层暂用初始值） */
        val DEFAULT_PARAMS = Fsrs.Params()
    }
}
