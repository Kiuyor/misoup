package com.misoup.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.data.index.IndexedEntry
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.store.StoreService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 详情页 ViewModel（T05/T06/T08/T10）
 *
 * - 加载：索引条目 + meta.json + 文本.md（分段解析）
 * - 遮罩开关（T06）：MaskState 纯逻辑驱动
 * - 删除双确认（T08）：DeleteConfirmMachine 状态机 + StoreService.delete
 * - 移动科目（T08）：StoreService.move
 * - 识别重试（T10）：OcrRetryHandler
 */
class LibraryDetailViewModel(
    private val index: LibraryIndex,
    private val store: StoreService?,
    private val ocrRetry: OcrRetryHandler?
) : ViewModel() {

    data class DetailState(
        val id: String = "",
        val entry: IndexedEntry? = null,
        val meta: MisoupMeta? = null,
        val text: QuestionText? = null,
        val imagePath: String? = null,
        /** 遮罩（T06）：隐藏答案 + 揭开动画进度 */
        val mask: MaskState = MaskState(),
        /** 是否正在"揭开预览"（UI 动画驱动 revealProgress 0→1） */
        val peeking: Boolean = false,
        /** 删除双确认状态机（T08） */
        val deleteConfirm: DeleteConfirm = DeleteConfirm.Idle,
        /** 移动科目对话框可见 */
        val moveDialogVisible: Boolean = false,
        val busy: Boolean = false,
        val message: String? = null
    ) {
        /** 识别状态条（T10） */
        val ocrStatus: OcrStatusUi
            get() = OcrStatusLogic.of(entry?.ocrStatus, entry?.needsRepair == true)
    }

    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    fun load(id: String) {
        _state.update { DetailState(id = id) }
        viewModelScope.launch {
            val entry = index.get(id)
            val meta = store?.readMetaById(id)?.getOrNull()
            val raw = store?.readTextById(id)?.getOrNull().orEmpty()
            val imagePath = store?.imagePathById(id)?.getOrNull()
            _state.update {
                it.copy(
                    entry = entry,
                    meta = meta,
                    text = LibraryTextFormat.parse(raw),
                    imagePath = imagePath
                )
            }
        }
    }

    // ---------- 遮罩（T06） ----------

    fun toggleMask() {
        _state.update { it.copy(mask = it.mask.toggle(), peeking = false) }
    }

    /** 点击「揭开预览」：开始揭开动画（progress 由 UI 动画推进） */
    fun peek() {
        if (_state.value.mask.hidden) _state.update { it.copy(peeking = true) }
    }

    /** UI 动画帧回调：推进揭开进度（0→1） */
    fun setRevealProgress(progress: Float) {
        _state.update { it.copy(mask = it.mask.withRevealProgress(progress)) }
    }

    /** 揭开动画结束（progress 达到 1）：收起预览 */
    fun onRevealFinished() {
        _state.update { it.copy(peeking = false) }
    }

    // ---------- 删除（T08） ----------

    fun onDeleteEvent(event: DeleteEvent) {
        val before = _state.value.deleteConfirm
        val after = DeleteConfirmMachine.next(before, event)
        _state.update { it.copy(deleteConfirm = after) }
        // 进入 Deleting 后执行真实删除
        if (after == DeleteConfirm.Deleting) {
            viewModelScope.launch {
                val result = store?.delete(_state.value.id)
                    ?: Result.failure(IllegalStateException("存储未初始化"))
                _state.update {
                    it.copy(
                        deleteConfirm = DeleteConfirmMachine.next(
                            DeleteConfirm.Deleting,
                            if (result.isSuccess) DeleteEvent.Success
                            else DeleteEvent.Fail(result.exceptionOrNull()?.message ?: "删除失败")
                        )
                    )
                }
            }
        }
        if (after is DeleteConfirm.Error) {
            _state.update { it.copy(message = "删除失败：${after.message}") }
        }
    }

    /** 关闭删除错误提示 */
    fun dismissDeleteError() {
        val s = _state.value
        if (s.deleteConfirm is DeleteConfirm.Error) {
            _state.update {
                it.copy(deleteConfirm = DeleteConfirmMachine.next(s.deleteConfirm, DeleteEvent.Reset))
            }
        }
    }

    // ---------- 移动（T08） ----------

    fun showMoveDialog() = _state.update { it.copy(moveDialogVisible = true) }

    fun dismissMoveDialog() = _state.update { it.copy(moveDialogVisible = false) }

    fun moveTo(newSubject: String) {
        val id = _state.value.id
        _state.update { it.copy(moveDialogVisible = false, busy = true) }
        viewModelScope.launch {
            val result = store?.move(id, newSubject)
                ?: Result.failure(IllegalStateException("存储未初始化"))
            _state.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) "已移动到「$newSubject」" else "移动失败：${result.exceptionOrNull()?.message}"
                )
            }
            // 移动后索引条目已更新，重读展示
            index.get(id)?.let { e -> _state.update { it.copy(entry = e) } }
        }
    }

    // ---------- 识别重试（T10） ----------

    fun retryOcr() {
        val id = _state.value.id
        ocrRetry?.retry(id)
        _state.update { it.copy(message = "已重新提交识别") }
    }

    /** 分享占位（模块 08 填充） */
    fun share() {
        _state.update { it.copy(message = "分享功能将在导出模块提供") }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
