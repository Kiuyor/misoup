package com.misoup.app.recognition.paddle

/**
 * 行排序与组装（T05，纯逻辑）
 *
 * 阅读顺序算法：
 * 1. 按 y 中心排序 → 依次尝试归入已有行组（组内任意行 y 区间重叠，容差 [yTolerance]）
 *    —— 同一段落的多行文字 y 中心接近，归为一组
 * 2. 组间按组内最小 y 排序（自上而下）；组内按 x 中心排序（自左向右）
 * 3. 文本 = 行文本按组内顺序 join("\n")（组间也 join("\n")，即逐行换行）
 */
object LineAssembler {

    const val DEFAULT_Y_TOLERANCE = 10f

    /** 按阅读顺序排序行 */
    fun sortByReadingOrder(
        lines: List<OcrLine>,
        yTolerance: Float = DEFAULT_Y_TOLERANCE,
    ): List<OcrLine> {
        if (lines.size <= 1) return lines

        // 1. 按 y 中心升序扫描，贪心归组（y 区间重叠即可同组）
        val rows = mutableListOf<MutableList<OcrLine>>()
        val sortedByY = lines.sortedBy { it.box.centerY }
        for (line in sortedByY) {
            val row = rows.firstOrNull { r ->
                r.any { it.box.overlapsY(line.box, yTolerance) }
            }
            if (row == null) {
                rows += mutableListOf(line)
            } else {
                row += line
            }
        }

        // 2. 组内按 x 排序，组间按最小 y 排序
        return rows
            .map { row -> row.sortedBy { it.box.centerX } }
            .sortedBy { row -> row.minOf { it.box.centerY } }
            .flatten()
    }

    /** 行文本拼接（保留空行语义：join("\n")） */
    fun joinText(lines: List<OcrLine>): String = lines.joinToString("\n") { it.text }

    /** 组装 OcrResult：排序 + 拼接 + 耗时 */
    fun assemble(lines: List<OcrLine>, latencyMs: Long, yTolerance: Float = DEFAULT_Y_TOLERANCE): OcrResult {
        val ordered = sortByReadingOrder(lines, yTolerance)
        return OcrResult(lines = ordered, text = joinText(ordered), latencyMs = latencyMs)
    }
}
