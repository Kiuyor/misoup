package com.misoup.app.output.pdf

import com.misoup.app.output.ExportItem
import com.misoup.app.output.HtmlEscape
import com.misoup.app.ui.library.LibraryTextFormat
import java.time.LocalDate

/**
 * PDF 模板渲染（父任务书 T01 模板 / T03 渲染的纯逻辑部分）。
 *
 * 两种模板（模块 08 定稿）：
 * - [PaperMode.REDO] 重做卷：无答案/解析输出（HTML 层面即不含，双保险防 CSS 失效泄露），
 *   每题留 60mm 作答区（.answer-space）
 * - [PaperMode.ANSWER] 答案卷：含答案+解析，答案区浅色底（CSS .mode-answer）
 *
 * 渲染链：题目 → [buildQuestionHtml] 片段 → 模板 token 替换（__TITLE__/__SUBJECT__/
 * __DATE__/__COUNT__/__MODE_CLASS__/__QUESTIONS_HTML__）。
 * 模板文件：assets/templates/paper.html + paper.css（T01 交付物）。
 *
 * 纯 JVM 可测（无 Android 依赖；模板文本由调用方从 assets 读取传入）。
 */
enum class PaperMode(val htmlClass: String, val displayName: String) {
    REDO("mode-redo", "重做卷"),
    ANSWER("mode-answer", "答案卷")
}

object PdfTemplate {

    /** 模板 token（与 assets/templates/paper.html 一一对应） */
    const val TOKEN_TITLE = "__TITLE__"
    const val TOKEN_SUBJECT = "__SUBJECT__"
    const val TOKEN_DATE = "__DATE__"
    const val TOKEN_COUNT = "__COUNT__"
    const val TOKEN_MODE_CLASS = "__MODE_CLASS__"
    const val TOKEN_QUESTIONS = "__QUESTIONS_HTML__"

    /** 默认日期（A4 头显示） */
    fun defaultDate(): String = LocalDate.now().toString()

    /**
     * 渲染完整 HTML 文档：模板 token 替换 + 题目片段注入。
     * @param template assets/templates/paper.html 原文
     */
    fun render(
        template: String,
        questionsHtml: String,
        title: String,
        subject: String,
        date: String,
        count: Int,
        mode: PaperMode
    ): String = template
        .replace(TOKEN_TITLE, HtmlEscape.escape(title))
        .replace(TOKEN_SUBJECT, HtmlEscape.escape(subject))
        .replace(TOKEN_DATE, HtmlEscape.escape(date))
        .replace(TOKEN_COUNT, count.toString())
        .replace(TOKEN_MODE_CLASS, mode.htmlClass)
        .replace(TOKEN_QUESTIONS, questionsHtml)

    /**
     * 渲染单题卡片 HTML（不含包裹 section 之外的骨架）。
     *
     * 结构：题号 + 题型/标签头 → 题干（HTML 转义，公式 $...$ 保留）→ 图片（file:// 绝对路径）→
     * REDO: 60mm 作答区；ANSWER: 答案（浅色底）+ 解析。
     */
    fun buildQuestionHtml(no: Int, item: ExportItem, mode: PaperMode): String {
        val text = LibraryTextFormat.parse(item.text)
        val sb = StringBuilder(512)
        sb.append("<section class=\"question\">\n")
        sb.append("  <div class=\"q-head\">\n")
        sb.append("    <span class=\"q-no\">$no.</span>\n")
        sb.append("    <span class=\"q-meta\">${HtmlEscape.escape(item.meta.questionType)}")
        item.meta.tags.take(3).forEach { tag ->
            sb.append(" <span class=\"tag\">${HtmlEscape.escape(tag)}</span>")
        }
        sb.append("</span>\n  </div>\n")
        sb.append("  <div class=\"q-body\">\n")
        if (text.question.isNotBlank()) {
            sb.append("    <div class=\"q-stem\">${HtmlEscape.escape(text.question)}</div>\n")
        }
        val imagePath = item.imagePath?.takeIf { it.isNotBlank() }
        if (imagePath != null) {
            sb.append("    <div class=\"q-images\"><img src=\"file://${escapePath(imagePath)}\" alt=\"题干图\"></div>\n")
        }
        when (mode) {
            PaperMode.REDO -> {
                // 重做卷：无答案/解析，留作答区（60mm，CSS .answer-space）
                sb.append("    <div class=\"answer-space\"></div>\n")
            }
            PaperMode.ANSWER -> {
                if (text.answer.isNotBlank()) {
                    sb.append("    <div class=\"q-answer\"><span class=\"q-answer-label\">答案</span>${HtmlEscape.escape(text.answer)}</div>\n")
                }
                if (text.explanation.isNotBlank()) {
                    sb.append("    <div class=\"q-analysis\"><span class=\"q-analysis-label\">解析</span>${HtmlEscape.escape(text.explanation)}</div>\n")
                }
            }
        }
        sb.append("  </div>\n</section>\n")
        return sb.toString()
    }

    /** 批量渲染题目片段 */
    fun buildQuestionsHtml(items: List<ExportItem>, mode: PaperMode): String =
        items.mapIndexed { index, item -> buildQuestionHtml(index + 1, item, mode) }
            .joinToString("\n")

    /** file:// URL 路径净化：Windows/Unix 反斜杠统一为正斜杠，路径含空格需编码 */
    fun escapePath(absolutePath: String): String =
        absolutePath.replace('\\', '/').replace(" ", "%20")
}
