package com.misoup.app.output

import com.misoup.app.makeMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文档签名 Exporter.toMarkdown(ids)/toCsv(ids)：数据源注入 + 缺失容错 */
class ExporterTest {

    private class FakeSource(
        private val items: List<ExportItem>,
        private val missing: Set<String> = emptySet()
    ) : ExportDataSource {
        override fun get(id: String): ExportItem? =
            if (id in missing) null else items.firstOrNull { it.meta.id == id }
    }

    private fun item(id: String, subject: String = "数学") = ExportItem(
        meta = makeMeta(id = id, subject = subject),
        text = "## 题干\n题$id",
        imagePath = null
    )

    @Test
    fun `toMarkdown 经数据源取数并聚合`() {
        val source = FakeSource(listOf(item("a"), item("b")))
        val md = Exporter.toMarkdown(listOf("a", "b"), source)
        assertTrue(md.contains("## 1. 题a"))
        assertTrue(md.contains("## 2. 题b"))
    }

    @Test
    fun `toMarkdown 缺失条目跳过不崩`() {
        val source = FakeSource(listOf(item("a")), missing = setOf("gone"))
        val md = Exporter.toMarkdown(listOf("a", "gone", "b"), source)
        assertTrue(md.contains("## 1. 题a"))
        assertTrue(!md.contains("题gone") || !md.contains("## 2."))
        // 只有 1 题被渲染
        assertEquals(1, Regex("## \\d+\\.").findAll(md).count())
    }

    @Test
    fun `toCsv 经数据源取数并聚合`() {
        val source = FakeSource(listOf(item("a"), item("b", subject = "物理")))
        val csv = Exporter.toCsv(listOf("a", "b"), source)
        assertTrue(csv.startsWith(CsvBom()))
        assertTrue(csv.contains("数学"))
        assertTrue(csv.contains("物理"))
    }

    @Test
    fun `toCsv 空选择仅表头`() {
        val source = FakeSource(emptyList())
        val csv = Exporter.toCsv(emptyList(), source)
        assertTrue(csv.contains("题干,科目,题型,错因,知识点,创建时间,复习状态"))
    }

    @Test
    fun `未装配数据源时文档签名抛明确错误`() {
        Exporter.resetSource()
        try {
            Exporter.toMarkdown(listOf("a"))
            throw AssertionError("应当抛 IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("未装配"))
        } finally {
            Exporter.resetSource()
        }
    }

    @Test
    fun `initSource 后文档签名可用`() {
        Exporter.initSource(FakeSource(listOf(item("a"))))
        try {
            val md = Exporter.toMarkdown(listOf("a"))
            assertTrue(md.contains("## 1. 题a"))
        } finally {
            Exporter.resetSource()
        }
    }

    /** BOM 常量（避免依赖 csv 包内部实现做交叉验证） */
    private fun CsvBom(): String = "\uFEFF"
}
