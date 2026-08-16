package com.misoup.app.splitting.candidates

/**
 * T05b 两栏排版提示（纯逻辑）：离线启发式对两栏卷拆题效果差，
 * 检测到两栏时提示「建议启用视觉 LLM 修正」（模块 06 边界情况：
 * 两栏排版视觉 LLM 修正为主，离线结果可作初稿）。
 *
 * 检测法（基于原始行而非聚类块——行聚类会把左右栏同 y 的行并进一块，
 * 导致栏间空隙消失，故必须用未聚类的行做 x 分布分析）：
 * 1. 行中心 x 升序排序
 * 2. 找相邻中心的最大间距；若该间距 > 页宽 * [MIN_COLUMN_GAP_RATIO]
 *    且左右两侧行数都 ≥ [MIN_LINES_PER_COLUMN] → 判定两栏
 */
object TwoColumnHint {

    /** 两栏判定最小水平间距（相对页宽） */
    const val MIN_COLUMN_GAP_RATIO = 0.15f

    /** 每栏最少行数（防单列噪声误判） */
    const val MIN_LINES_PER_COLUMN = 2

    /** 基于原始文本行判定（推荐入口：行聚类前调用） */
    fun isTwoColumn(lines: List<TextLine>, pageWidth: Float): Boolean {
        if (lines.size < MIN_LINES_PER_COLUMN * 2 || pageWidth <= 0f) return false
        val centers = lines.map { it.centerX }.sorted()
        // 找最大相邻间距
        var maxGap = 0f
        var splitAfter = -1
        for (i in 0 until centers.size - 1) {
            val gap = centers[i + 1] - centers[i]
            if (gap > maxGap) {
                maxGap = gap
                splitAfter = i
            }
        }
        if (maxGap <= pageWidth * MIN_COLUMN_GAP_RATIO) return false
        val left = splitAfter + 1
        val right = centers.size - left
        return left >= MIN_LINES_PER_COLUMN && right >= MIN_LINES_PER_COLUMN
    }

    /** 基于行块判定（供已有块列表的调用方） */
    fun isTwoColumnBlocks(blocks: List<LineBlock>, pageWidth: Float): Boolean {
        if (blocks.size < MIN_LINES_PER_COLUMN * 2 || pageWidth <= 0f) return false
        val centers = blocks.map { it.left + it.width / 2f }.sorted()
        var maxGap = 0f
        var splitAfter = -1
        for (i in 0 until centers.size - 1) {
            val gap = centers[i + 1] - centers[i]
            if (gap > maxGap) {
                maxGap = gap
                splitAfter = i
            }
        }
        if (maxGap <= pageWidth * MIN_COLUMN_GAP_RATIO) return false
        val left = splitAfter + 1
        val right = centers.size - left
        return left >= MIN_LINES_PER_COLUMN && right >= MIN_LINES_PER_COLUMN
    }

    /** 便捷入口：整页宽（Int 页宽） */
    fun isTwoColumn(lines: List<TextLine>, pageWidth: Int): Boolean =
        isTwoColumn(lines, pageWidth.toFloat())
}
