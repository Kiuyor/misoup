package com.misoup.app.ui.library

/**
 * 文本.md 分段格式（T05/T07）
 *
 * 文档仅约定"题干/答案/解析 分段 Markdown（含 $LaTeX$）"，未固定标头格式。
 * 本模块裁决采用一级 Markdown 标题分段（模块 01 写盘与模块 02 解析共用此约定）：
 * ```
 * ## 题干
 * <题干文本，可含 $LaTeX$>
 *
 * ## 答案
 * <答案文本>
 *
 * ## 解析
 * <解析文本>
 * ```
 * 解析容错：缺段/乱序/多余内容不崩——无标头时整段视为题干；未识别段落原样保留在题干尾部。
 */
data class QuestionText(
    val question: String = "",
    val answer: String = "",
    val explanation: String = ""
) {
    val isEmpty: Boolean get() = question.isBlank() && answer.isBlank() && explanation.isBlank()
}

object LibraryTextFormat {

    const val QUESTION_HEADER = "## 题干"
    const val ANSWER_HEADER = "## 答案"
    const val EXPLANATION_HEADER = "## 解析"

    /** 段名（规范化后与标头级数无关：# 题干 / ## 题干 / ### 题干 等价） */
    private const val SECTION_QUESTION = "题干"
    private const val SECTION_ANSWER = "答案"
    private const val SECTION_EXPLANATION = "解析"

    /** 匹配任意级 Markdown 标题并捕获段名 */
    private val HEADER = Regex("^#{1,6}\\s+(.+)$")

    /** 序列化为 文本.md 内容（仅写非空段；至少保留题干段避免空文件） */
    fun toMarkdown(text: QuestionText): String {
        val parts = mutableListOf<String>()
        if (text.question.isNotBlank()) parts += "$QUESTION_HEADER\n${text.question.trim()}"
        if (text.answer.isNotBlank()) parts += "$ANSWER_HEADER\n${text.answer.trim()}"
        if (text.explanation.isNotBlank()) parts += "$EXPLANATION_HEADER\n${text.explanation.trim()}"
        if (parts.isEmpty()) return ""   // 全空 → 空文件（调用方自行决定是否写盘）
        return parts.joinToString("\n\n")
    }

    /**
     * 解析 文本.md：按标头分段。
     * - 无标头（纯文本）→ 全部归题干
     * - 标头级数任意（#~######），段名匹配"题干/答案/解析"
     * - 未知标头段内容追加到题干尾部（保留信息不丢）
     */
    fun parse(markdown: String): QuestionText {
        if (markdown.isBlank()) return QuestionText()
        val lines = markdown.replace("\r\n", "\n").split("\n")
        var section = ""            // 当前段名（规范化后）；"" = 未遇到标头
        val question = StringBuilder()
        val answer = StringBuilder()
        val explanation = StringBuilder()
        val unknown = StringBuilder()

        fun target(): StringBuilder? = when (section) {
            SECTION_QUESTION -> question
            SECTION_ANSWER -> answer
            SECTION_EXPLANATION -> explanation
            "" -> question          // 标头前的散落内容视为题干
            else -> unknown
        }

        for (line in lines) {
            val header = HEADER.find(line.trim())?.groupValues?.get(1)?.trim()
            if (header != null) {
                section = header
                continue
            }
            target()?.append(line)?.append('\n')
        }

        // 未识别段并入题干尾部（保持信息完整）
        val tail = unknown.toString().trim()
        val q = question.toString().trim()
        return QuestionText(
            question = if (tail.isEmpty()) q else listOf(q, tail).filter { it.isNotBlank() }.joinToString("\n"),
            answer = answer.toString().trim(),
            explanation = explanation.toString().trim()
        )
    }
}
