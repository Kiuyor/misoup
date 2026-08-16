package com.misoup.app.ai.variants

import com.misoup.app.data.model.MisoupConfigConst
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 变式题数据类（模块 07 文档定稿：title/question/answer/hint）。
 *
 * LLM 输出 JSON 仅含 question/answer/hint（module/07 Prompt 定稿），
 * [title] 由 [titleOf] 从题干首行推导（列表展示用）。
 */
data class Variant(
    val title: String,
    val question: String,
    val answer: String,
    val hint: String? = null
)

/** 标题推导：题干首行，剔除空白，截 20 字；空题干 → 空串 */
fun titleOf(question: String): String {
    val firstLine = question.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
    val cleaned = firstLine.replace(Regex("\\s+"), "")
    return if (cleaned.length <= MAX_TITLE_LENGTH) cleaned else cleaned.take(MAX_TITLE_LENGTH)
}

private const val MAX_TITLE_LENGTH = 20

/**
 * 变式文件名（module/07 定稿：`变式/yyyyMMdd_HHmmss_变式N.md`）。
 * N 从 1 起；同批生成同一时间戳，序号区分。
 */
object VariantFileName {

    /** 生成：yyyyMMdd_HHmmss_变式N.md（[index] 从 1 起） */
    fun generate(nowMillis: Long, index: Int): String {
        require(index >= 1) { "变式序号需从 1 起: $index" }
        val ts = SimpleDateFormat(
            MisoupConfigConst.NAME_DATE_PATTERN, Locale.US
        ).format(Date(nowMillis))
        return "${ts}_变式$index.md"
    }

    /** 文件名格式校验（保存前防路径注入/脏名） */
    fun isValid(name: String): Boolean =
        FILE_NAME.matches(name)

    private val FILE_NAME = Regex("\\d{8}_\\d{6}_变式\\d+\\.md")
}

/**
 * 变式 .md 渲染/解析（与 文本.md 同风格分段：## 题干 / ## 答案 / ## 提示）。
 * 提示为空 → 省略该段（保持文件干净）。
 */
object VariantMarkdown {

    const val QUESTION_HEADER = "## 题干"
    const val ANSWER_HEADER = "## 答案"
    const val HINT_HEADER = "## 提示"

    /** 渲染 变式N.md 内容 */
    fun render(variant: Variant): String {
        val parts = mutableListOf<String>()
        parts += "$QUESTION_HEADER\n${variant.question.trim()}"
        parts += "$ANSWER_HEADER\n${variant.answer.trim()}"
        variant.hint?.takeIf { it.isNotBlank() }?.let { parts += "$HINT_HEADER\n${it.trim()}" }
        return parts.joinToString("\n\n")
    }

    private val HEADER = Regex("^#{1,6}\\s+(.+)$")

    // 规范化段名（与标头级数无关：# 题干 / ## 题干 等价）
    private const val SECTION_QUESTION = "题干"
    private const val SECTION_ANSWER = "答案"
    private const val SECTION_HINT = "提示"

    /** 解析 变式N.md → Variant（答案可展开列表展示用）；无题干段 → null */
    fun parse(markdown: String): Variant? {
        if (markdown.isBlank()) return null
        var section = ""
        val question = StringBuilder()
        val answer = StringBuilder()
        val hint = StringBuilder()
        for (line in markdown.replace("\r\n", "\n").split("\n")) {
            val header = HEADER.find(line.trim())?.groupValues?.get(1)?.trim() ?: run {
                when (section) {
                    SECTION_QUESTION -> question.append(line).append('\n')
                    SECTION_ANSWER -> answer.append(line).append('\n')
                    SECTION_HINT -> hint.append(line).append('\n')
                }
                continue
            }
            section = header
        }
        val q = question.toString().trim()
        if (q.isEmpty()) return null
        val a = answer.toString().trim()
        val h = hint.toString().trim()
        return Variant(
            title = titleOf(q),
            question = q,
            answer = a,
            hint = h.takeIf { it.isNotEmpty() }
        )
    }
}
