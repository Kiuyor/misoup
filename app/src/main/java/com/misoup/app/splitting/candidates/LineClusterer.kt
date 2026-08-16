package com.misoup.app.splitting.candidates

/**
 * T01a 行聚类：把文本行按 y 间距合并成行块。
 *
 * 算法（模块 06 文档「行聚类（y 间距 < 阈值合并）」）：
 * 1. 按 top 升序排序（同 top 按 x 升序，保持稳定）
 * 2. 顺序扫描：当前行块维护外接盒；下一行与块底部的 y 间距
 *    （next.top - block.bottom）< 阈值 → 并入同一块，否则另起新块
 * 3. 块内行不要求宽度/左对齐（题号行与正文行可能 x 不同）
 *
 * 边界约定（严格小于）：gap == threshold 不合并（可测的确定性边界）。
 */
object LineClusterer {

    /** 默认聚类阈值（像素，页宽约 1000+ 时行距一般 <10px，题间距离 >>10px） */
    const val DEFAULT_GAP_THRESHOLD = 10f

    /**
     * 行聚类。
     * @param lines 任意顺序的文本行
     * @param gapThreshold y 间距阈值；<=0 时每行独立成块
     */
    fun cluster(lines: List<TextLine>, gapThreshold: Float = DEFAULT_GAP_THRESHOLD): List<LineBlock> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val blocks = mutableListOf<MutableList<TextLine>>()
        var blockBottom = Float.NEGATIVE_INFINITY

        for (line in sorted) {
            val gap = line.top - blockBottom
            val canMerge = blocks.isNotEmpty() && gapThreshold > 0f && gap < gapThreshold
            if (canMerge) {
                blocks.last().add(line)
                blockBottom = maxOf(blockBottom, line.bottom)
            } else {
                blocks.add(mutableListOf(line))
                blockBottom = line.bottom
            }
        }
        return blocks.map { LineBlock.of(it) }
    }
}
