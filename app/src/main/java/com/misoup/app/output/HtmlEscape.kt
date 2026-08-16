package com.misoup.app.output

/**
 * HTML 转义工具（PDF 模板注入题干/答案/解析文本前必须转义，防 <script> 注入与标签破坏）。
 * 纯 JVM 可测。
 */
object HtmlEscape {

    /**
     * 转义 HTML 特殊字符：& < > " '。
     * 注意：保留 $LaTeX$ 语法（$ \frac 等不在此列），KaTeX 渲染不受影响。
     */
    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
