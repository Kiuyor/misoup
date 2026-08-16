package com.misoup.app.output

import com.misoup.app.output.csv.CsvExporter
import com.misoup.app.output.markdown.MarkdownExporter

/**
 * 导出统一入口（模块 08 定稿签名）：
 * ```
 * object Exporter {
 *   fun toMarkdown(ids: List<String>): String   // 每题: ## 标题 + 题干 + 答案/解析 + ![图](相对路径)
 *   fun toCsv(ids: List<String>): String        // 题干,科目,题型,错因,知识点,创建时间,复习状态
 * }
 * ```
 *
 * 数据经 [ExportDataSource] 注入：生产默认 [StoreExportDataSource]（由 [initSource] 装配），
 * JVM 单测直接传 fake 源（默认参数重载），保持文档签名不变。
 *
 * 容错：缺失/损坏条目跳过（不崩、不占位）。
 */
object Exporter {

    @Volatile
    private var source: ExportDataSource? = null

    /** 装配生产数据源（AppGraph 初始化后调用一次） */
    fun initSource(dataSource: ExportDataSource) {
        source = dataSource
    }

    /** 测试/重置用 */
    fun resetSource() {
        source = null
    }

    private fun currentSource(): ExportDataSource =
        source ?: throw IllegalStateException("Exporter 未装配数据源：请先调用 initSource 或传入 source 参数")

    /** 文档签名：Markdown 导出（生产数据源） */
    fun toMarkdown(ids: List<String>): String = toMarkdown(ids, currentSource())

    /** 注入数据源版本（JVM 测试用） */
    fun toMarkdown(ids: List<String>, source: ExportDataSource): String =
        MarkdownExporter.toMarkdown(source.getMany(ids))

    /** 文档签名：CSV 导出（生产数据源） */
    fun toCsv(ids: List<String>): String = toCsv(ids, currentSource())

    /** 注入数据源版本（JVM 测试用） */
    fun toCsv(ids: List<String>, source: ExportDataSource): String =
        CsvExporter.toCsv(source.getMany(ids))
}
