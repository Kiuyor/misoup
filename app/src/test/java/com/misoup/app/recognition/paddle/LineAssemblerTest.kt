package com.misoup.app.recognition.paddle

import org.junit.Assert.assertEquals
import org.junit.Test

/** T05 行排序（阅读顺序：y 分行、组内 x 排序）与 OcrResult 组装 */
class LineAssemblerTest {

    private fun line(text: String, l: Float, t: Float, r: Float, b: Float) =
        OcrLine(text, Box(l, t, r, b))

    @Test
    fun `多行乱序输入-按阅读顺序重排`() {
        val lines = listOf(
            line("第二行", 0f, 100f, 100f, 120f),
            line("第一行右", 120f, 0f, 220f, 20f),
            line("第一行左", 0f, 0f, 100f, 20f),
        )
        val ordered = LineAssembler.sortByReadingOrder(lines)
        assertEquals(listOf("第一行左", "第一行右", "第二行"), ordered.map { it.text })
    }

    @Test
    fun `同行y有容差-仍归同一行-组内按x排序`() {
        // 两行 y 区间有 10px 交叠（容差内）→ 同一行，按 x 排序
        val lines = listOf(
            line("右", 200f, 0f, 300f, 20f),
            line("左", 0f, 8f, 100f, 28f),
        )
        val ordered = LineAssembler.sortByReadingOrder(lines, yTolerance = 10f)
        assertEquals(listOf("左", "右"), ordered.map { it.text })
    }

    @Test
    fun `单行-原样返回`() {
        val lines = listOf(line("唯一", 0f, 0f, 10f, 10f))
        assertEquals(lines, LineAssembler.sortByReadingOrder(lines))
        assertEquals(emptyList<OcrLine>(), LineAssembler.sortByReadingOrder(emptyList()))
    }

    @Test
    fun `y差巨大-分行处理`() {
        val lines = listOf(
            line("上", 0f, 0f, 50f, 10f),
            line("下", 0f, 500f, 50f, 510f),
        )
        val ordered = LineAssembler.sortByReadingOrder(lines)
        assertEquals(listOf("上", "下"), ordered.map { it.text })
    }

    @Test
    fun `joinText-保留换行拼接`() {
        val lines = listOf(line("a", 0f, 0f, 1f, 1f), line("b", 0f, 0f, 1f, 1f), line("c", 0f, 0f, 1f, 1f))
        assertEquals("a\nb\nc", LineAssembler.joinText(lines))
        assertEquals("", LineAssembler.joinText(emptyList()))
    }

    @Test
    fun `assemble-排序加拼接加耗时`() {
        val lines = listOf(
            line("第二", 0f, 100f, 50f, 110f),
            line("第一", 0f, 0f, 50f, 10f),
        )
        val result = LineAssembler.assemble(lines, latencyMs = 1234L)
        assertEquals(listOf("第一", "第二"), result.lines.map { it.text })
        assertEquals("第一\n第二", result.text)
        assertEquals(1234L, result.latencyMs)
        assertEquals(false, result.isEmpty)
    }

    @Test
    fun `空白文本-判空`() {
        val result = LineAssembler.assemble(listOf(line("  ", 0f, 0f, 1f, 1f)), 0L)
        assertEquals(true, result.isEmpty)
    }
}
