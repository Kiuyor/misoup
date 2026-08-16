package com.misoup.app.ai.variants

/**
 * 变式 Prompt（module/07 文档定稿，逐字保留）：
 *
 * ```
 * 你是出题老师。基于下面的错题，出 {n} 道同考点的变式题（难度递增），只输出 JSON 数组：
 * [{"question":"...", "answer":"...", "hint":"..."}]
 * 考点: {knowledgePoints} 原题: {文本.md 题干+答案}
 * ```
 *
 * 文档裁决：
 * - 数量约束 1-3（详情页弹窗定稿）；考点为空 → "未标注"（不崩溃）
 * - 「原题」取 文本.md 的 题干+答案 段（解析段不进 Prompt，避免模型被解析内容带偏）
 */
object VariantPrompt {

    const val MIN_COUNT = 1
    const val MAX_COUNT = 3

    private const val UNKNOWN_KP = "未标注"

    private val HEADER = Regex("^#{1,6}\\s+(.+)$")

    /** 构造变式 Prompt；count 越界 → IllegalArgumentException */
    fun build(knowledgePoints: List<String>, originalText: String, count: Int): String {
        require(count in MIN_COUNT..MAX_COUNT) { "变式数量需在 $MIN_COUNT-$MAX_COUNT 之间" }
        val kps = knowledgePoints.filter { it.isNotBlank() }.joinToString("、").ifBlank { UNKNOWN_KP }
        val original = extractQuestionAndAnswer(originalText)
        return buildString {
            append("你是出题老师。基于下面的错题，出 $count 道同考点的变式题（难度递增），只输出 JSON 数组：\n")
            append("[{\"question\":\"...\", \"answer\":\"...\", \"hint\":\"...\"}]\n")
            append("考点: $kps 原题: $original")
        }
    }

    /**
     * 提取 文本.md 的 题干+答案 段（保持原始顺序；无标头时整段视为题干）。
     * 容错：缺段/乱序不崩；解析段与未知段忽略。
     */
    fun extractQuestionAndAnswer(markdown: String): String {
        if (markdown.isBlank()) return ""
        var section = ""
        val question = StringBuilder()
        val answer = StringBuilder()
        for (line in markdown.replace("\r\n", "\n").split("\n")) {
            val header = HEADER.find(line.trim())?.groupValues?.get(1)?.trim()
            if (header != null) {
                section = header
                continue
            }
            when (section) {
                "题干" -> question.append(line).append('\n')
                "答案" -> answer.append(line).append('\n')
            }
        }
        val parts = listOfNotNull(
            question.toString().trim().takeIf { it.isNotBlank() }?.let { "题干: $it" },
            answer.toString().trim().takeIf { it.isNotBlank() }?.let { "答案: $it" }
        )
        return if (parts.isEmpty()) markdown.trim() else parts.joinToString("\n")
    }
}
