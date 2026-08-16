package com.misoup.app.output.markdown

import com.misoup.app.output.ExportItem
import com.misoup.app.ui.library.LibraryTextFormat

/**
 * Markdown 导出（父任务书 T04 / tasks T05）。
 *
 * 结构（模块 08 定稿）：每题 `## 标题` + 题干 + 答案/解析 + `![图](相对路径)`。
 * - 标题 = 序号 + 题干首行摘要（≤20 字；无题干时用「题目 N」）
 * - 图片引用 = meta.images.original 文件名（相对 md 所在目录；
 *   单题分享时与图片同发；SAF 单文件导出时图片缺失由查看方自行处理）
 * - 公式 $LaTeX$ 原样保留（KaTeX/Markdown 预览器均可渲染）
 *
 * 纯 JVM 可测（无 Android 依赖）。
 */
object MarkdownExporter {

    /** 标题摘要最大长度 */
    const val SUMMARY_LENGTH = 20

    /**
     * 生成整份 Markdown 文档。
     * @param items 按显示顺序的导出数据（已按 id 顺序去缺失）
     * @param title 文档标题（默认「错题汤导出」）
     */
    fun toMarkdown(items: List<ExportItem>, title: String = "错题汤导出"): String = buildString {
        append("# $title\n\n")
        items.forEachIndexed { index, item ->
            append(renderQuestion(index + 1, item))
            append('\n')
        }
    }

    /** 渲染单题（## 标题 + 题干 + 图片 + 答案/解析），末尾带换行 */
    fun renderQuestion(no: Int, item: ExportItem): String = buildString {
        val text = LibraryTextFormat.parse(item.text)
        val summary = summaryOf(text.question, no)
        append("## $no. $summary\n\n")
        if (text.question.isNotBlank()) {
            append(text.question)
            append("\n\n")
        }
        val imageName = item.meta.images.original.takeIf { it.isNotBlank() }
        if (imageName != null) {
            // 相对路径引用（与 md 同目录）；文件名做 Markdown 链接转义（括号/空格）
            append("![${escapeLinkText(imageName)}](${escapeLink(imageName)})")
            append("\n\n")
        }
        if (text.answer.isNotBlank()) {
            append("**答案**\n\n")
            append(text.answer)
            append("\n\n")
        }
        if (text.explanation.isNotBlank()) {
            append("**解析**\n\n")
            append(text.explanation)
            append("\n\n")
        }
    }

    /** 摘要：题干首行去 Markdown 符号后取前 N 字；空 → 「题目 N」 */
    fun summaryOf(question: String, no: Int): String {
        val firstLine = question.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
        val cleaned = firstLine
            .replace(Regex("^#{1,6}\\s+"), "")
            .replace(Regex("[*_`~>\\[\\]()]"), "")
            .trim()
        return if (cleaned.isBlank()) "题目 $no" else cleaned.take(SUMMARY_LENGTH)
    }

    /** 链接目标转义：空格/括号 → URL 编码，防 Markdown 解析歧义 */
    fun escapeLink(name: String): String =
        name.replace(" ", "%20").replace("(", "%28").replace(")", "%29")

    /** 链接文本转义：方括号/括号 → 全角，防破坏链接语法 */
    fun escapeLinkText(name: String): String =
        name.replace("[", "［").replace("]", "］").replace("(", "（").replace(")", "）")
}
