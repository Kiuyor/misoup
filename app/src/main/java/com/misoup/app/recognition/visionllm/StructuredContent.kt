package com.misoup.app.recognition.visionllm

import kotlinx.serialization.Serializable

/**
 * 视觉 LLM 结构化识别结果（模块 05 T06）
 *
 * 字段对齐 module/05 文档 JSON 契约：
 * {"question":"题干，公式用 $LaTeX$ 包裹","answer":"答案","explanation":"解析"}
 */
@Serializable
data class StructuredContent(
    val question: String = "",
    val answer: String = "",
    val explanation: String = "",
) {
    val isEmpty: Boolean get() = question.isBlank() && answer.isBlank() && explanation.isBlank()

    /**
     * 序列化为 文本.md 分区内容（T07 Coordinator 视觉成功写入）。
     * 格式与全工程约定一致（module/02 裁决）：`## 题干 / ## 答案 / ## 解析`，
     * 仅写非空段；全空返回空串。
     */
    fun toMarkdown(): String {
        val parts = mutableListOf<String>()
        if (question.isNotBlank()) parts += "## 题干\n${question.trim()}"
        if (answer.isNotBlank()) parts += "## 答案\n${answer.trim()}"
        if (explanation.isNotBlank()) parts += "## 解析\n${explanation.trim()}"
        return parts.joinToString("\n\n")
    }
}
