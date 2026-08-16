package com.misoup.app.splitting.candidates

/**
 * T01c 候选框生成：分界点之间的行块 → 合并外接矩形 → List<BoundingBox>。
 *
 * 规则（模块 06 文档 §实现路径）：
 * - 分界下标 [boundaries] 为行块列表下标；第 k 个候选框 = 从 boundaries[k]
 *   到 boundaries[k+1]-1（或末尾）的所有行块的外接并集
 * - 第一个分界之前的行块（如试卷标题「数学试卷」）不作为候选框内容，
 *   但参与后续框的并集计算前的「前置块」被丢弃（文档：分界点之间的行块）
 * - 无分界 → 空列表（T05a：无法拆分 → 手动裁剪提示）
 * - 插图/表格行块自然并入所在题的外接矩形（框内保留，不做版面重排）
 */
object CandidateBoxGenerator {

    /**
     * 生成候选框。
     * @param blocks 行块（须已按阅读顺序排好：top 升序）
     * @param boundaries 题号分界行块下标（升序，来自 [QuestionNumberDetector.findBoundaries]）
     */
    fun generate(blocks: List<LineBlock>, boundaries: List<Int>): List<BoundingBox> {
        if (blocks.isEmpty() || boundaries.isEmpty()) return emptyList()
        val boxes = mutableListOf<BoundingBox>()
        for (k in boundaries.indices) {
            val start = boundaries[k]
            val end = if (k + 1 < boundaries.size) boundaries[k + 1] else blocks.size
            if (start >= blocks.size) continue
            val seg = blocks.subList(start, end)
            if (seg.isEmpty()) continue
            boxes += BoundingBox.ofBounds(
                left = seg.minOf { it.left },
                top = seg.minOf { it.top },
                right = seg.maxOf { it.right },
                bottom = seg.maxOf { it.bottom },
            )
        }
        return boxes
    }
}
