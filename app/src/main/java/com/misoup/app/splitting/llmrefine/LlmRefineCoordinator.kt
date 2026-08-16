package com.misoup.app.splitting.llmrefine

import com.misoup.app.splitting.candidates.BoundingBox

/**
 * T02 视觉 LLM 校验协调器（含 §4 无 Key 降级路径）。
 *
 * 数据流：离线候选框 [offlineBoxes] + 视觉 LLM 建议 JSON [llmJson] →
 * 解析 → 按序应用操作 → 最终候选框。
 *
 * 降级规则（模块 06 文档 §实现路径 2「可选增强，无 Key 跳过」）：
 * - 无 Key / 未启用 / llmJson 为空 → 直接返回离线结果（不解析不报错）
 * - llmJson 坏 JSON / 结构非法 → 返回离线结果 + 降级标记（不崩溃）
 * - 正常 → 应用操作返回修正后结果
 */
object LlmRefineCoordinator {

    const val MSG_NO_KEY = "视觉 LLM 未配置（无 Key），使用离线候选框"
    const val MSG_BAD_JSON = "视觉 LLM 建议解析失败，降级为离线候选框"

    /**
     * @param offlineBoxes 离线候选框（T01 输出）
     * @param llmJson 视觉 LLM 建议 JSON（可为 null）
     * @param enabled 视觉 LLM 是否启用（无 Key → false，走降级）
     * @return [RefineResult]（refined 始终非空：降级时 = offlineBoxes）
     */
    fun refine(
        offlineBoxes: List<BoundingBox>,
        llmJson: String?,
        enabled: Boolean,
    ): RefineResult {
        if (!enabled) return RefineResult(offlineBoxes, degraded = true, message = MSG_NO_KEY)
        if (llmJson.isNullOrBlank()) return RefineResult(offlineBoxes, degraded = true, message = MSG_NO_KEY)

        val parsed = LlmRefineParser.parse(llmJson)
        if (parsed.isFailure) return RefineResult(offlineBoxes, degraded = true, message = MSG_BAD_JSON)

        val ops = parsed.getOrThrow()
        var boxes = offlineBoxes
        for (op in ops) {
            boxes = op.apply(boxes)
        }
        return RefineResult(boxes, degraded = false, message = "视觉 LLM 修正完成")
    }
}

/** 校验结果：refined = 最终候选框；degraded = 是否走了降级路径 */
data class RefineResult(
    val refined: List<BoundingBox>,
    val degraded: Boolean,
    val message: String,
)
