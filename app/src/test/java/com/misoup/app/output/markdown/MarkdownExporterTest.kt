package com.misoup.app.output.markdown

import com.misoup.app.makeMeta
import com.misoup.app.output.ExportItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 父任务书 T04 / tasks T05：Markdown 结构 — ## 标题/题干/答案/解析/图引用相对路径/多题聚合 */
class MarkdownExporterTest {

    // 注：测试字符串中 LaTeX 反斜杠统一用 \\ 双反斜杠字面量（Kotlin 中 \f/\r/\t 为转义序列，
    // 单反斜杠 \frac 会被解析成 form feed，必须避免）
    private val LATEX_TEXT = "## 题干\n已知 \$y=x^2+1\$，求最值。\n\n## 答案\n\$x=\\pm 1\$\n\n## 解析\n利用单调性。"

    private fun item(
        id: String,
        original: String = "原图.webp",
        text: String = LATEX_TEXT,
        subject: String = "数学"
    ) = ExportItem(
        meta = makeMeta(id = id, subject = subject, images = com.misoup.app.data.model.Images(original = original)),
        text = text,
        imagePath = "D:/fake/$original"
    )

    @Test
    fun `整份文档以一级标题开头`() {
        val md = MarkdownExporter.toMarkdown(listOf(item("a")))
        assertTrue(md.startsWith("# 错题汤导出\n"))
    }

    @Test
    fun `每题渲染为二级标题 序号加摘要`() {
        val md = MarkdownExporter.toMarkdown(listOf(item("a")))
        assertTrue(md.contains("## 1. 已知 \$y=x^2+1\$，求最值。"))
    }

    @Test
    fun `摘要取题干首行去 Markdown 符号并截断 20 字`() {
        val summary = MarkdownExporter.summaryOf("**重点**题目内容一二三四五六七八九十甲乙丙丁戊", 3)
        assertEquals(20, summary.length)
        assertFalse(summary.contains("*"))
        val fallback = MarkdownExporter.summaryOf("   \n  ", 5)
        assertEquals("题目 5", fallback)
    }

    @Test
    fun `题干原样保留含 LaTeX`() {
        val md = MarkdownExporter.renderQuestion(1, item("a"))
        assertTrue(md.contains("已知 \$y=x^2+1\$，求最值。"))
    }

    @Test
    fun `LaTeX 反斜杠序列原样保留`() {
        // 双反斜杠字面量：Kotlin 源码 \\frac → 运行时 \frac
        val md = MarkdownExporter.renderQuestion(1, item("a", text = "## 题干\n求 \\frac{1}{x} 的值"))
        assertTrue(md.contains("求 \\frac{1}{x} 的值"))
    }

    @Test
    fun `图片引用为相对路径文件名`() {
        val md = MarkdownExporter.renderQuestion(1, item("a", original = "原图.webp"))
        assertTrue(md.contains("![原图.webp](原图.webp)"))
    }

    @Test
    fun `图片文件名含空格括号时链接转义`() {
        val md = MarkdownExporter.renderQuestion(1, item("a", original = "图 1(副本).webp"))
        assertTrue(md.contains("![图 1（副本）.webp](图%201%28副本%29.webp)"))
    }

    @Test
    fun `无图片时不输出图片行`() {
        val md = MarkdownExporter.renderQuestion(1, item("a", original = ""))
        assertFalse(md.contains("!["))
    }

    @Test
    fun `答案解析分节输出`() {
        val md = MarkdownExporter.renderQuestion(1, item("a"))
        assertTrue(md.contains("**答案**\n\n\$x=\\pm 1\$"))
        assertTrue(md.contains("**解析**\n\n利用单调性。"))
    }

    @Test
    fun `缺失段落不输出对应分节`() {
        val md = MarkdownExporter.renderQuestion(1, item("a", text = "## 题干\n只有题干"))
        assertFalse(md.contains("**答案**"))
        assertFalse(md.contains("**解析**"))
    }

    @Test
    fun `无标头纯文本视为题干`() {
        val md = MarkdownExporter.renderQuestion(1, item("a", text = "纯文本题干"))
        assertTrue(md.contains("纯文本题干"))
        assertTrue(md.contains("## 1. 纯文本题干"))
    }

    @Test
    fun `多题聚合按顺序编号`() {
        val md = MarkdownExporter.toMarkdown(listOf(item("a"), item("b")))
        assertTrue(md.indexOf("## 1.") < md.indexOf("## 2."))
        assertEquals(2, Regex("## \\d+\\.").findAll(md).count())
    }
}
