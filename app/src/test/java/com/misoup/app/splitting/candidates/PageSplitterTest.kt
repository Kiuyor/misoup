package com.misoup.app.splitting.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSplitterTest {

    /** 模拟单栏试卷：卷头 + 3 道题（题号清晰），行距 5px，题间距 40px */
    private fun singleColumnPage(): List<TextLine> = listOf(
        TextLine(y = 0f, height = 20f, x = 0f, width = 200f, text = "数学试卷"),
        // 题 1
        TextLine(y = 40f, height = 15f, x = 0f, width = 300f, text = "1. 计算"),
        TextLine(y = 58f, height = 15f, x = 0f, width = 300f, text = "2x+1"),
        // 题 2
        TextLine(y = 100f, height = 15f, x = 0f, width = 300f, text = "2、求导"),
        TextLine(y = 118f, height = 15f, x = 0f, width = 300f, text = "f(x)=x^2"),
        // 题 3
        TextLine(y = 160f, height = 15f, x = 0f, width = 300f, text = "(3) 证明"),
        TextLine(y = 178f, height = 15f, x = 0f, width = 300f, text = "单调性"),
    )

    @Test
    fun `standard page splits into three boxes`() {
        val outcome = PageSplitter.split(singleColumnPage(), gapThreshold = 10f)
        assertFalse(outcome.unsplittable)
        assertEquals(3, outcome.boxes.size)
    }

    @Test
    fun `each box covers its own question lines`() {
        val outcome = PageSplitter.split(singleColumnPage(), gapThreshold = 10f)
        // 题1 框：40..73；题2 框：100..133；题3 框：160..193
        assertEquals(40f, outcome.boxes[0].top, 1e-6f)
        assertEquals(73f, outcome.boxes[0].bottom, 1e-6f)
        assertEquals(100f, outcome.boxes[1].top, 1e-6f)
        assertEquals(133f, outcome.boxes[1].bottom, 1e-6f)
        assertEquals(160f, outcome.boxes[2].top, 1e-6f)
        assertEquals(193f, outcome.boxes[2].bottom, 1e-6f)
    }

    @Test
    fun `preamble title is not part of first box`() {
        val outcome = PageSplitter.split(singleColumnPage(), gapThreshold = 10f)
        // 卷头 0..20 与题1 40.. 间距 20 > 10 → 独立块；且不在任何分界后 → 被丢弃
        assertFalse(outcome.boxes[0].top < 40f)
    }

    // ---------- T05a：无法拆分判定 ----------

    @Test
    fun `page without question numbers is unsplittable with manual crop hint`() {
        val lines = listOf(
            TextLine(y = 0f, height = 15f, x = 0f, width = 300f, text = "随手拍的一段文字"),
            TextLine(y = 20f, height = 15f, x = 0f, width = 300f, text = "没有题号没有结构"),
        )
        val outcome = PageSplitter.split(lines)
        assertTrue(outcome.unsplittable)
        assertTrue(outcome.boxes.isEmpty())
        assertEquals(PageSplitter.MESSAGE_NO_BOUNDARY, outcome.message)
    }

    @Test
    fun `empty line list is unsplittable with empty hint`() {
        val outcome = PageSplitter.split(emptyList())
        assertTrue(outcome.unsplittable)
        assertEquals(PageSplitter.MESSAGE_EMPTY_LINES, outcome.message)
    }

    @Test
    fun `illustration only page without numbers is unsplittable not crashing`() {
        // 只有插图/表格行（无题号）→ 不崩溃，明确提示
        val lines = listOf(
            TextLine(y = 0f, height = 100f, x = 0f, width = 400f, text = "A. 甲"),
            TextLine(y = 110f, height = 100f, x = 0f, width = 400f, text = "B. 乙"),
        )
        val outcome = PageSplitter.split(lines, gapThreshold = 10f)
        assertTrue(outcome.unsplittable)
        assertEquals(PageSplitter.MESSAGE_NO_BOUNDARY, outcome.message)
    }

    @Test
    fun `question with chinese numeral boundary splits correctly`() {
        val lines = listOf(
            TextLine(y = 0f, height = 15f, x = 0f, width = 300f, text = "一、选择题"),
            TextLine(y = 20f, height = 15f, x = 0f, width = 300f, text = "1. 题目甲"),
            TextLine(y = 50f, height = 15f, x = 0f, width = 300f, text = "2. 题目乙"),
        )
        // 阈值 5：各行独立成块（行距 20/30 > 5）；三处题号分界 → 3 候选框
        val outcome = PageSplitter.split(lines, gapThreshold = 5f)
        assertFalse(outcome.unsplittable)
        assertEquals(3, outcome.boxes.size)
        assertEquals(0f, outcome.boxes[0].top, 1e-6f)
        assertEquals(15f, outcome.boxes[0].bottom, 1e-6f)
        assertEquals(20f, outcome.boxes[1].top, 1e-6f)
        assertEquals(35f, outcome.boxes[1].bottom, 1e-6f)
        assertEquals(50f, outcome.boxes[2].top, 1e-6f)
        assertEquals(65f, outcome.boxes[2].bottom, 1e-6f)
    }

    @Test
    fun `illustration row inside question stays inside its box`() {
        // 插图/表格行（高行）嵌在题 1 与题 2 之间 → 归入题 1 框（分界点之间），不拆出
        val lines = listOf(
            TextLine(y = 0f, height = 15f, x = 0f, width = 300f, text = "1. 题干"),
            TextLine(y = 30f, height = 60f, x = 0f, width = 300f, text = "插图说明"),
            TextLine(y = 100f, height = 15f, x = 0f, width = 300f, text = "2. 下一题"),
        )
        val outcome = PageSplitter.split(lines, gapThreshold = 10f)
        assertFalse(outcome.unsplittable)
        assertEquals(2, outcome.boxes.size)
        // 题1 框包含插图行（union 0..90）；题2 框 100..115
        assertEquals(0f, outcome.boxes[0].top, 1e-6f)
        assertEquals(90f, outcome.boxes[0].bottom, 1e-6f)
        assertEquals(100f, outcome.boxes[1].top, 1e-6f)
        assertEquals(115f, outcome.boxes[1].bottom, 1e-6f)
    }
}
