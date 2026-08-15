package com.misoup.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T05 文本.md 分段格式：序列化/解析往返，缺段容错，LaTeX 保真 */
class LibraryTextFormatTest {

    @Test
    fun `三段完整-往返一致`() {
        val text = QuestionText(
            question = "求 \$x^2\$ 的最小值",
            answer = "\$0\$",
            explanation = "配方得 \$x^2 \\geq 0\$"
        )
        val md = LibraryTextFormat.toMarkdown(text)
        val parsed = LibraryTextFormat.parse(md)
        assertEquals(text, parsed)
    }

    @Test
    fun `解析-缺答案段-题干答案正常`() {
        val md = "## 题干\n题目内容\n\n## 解析\n解析内容"
        val parsed = LibraryTextFormat.parse(md)
        assertEquals("题目内容", parsed.question)
        assertEquals("", parsed.answer)
        assertEquals("解析内容", parsed.explanation)
    }

    @Test
    fun `解析-无标头-整段视为题干`() {
        val raw = "纯文本题干\n第二行"
        val parsed = LibraryTextFormat.parse(raw)
        assertEquals(raw.trim(), parsed.question)
        assertEquals("", parsed.answer)
        assertEquals("", parsed.explanation)
    }

    @Test
    fun `解析-空输入-返回空结构`() {
        assertEquals(QuestionText(), LibraryTextFormat.parse(""))
        assertEquals(QuestionText(), LibraryTextFormat.parse("   \n  "))
    }

    @Test
    fun `序列化-空段不输出-全空返回空串`() {
        val md = LibraryTextFormat.toMarkdown(QuestionText(question = "只有题干"))
        assertEquals("## 题干\n只有题干", md)
        assertEquals("", LibraryTextFormat.toMarkdown(QuestionText()))
    }

    @Test
    fun `解析-未知标头内容并入题干不丢信息`() {
        val md = "## 题干\n题干\n\n## 备注\n附加信息"
        val parsed = LibraryTextFormat.parse(md)
        assertTrue(parsed.question.contains("题干"))
        assertTrue(parsed.question.contains("附加信息"))
    }

    @Test
    fun `解析-标头级数容错-任意级标题可识别`() {
        val md = "# 题干\nQ\n\n## 答案\nA\n\n### 解析\nE"
        val parsed = LibraryTextFormat.parse(md)
        assertEquals("Q", parsed.question)
        assertEquals("A", parsed.answer)
        assertEquals("E", parsed.explanation)
    }

    @Test
    fun `解析-正文内 LaTeX 换行内容保真`() {
        val md = "## 题干\n公式:\n\$\\frac{1}{2}\$\n\n## 答案\nB"
        val parsed = LibraryTextFormat.parse(md)
        assertTrue(parsed.question.contains("\\frac{1}{2}"))
        assertEquals("B", parsed.answer)
    }

    @Test
    fun `isEmpty 判定`() {
        assertTrue(QuestionText().isEmpty)
        assertTrue(!QuestionText(question = "x").isEmpty)
    }
}
