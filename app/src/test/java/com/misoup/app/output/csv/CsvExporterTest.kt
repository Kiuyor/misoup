package com.misoup.app.output.csv

import com.misoup.app.makeMeta
import com.misoup.app.output.ExportItem
import com.misoup.app.data.model.FsrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T05(父任务书)/T06(tasks)：CSV 纯逻辑 — BOM/引号包裹/转义/列映射/多题聚合/空列表 */
class CsvExporterTest {

    private fun item(
        id: String,
        subject: String = "数学",
        questionType: String = "选择题",
        errors: List<String> = listOf("概念不清"),
        points: List<String> = listOf("二次函数"),
        createdAt: String = "2026-08-16T14:05:23+08:00",
        text: String = "## 题干\n求 \$f(x)=x^2\$ 的最值，答案是, 逗号",
        fsrsState: Int = 0
    ) = ExportItem(
        meta = makeMeta(
            id = id,
            subject = subject,
            questionType = questionType,
            errorReasons = errors,
            knowledgePoints = points,
            createdAt = createdAt,
            fsrs = FsrsState(state = fsrsState)
        ),
        text = text
    )

    @Test
    fun `输出以 UTF-8 BOM 开头`() {
        val csv = CsvExporter.toCsv(listOf(item("a")))
        assertTrue("应以 BOM 开头", csv.startsWith(CsvExporter.BOM))
        assertEquals("\uFEFF", csv.substring(0, 1))
    }

    @Test
    fun `表头列为定稿七列`() {
        val csv = CsvExporter.toCsv(emptyList())
        val header = csv.removePrefix(CsvExporter.BOM).lineSequence().first()
        assertEquals("题干,科目,题型,错因,知识点,创建时间,复习状态", header)
    }

    @Test
    fun `空列表仅 BOM 加表头`() {
        val csv = CsvExporter.toCsv(emptyList())
        // BOM + 表头行（CRLF 结尾）
        assertTrue(csv.startsWith(CsvExporter.BOM))
        assertTrue(csv.endsWith("\r\n"))
        assertEquals("题干,科目,题型,错因,知识点,创建时间,复习状态", csv.removePrefix(CsvExporter.BOM).trimEnd('\r', '\n'))
    }

    @Test
    fun `含逗号字段被引号包裹`() {
        val csv = CsvExporter.toCsv(listOf(item("a")))
        val dataLine = csv.lineSequence().elementAt(1)
        // 题干含逗号 → 该字段必须以引号包裹
        assertTrue(dataLine.contains("\"求 \$f(x)=x^2\$ 的最值，答案是, 逗号\""))
    }

    @Test
    fun `含引号字段双写转义并包裹`() {
        val escaped = CsvExporter.escape("他说\"对\"")
        assertEquals("\"他说\"\"对\"\"\"", escaped)
    }

    @Test
    fun `含换行字段被包裹且保留换行`() {
        val escaped = CsvExporter.escape("第一行\n第二行")
        assertEquals("\"第一行\n第二行\"", escaped)
    }

    @Test
    fun `无特殊字符字段原样输出`() {
        assertEquals("普通文本", CsvExporter.escape("普通文本"))
    }

    @Test
    fun `列映射正确`() {
        val row = CsvExporter.rowOf(item("a"))
        assertEquals(7, row.size)
        assertEquals("求 \$f(x)=x^2\$ 的最值，答案是, 逗号", row[0])   // 题干
        assertEquals("数学", row[1])                                    // 科目
        assertEquals("选择题", row[2])                                  // 题型
        assertEquals("概念不清", row[3])                                // 错因
        assertEquals("二次函数", row[4])                                // 知识点
        assertEquals("2026-08-16T14:05:23+08:00", row[5])              // 创建时间
        assertEquals("新卡", row[6])                                    // 复习状态
    }

    @Test
    fun `复习状态按 fsrs state 映射`() {
        assertEquals("学习中", CsvExporter.rowOf(item("a", fsrsState = 1))[6])
        assertEquals("已掌握", CsvExporter.rowOf(item("a", fsrsState = 2))[6])
        assertEquals("重学", CsvExporter.rowOf(item("a", fsrsState = 3))[6])
        assertEquals("新卡", CsvExporter.rowOf(item("a", fsrsState = 99))[6])  // 未知值兜底
    }

    @Test
    fun `多题聚合每行一条且保持顺序`() {
        val csv = CsvExporter.toCsv(listOf(item("a"), item("b", subject = "物理")))
        val lines = csv.trimEnd('\r', '\n').split("\r\n")
        assertEquals(3, lines.size)   // 表头 + 2 行
        assertTrue(lines[1].contains("数学"))
        assertTrue(lines[2].contains("物理"))
    }

    @Test
    fun `多值错因知识点用顿号连接`() {
        val row = CsvExporter.rowOf(
            item("a", errors = listOf("概念不清", "计算失误"), points = listOf("二次函数", "最值"))
        )
        assertEquals("概念不清、计算失误", row[3])
        assertEquals("二次函数、最值", row[4])
    }

    @Test
    fun `整行编码以 CRLF 结尾`() {
        assertEquals("a,b\r\n", CsvExporter.encodeRow(listOf("a", "b")))
    }
}
