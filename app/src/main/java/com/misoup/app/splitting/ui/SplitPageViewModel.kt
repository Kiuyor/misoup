package com.misoup.app.splitting.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.misoup.app.splitting.candidates.BoundingBox
import com.misoup.app.splitting.candidates.BoxEditor
import com.misoup.app.splitting.candidates.PageSplitter
import com.misoup.app.splitting.candidates.TextLine
import com.misoup.app.splitting.candidates.TwoColumnHint
import com.misoup.app.splitting.llmrefine.LlmRefineCoordinator
import com.misoup.app.splitting.llmrefine.RefineResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * T03 拆题页 ViewModel：持有 [BoxEditor]（纯逻辑状态机）并桥接 UI。
 *
 * 职责：
 * - 运行 T01 管线产出初始候选框（离线初稿）
 * - 可选叠加视觉 LLM 修正（无 Key → 降级，提示使用离线结果）
 * - 转发拖拽/新增/删除/合并/撤销全部手势到编辑器
 * - 透传「开始逐题录入」进度（T04，由 Screen 调用 submitter）
 */
class SplitPageViewModel(
    initialLines: List<TextLine> = emptyList(),
    pageWidth: Int = 0,
    llmEnabled: Boolean = false,
) : ViewModel() {

    /** 拆题页 UI 状态 */
    data class UiState(
        val boxes: List<BoundingBox> = emptyList(),
        val selected: Int? = null,
        val message: String = "",
        val unsplittable: Boolean = false,
        val twoColumnHint: Boolean = false,
        val llmDegraded: Boolean = false,
        val llmMessage: String = "",
        val importing: Boolean = false,
        val importDone: Int = 0,
        val importTotal: Int = 0,
        val importSummary: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var editor: BoxEditor = BoxEditor(emptyList())

    /** 撤销全部目标 = 进入编辑时的框快照（load 时固化，T05 提交前可撤销全部） */
    private var snapshot: List<BoundingBox> = emptyList()

    init {
        load(initialLines, pageWidth, llmEnabled)
    }

    /**
     * 加载整页 OCR 行 → T01 候选框（离线）+ 可选 LLM 修正。
     * 无 Key / 坏 JSON → 降级为离线结果（T02 §4）。
     */
    fun load(lines: List<TextLine>, pageWidth: Int, llmEnabled: Boolean = false, llmJson: String? = null) {
        val outcome = PageSplitter.split(lines)
        // 两栏判定基于原始行（行聚类会把左右栏同 y 行并块，栏间空隙消失）
        val twoCol = TwoColumnHint.isTwoColumn(lines, pageWidth)

        if (outcome.unsplittable) {
            _state.value = UiState(
                message = outcome.message,
                unsplittable = true,
                twoColumnHint = twoCol,
            )
            return
        }

        // 视觉 LLM 修正（可选增强）：降级路径自动返回离线框
        val refined: RefineResult = LlmRefineCoordinator.refine(outcome.boxes, llmJson, llmEnabled)
        editor = BoxEditor(refined.refined)
        snapshot = refined.refined
        _state.value = UiState(
            boxes = editor.boxes,
            message = outcome.message,
            twoColumnHint = twoCol,
            llmDegraded = refined.degraded,
            llmMessage = refined.message,
        )
    }

    // ---------- 手势转发 ----------

    fun select(index: Int) {
        editor.select(index)
        push()
    }

    fun moveSelected(dx: Float, dy: Float) {
        editor.moveSelected(dx, dy)
        push()
    }

    fun addBox(pageWidth: Float, pageHeight: Float) {
        editor.addBox(pageWidth, pageHeight)
        push()
    }

    fun deleteSelected() {
        editor.deleteSelected()
        push()
    }

    fun mergeSelected() {
        editor.mergeSelected()
        push()
    }

    fun undoAll() {
        editor.undoAll()
        push()
    }

    /** 提交前撤销全部（T05）：恢复进入编辑时的框 */
    fun undoAllToSnapshot() {
        editor = BoxEditor(snapshot)
        push()
    }

    // ---------- 录入进度（T04） ----------

    fun onImportStart(total: Int) {
        _state.value = _state.value.copy(importing = true, importTotal = total, importDone = 0, importSummary = "")
    }

    fun onImportProgress(done: Int, total: Int) {
        _state.value = _state.value.copy(importing = true, importDone = done, importTotal = total)
    }

    fun onImportFinished(summary: SplitSubmitSummary) {
        _state.value = _state.value.copy(
            importing = false,
            importSummary = "已入库 ${summary.ok} 题" +
                (if (summary.failed.isNotEmpty()) "，失败 ${summary.failed.size} 题" else ""),
        )
    }

    /** 供 UI 读取最终框列表（提交时用） */
    fun currentBoxes(): List<BoundingBox> = editor.boxes

    /** 命中测试透传（拖拽/点按选中用） */
    fun pickAt(x: Float, y: Float): Int? = editor.pickAt(x, y)

    private fun push() {
        _state.value = _state.value.copy(boxes = editor.boxes, selected = editor.selected)
    }
}
