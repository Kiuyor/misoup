package com.misoup.app.splitting.candidates

/**
 * T01 整页拆题编排入口（候选框生成全链路）+ T05a 无法拆分判定。
 *
 * 管线：det 行列表 → [LineClusterer] 行聚类 → [QuestionNumberDetector] 题号分界
 * → [CandidateBoxGenerator] 候选框。
 *
 * 无 Key 时（T02 降级 §4）：调用方直接用本类输出（离线初稿）；
 * 视觉 LLM 修正由 llmrefine 包在 [SplitOutcome] 之后叠加。
 */
object PageSplitter {

    /** T05a 烂图/无分界提示文案（模块 06 文档边界情况原文） */
    const val MESSAGE_NO_BOUNDARY = "未识别到题目分界，建议手动裁剪"
    const val MESSAGE_EMPTY_LINES = "未检测到文本行，请检查图片"

    /**
     * 拆题主入口。
     * @return 候选框列表 + 状态信息；无法拆分时 [SplitOutcome.unsplittable]=true
     */
    fun split(lines: List<TextLine>, gapThreshold: Float = LineClusterer.DEFAULT_GAP_THRESHOLD): SplitOutcome {
        if (lines.isEmpty()) {
            return SplitOutcome(boxes = emptyList(), message = MESSAGE_EMPTY_LINES, unsplittable = true)
        }
        val blocks = LineClusterer.cluster(lines, gapThreshold)
        val boundaries = QuestionNumberDetector.findBoundaries(blocks)
        if (boundaries.isEmpty()) {
            return SplitOutcome(boxes = emptyList(), message = MESSAGE_NO_BOUNDARY, unsplittable = true)
        }
        val boxes = CandidateBoxGenerator.generate(blocks, boundaries)
        return SplitOutcome(
            boxes = boxes,
            message = if (boxes.isEmpty()) MESSAGE_NO_BOUNDARY else "识别到 ${boxes.size} 道题",
            unsplittable = boxes.isEmpty(),
        )
    }
}

/** 拆题结果：候选框（原图坐标）+ 信息 + 是否无法拆分 */
data class SplitOutcome(
    val boxes: List<BoundingBox>,
    val message: String,
    val unsplittable: Boolean,
)
