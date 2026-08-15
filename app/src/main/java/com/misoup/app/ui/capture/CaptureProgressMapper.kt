package com.misoup.app.ui.capture

import com.misoup.app.recognition.coordinator.BatchItem
import com.misoup.app.recognition.coordinator.StorePhase
import com.misoup.app.recognition.queue.RecognitionPhase
import com.misoup.app.recognition.queue.RecognitionStatus

/**
 * T10 全屏进度页聚合逻辑（纯函数，可 JVM 单测）
 *
 * 输入：批次收录状态 [batch] + 识别队列状态 [queueStatus]（按 metaId 索引）
 * 输出：逐张 UI 状态（缩略图 + 状态徽标）。
 *
 * 状态机：
 * - 收录中 STORING        → 「收录中…」（灰点）
 * - 收录失败 STORE_FAILED → 「收录失败」（红）
 * - 已收录+队列 pending   → 「识别中…」（转圈）
 * - 已收录+队列 done      → 「已收录」（绿✓）
 * - 已收录+队列 failed    → 「识别失败」（红，可重试）
 * - 已收录+队列无记录     → 「识别中…」（入队与状态回写间的窗口期，按进行中处理）
 */
object CaptureProgressMapper {

    /** 逐张徽标状态（全中文文案，UI 直接渲染） */
    enum class ItemState(val label: String) {
        STORING("收录中…"),
        STORE_FAILED("收录失败"),
        RECOGNIZING("识别中…"),
        DONE("已收录"),
        RECOG_FAILED("识别失败")
    }

    /** 进度页单条 UI 模型 */
    data class ItemUi(
        val index: Int,          // 批次序号（0 起）
        val sourceUri: String,   // 缩略图加载源
        val state: ItemState,
        val metaId: String? = null,   // 识别失败重试用
        val error: String? = null     // 失败原因（极客可见）
    )

    /** 汇总条文案（"已收录 3/5"；全失败 "收录 0/5"） */
    fun summaryLabel(ok: Int, total: Int): String = "已收录 $ok/$total"

    /**
     * 合成逐张状态。batch 与 queueStatus 均可能为空（未开始/已完成清空）。
     * 队列状态只对已收录（STORED）条目生效；收录失败条目不再参与识别阶段。
     */
    fun map(
        batch: List<BatchItem>,
        queueStatus: Map<String, RecognitionStatus>
    ): List<ItemUi> = batch.mapIndexed { index, item ->
        val recognition = item.metaId?.let { queueStatus[it] }
        val state = when (item.storePhase) {
            StorePhase.STORING -> ItemState.STORING
            StorePhase.FAILED -> ItemState.STORE_FAILED
            StorePhase.STORED -> when (recognition?.phase) {
                RecognitionPhase.DONE -> ItemState.DONE
                RecognitionPhase.FAILED -> ItemState.RECOG_FAILED
                // PENDING 或暂无记录（入队窗口期）→ 识别中
                else -> ItemState.RECOGNIZING
            }
        }
        ItemUi(
            index = index,
            sourceUri = item.source.uri,
            state = state,
            metaId = item.metaId,
            error = recognition?.error
        )
    }
}

/**
 * T03 连拍状态机（纯逻辑，可 JVM 单测）
 *
 * 连拍 = 连续 N 次 capture，每张独立入管线（模块 01 文档）。
 * 状态机只跟踪计数与开关：start → (capture → next) × N → done。
 * UI 层据此驱动 CameraX 连续拍摄 + 展示「第 x/N 张」。
 */
data class BurstState(
    val total: Int,
    val taken: Int = 0,
    val capturing: Boolean = false
) {
    val remaining: Int get() = (total - taken).coerceAtLeast(0)
    val done: Boolean get() = taken >= total

    companion object {
        /** 连拍默认张数（模块 01 文档"连续 N 次"，UI 可调，默认 3） */
        const val DEFAULT_BURST_COUNT = 3
        const val MAX_BURST_COUNT = 9

        /** 启动连拍；非法张数（<1）回退默认值 */
        fun start(total: Int = DEFAULT_BURST_COUNT): BurstState =
            BurstState(total = total.coerceIn(1, MAX_BURST_COUNT), capturing = true)

        /** 完成一张（防溢出：taken 不超过 total） */
        fun next(state: BurstState): BurstState =
            state.copy(taken = (state.taken + 1).coerceAtMost(state.total))

        /** 中止连拍（用户取消/错误），done 标记 */
        fun abort(state: BurstState): BurstState = state.copy(capturing = false)
    }
}
