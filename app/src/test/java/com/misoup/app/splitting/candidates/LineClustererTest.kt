package com.misoup.app.splitting.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineClustererTest {

    private fun line(y: Float, h: Float = 20f, x: Float = 0f, text: String = "") =
        TextLine(y = y, height = h, x = x, width = 100f, text = text)

    @Test
    fun `empty input yields no blocks`() {
        assertEquals(emptyList<LineBlock>(), LineClusterer.cluster(emptyList()))
    }

    @Test
    fun `lines with gap below threshold merge into one block`() {
        // y=0..20, y=25..45 → gap=5 < 10 → 同一块
        val blocks = LineClusterer.cluster(listOf(line(0f), line(25f)), gapThreshold = 10f)
        assertEquals(1, blocks.size)
        assertEquals(2, blocks[0].lines.size)
        assertEquals(0f, blocks[0].top, 1e-6f)
        assertEquals(45f, blocks[0].bottom, 1e-6f)
    }

    @Test
    fun `lines with gap equal to threshold do NOT merge`() {
        // gap=10 不 < 10 → 两块（严格小于边界）
        val blocks = LineClusterer.cluster(listOf(line(0f), line(30f)), gapThreshold = 10f)
        assertEquals(2, blocks.size)
    }

    @Test
    fun `lines with gap above threshold stay separate`() {
        val blocks = LineClusterer.cluster(listOf(line(0f), line(50f)), gapThreshold = 10f)
        assertEquals(2, blocks.size)
        assertEquals(1, blocks[0].lines.size)
        assertEquals(1, blocks[1].lines.size)
    }

    @Test
    fun `overlapping lines always merge even with small threshold`() {
        // y=10..30 与 y=20..40 重叠 → gap=-10 < 任意正阈值
        val blocks = LineClusterer.cluster(
            listOf(line(10f, h = 20f), line(20f, h = 20f)),
            gapThreshold = 1f
        )
        assertEquals(1, blocks.size)
    }

    @Test
    fun `unsorted input is sorted by top before clustering`() {
        val blocks = LineClusterer.cluster(
            listOf(line(50f), line(0f), line(55f)),
            gapThreshold = 10f
        )
        // 0 与 50 分开；50 与 55 gap=5 → 同块
        assertEquals(2, blocks.size)
        assertEquals(2, blocks[1].lines.size)
    }

    @Test
    fun `non-positive threshold keeps every line separate`() {
        val blocks = LineClusterer.cluster(listOf(line(0f), line(5f)), gapThreshold = 0f)
        assertEquals(2, blocks.size)
    }

    @Test
    fun `block text joins line texts with space`() {
        val blocks = LineClusterer.cluster(
            listOf(line(0f, text = "1."), line(15f, text = "下列选项正确")),
            gapThreshold = 10f
        )
        assertEquals("1. 下列选项正确", blocks.single().text)
    }

    @Test
    fun `block bounds are union of member lines`() {
        val blocks = LineClusterer.cluster(
            listOf(line(0f, h = 20f, x = 10f), line(15f, h = 20f, x = 200f)),
            gapThreshold = 10f
        )
        val b = blocks.single()
        assertEquals(10f, b.left, 1e-6f)
        assertEquals(300f, b.right, 1e-6f)
        assertEquals(35f, b.bottom, 1e-6f)
    }

    @Test
    fun `three consecutive close lines chain into one block`() {
        val blocks = LineClusterer.cluster(
            listOf(line(0f), line(8f), line(16f)),
            gapThreshold = 10f
        )
        assertTrue(blocks.size == 1 && blocks[0].lines.size == 3)
    }
}
