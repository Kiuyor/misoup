package com.misoup.app.output

import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.store.StoreService
import kotlinx.coroutines.runBlocking

/**
 * 导出数据源：屏蔽「索引/存储」细节，导出器只依赖本接口取数（JVM 单测注入 fake）。
 *
 * 设计动机：模块 08 要求 `Exporter.toMarkdown(ids)` / `toCsv(ids)` 纯逻辑 JVM 可测，
 * 而生产数据在 StoreService（suspend + 文件系统）。本接口做同步薄适配层：
 * - 生产实现 [StoreExportDataSource] 内部 runBlocking 取数（导出是低频小批量操作，可接受）
 * - 测试实现直接构造 [ExportItem] 列表
 */
interface ExportDataSource {

    /**
     * 按 id 取导出数据；条目缺失/损坏 → null（导出时跳过该题，不崩）。
     * 实现方保证：meta 必填；text 无文本文件时为空串；imagePath 无图时为 null。
     */
    fun get(id: String): ExportItem?

    /** 批量取数（保持传入顺序；缺失项跳过） */
    fun getMany(ids: List<String>): List<ExportItem> = ids.mapNotNull { get(it) }
}

/** 单题导出快照（meta + 文本.md 原文 + 原图绝对路径） */
data class ExportItem(
    val meta: MisoupMeta,
    val text: String = "",
    val imagePath: String? = null
)

/**
 * 生产实现：包装 StoreService（模块 02）。
 * 纯读操作，不持有写锁语义；逐题取数失败按 null 处理（跳过，不崩）。
 */
class StoreExportDataSource(private val store: StoreService) : ExportDataSource {

    override fun get(id: String): ExportItem? = runBlocking {
        val meta = store.readMetaById(id).getOrNull() ?: return@runBlocking null
        val text = store.readTextById(id).getOrNull() ?: ""
        val imagePath = store.imagePathById(id).getOrNull()
        ExportItem(meta = meta, text = text, imagePath = imagePath)
    }
}
