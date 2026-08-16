package com.misoup.app.output.pdf

import com.misoup.app.makeMeta
import com.misoup.app.output.ExportItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 父任务书 T01/T03：PDF 模板渲染 — 两种模式、token 替换、题号、图文、转义 */
class PdfTemplateTest {

    private val template = """
        <html><body class="__MODE_CLASS__">
        <h1>__TITLE__</h1>
        <span>__SUBJECT__ __DATE__ __COUNT__</span>
        __QUESTIONS_HTML__
        </body></html>
    """.trimIndent()

    private fun item(
        id: String,
        imagePath: String? = "D:/fake/原图.webp",
        text: String = "## 题干\n求 \$f(x)=\\frac{x}{2}\$ 的值。\n\n## 答案\n\$x=2\$\n\n## 解析\n代入求解。",
        tags: List<String> = listOf("二次函数")
    ) = ExportItem(
        meta = makeMeta(id = id, tags = tags),
        text = text,
        imagePath = imagePath
    )

    @Test
    fun `重做卷模式类名注入`() {
        val html = PdfTemplate.render(template, "", "错题卷", "数学", "2026-08-16", 0, PaperMode.REDO)
        assertTrue(html.contains("class=\"mode-redo\""))
    }

    @Test
    fun `答案卷模式类名注入`() {
        val html = PdfTemplate.render(template, "", "错题卷", "数学", "2026-08-16", 0, PaperMode.ANSWER)
        assertTrue(html.contains("class=\"mode-answer\""))
    }

    @Test
    fun `token 全部替换为头信息`() {
        val html = PdfTemplate.render(template, "<!--Q-->", "错题卷", "数学", "2026-08-16", 3, PaperMode.REDO)
        assertTrue(html.contains("<h1>错题卷</h1>"))
        assertTrue(html.contains("<span>数学 2026-08-16 3</span>"))
        assertTrue(html.contains("<!--Q-->"))
        assertFalse(html.contains("__TITLE__"))
        assertFalse(html.contains("__QUESTIONS_HTML__"))
    }

    @Test
    fun `题干含特殊字符被 HTML 转义`() {
        val html = PdfTemplate.buildQuestionHtml(1, item("a", text = "## 题干\n1 < 2 && 3 > 2 \"引号\""), PaperMode.ANSWER)
        assertTrue(html.contains("1 &lt; 2 &amp;&amp; 3 &gt; 2 &quot;引号&quot;"))
        assertFalse(html.contains("1 < 2"))
    }

    @Test
    fun `LaTeX 原样保留不被转义`() {
        val html = PdfTemplate.buildQuestionHtml(1, item("a"), PaperMode.ANSWER)
        assertTrue(html.contains("求 \$f(x)=\\frac{x}{2}\$ 的值。"))
    }

    @Test
    fun `重做卷不含答案解析且含作答区`() {
        val html = PdfTemplate.buildQuestionHtml(1, item("a"), PaperMode.REDO)
        assertTrue(html.contains("class=\"answer-space\""))
        assertFalse(html.contains("q-answer"))
        assertFalse(html.contains("q-analysis"))
        assertFalse(html.contains("答案"))
    }

    @Test
    fun `答案卷含答案解析浅色底类且无作答区`() {
        val html = PdfTemplate.buildQuestionHtml(1, item("a"), PaperMode.ANSWER)
        assertTrue(html.contains("class=\"q-answer\""))
        assertTrue(html.contains("class=\"q-analysis\""))
        assertFalse(html.contains("answer-space"))
        // 答案区标签
        assertTrue(html.contains("答案</span>\$x=2\$"))
    }

    @Test
    fun `题号递增`() {
        val items = listOf(item("a"), item("b"), item("c"))
        val html = PdfTemplate.buildQuestionsHtml(items, PaperMode.REDO)
        assertTrue(html.contains("class=\"q-no\">1."))
        assertTrue(html.contains("class=\"q-no\">2."))
        assertTrue(html.contains("class=\"q-no\">3."))
    }

    @Test
    fun `图片以 file 协议绝对路径引用`() {
        val html = PdfTemplate.buildQuestionHtml(1, item("a", imagePath = "D:/fake/原图.webp"), PaperMode.REDO)
        assertTrue(html.contains("src=\"file://D:/fake/原图.webp\""))
    }

    @Test
    fun `无图不输出 img 标签`() {
        val html = PdfTemplate.buildQuestionHtml(1, item("a", imagePath = null), PaperMode.REDO)
        assertFalse(html.contains("<img"))
    }

    @Test
    fun `路径反斜杠与空格转义`() {
        // file:// URL 中空格需编码 %20（反斜杠统一为正斜杠）
        assertEquals("C:/Users/a%20b/图.webp", PdfTemplate.escapePath("C:\\Users\\a b\\图.webp"))
    }

    @Test
    fun `题型与标签头展示`() {
        val html = PdfTemplate.buildQuestionHtml(1, item("a"), PaperMode.REDO)
        assertTrue(html.contains("解答题"))
        assertTrue(html.contains("class=\"tag\">二次函数"))
    }

    @Test
    fun `默认日期为当天`() {
        assertEquals(java.time.LocalDate.now().toString(), PdfTemplate.defaultDate())
    }
}
