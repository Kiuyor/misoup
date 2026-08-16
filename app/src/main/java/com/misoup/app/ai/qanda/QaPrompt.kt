package com.misoup.app.ai.qanda

/**
 * 答疑 Prompt（模块 07 T05，module/07 文档定稿）：
 *
 * 前缀（逐字保留）：
 * `你是耐心的高中理科老师。讲解下面这道错题，先给思路再给步骤，不要直接给答案除非用户要求。`
 * + 错题内容（结构化：考点 + 文本.md 题干/答案/解析）+ 最近对话历史 + 用户提问。
 *
 * 跟随指令（文档定稿：支持「重讲 / 举例 / 换一种解法」）：
 * 用户问题命中关键词 → 追加对应指令后缀，保证三种诉求被模型明确响应。
 *
 * 会话仅内存（[QaSession]），Prompt 只带最近 [HISTORY_TURNS] 条历史（控制 token 成本）。
 */
object QaPrompt {

    /** 文档定稿前缀（逐字保留） */
    const val PREFIX = "你是耐心的高中理科老师。讲解下面这道错题，先给思路再给步骤，不要直接给答案除非用户要求。"

    /** 历史对话最大携带条数（用户+助手各计；超出丢弃最旧） */
    const val HISTORY_TURNS = 4

    /** 跟随指令 */
    const val FOLLOW_UP_RETELL = "请重新讲解一遍，换更简单易懂的语言。"
    const val FOLLOW_UP_EXAMPLE = "请举一个具体的例子说明。"
    const val FOLLOW_UP_ALT_METHOD = "请换一种解法（不同思路）再讲一遍。"

    /** 错题内容段：结构化内容（考点 + 文本.md） */
    fun buildContext(knowledgePoints: List<String>, text: String): String {
        val kpLine = knowledgePoints
            .filter { it.isNotBlank() }
            .joinToString("、")
            .takeIf { it.isNotBlank() }
            ?.let { "考点: $it\n" }
            .orEmpty()
        return kpLine + "错题内容:\n${text.trim()}"
    }

    /**
     * 命中跟随意图 → 指令后缀；未命中 → null。
     * 关键词：重讲/再讲 → 重讲；举例/例子 → 举例；换一种解法/另一种/别的方法 → 换解法。
     */
    fun followUpInstruction(userQuestion: String): String? = when {
        userQuestion.contains("换一种解法") ||
            userQuestion.contains("换个解法") ||
            userQuestion.contains("另一种解法") ||
            userQuestion.contains("别的方法") -> FOLLOW_UP_ALT_METHOD
        userQuestion.contains("举例") || userQuestion.contains("例子") -> FOLLOW_UP_EXAMPLE
        userQuestion.contains("重讲") || userQuestion.contains("再讲") -> FOLLOW_UP_RETELL
        else -> null
    }

    /**
     * 组装完整 Prompt：前缀 + 错题内容 + 历史对话（可选）+ 用户提问 + 跟随指令。
     * @param history 之前的对话（不含当前提问；最多取最近 [HISTORY_TURNS] 条）
     */
    fun build(
        questionText: String,
        userQuestion: String,
        history: List<QaMessage> = emptyList()
    ): String = buildString {
        append(PREFIX).append('\n')
        append(questionText).append('\n')
        if (history.isNotEmpty()) {
            append("\n历史对话:\n")
            history.takeLast(HISTORY_TURNS).forEach { m ->
                append(if (m.role == QaMessage.Role.USER) "用户: " else "老师: ")
                    .append(m.text)
                    .append('\n')
            }
        }
        append("\n用户问题: ").append(userQuestion.trim())
        followUpInstruction(userQuestion)?.let { append('\n').append(it) }
    }
}
