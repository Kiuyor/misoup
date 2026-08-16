package com.misoup.app.output.csv

import com.misoup.app.data.model.FsrsCardState
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.output.ExportItem
import com.misoup.app.ui.library.LibraryTextFormat

/**
 * CSV 导出（T06 父任务书 / tasks T05：Markdown、T06：CSV 中 CSV 部分）。
 *
 * 规格（模块 08 定稿）：
 * - 编码 UTF-8 并带 BOM（\uFEFF，Excel 打开中文不乱码）
 * - 列：题干,科目,题型,错因,知识点,创建时间,复习状态
 * - 字段含逗号/引号/换行 → 双引号包裹，内部引号翻倍（RFC 4180）
 * - 行尾 CRLF（Excel 兼容）
 *
 * 纯 JVM 可测（无 Android 依赖）。
 */
object CsvExporter {

    /** UTF-8 BOM（Excel 中文兼容） */
    const val BOM = "\uFEFF"

    /** 表头（列映射定稿） */
    val HEADERS: List<String> = listOf("题干", "科目", "题型", "错因", "知识点", "创建时间", "复习状态")

    /** 空列表时仅 BOM + 表头 */
    fun toCsv(items: List<ExportItem>): String = buildString {
        append(BOM)
        append(encodeRow(HEADERS))
        items.forEach { item ->
            append(encodeRow(rowOf(item)))
        }
    }

    /** 单行数据（列映射） */
    fun rowOf(item: ExportItem): List<String> {
        val meta = item.meta
        val question = LibraryTextFormat.parse(item.text).question
        return listOf(
            question,
            meta.subject,
            meta.questionType,
            meta.errorReasons.joinToString("、"),
            meta.knowledgePoints.joinToString("、"),
            meta.createdAt,
            FsrsCardState.fromCode(meta.fsrs.state).displayName
        )
    }

    /** 编码一行：每字段引号包裹（按需），CRLF 结尾 */
    fun encodeRow(fields: List<String>): String =
        fields.joinToString(",") { escape(it) } + "\r\n"

    /**
     * 字段转义：含逗号/双引号/换行/回车 → 包裹双引号，内部双引号翻倍；
     * 否则原样输出。
     */
    fun escape(field: String): String {
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }
}
