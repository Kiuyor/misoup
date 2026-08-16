package com.misoup.app.splitting.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateBoxGeneratorTest {

    private fun block(top: Float, bottom: Float, left: Float = 0f, right: Float = 100f): LineBlock =
        LineBlock.of(listOf(TextLine(y = top, height = bottom - top, x = left, width = right - left)))

    @Test
    fun `empty blocks or boundaries yield empty`() {
        assertEquals(emptyList<BoundingBox>(), CandidateBoxGenerator.generate(emptyList(), listOf(0)))
        assertEquals(emptyList<BoundingBox>(), CandidateBoxGenerator.generate(listOf(block(0f, 20f)), emptyList()))
    }

    @Test
    fun `single boundary yields one box covering all blocks`() {
        val blocks = listOf(block(0f, 20f), block(25f, 45f), block(50f, 70f))
        val boxes = CandidateBoxGenerator.generate(blocks, listOf(0))
        assertEquals(1, boxes.size)
        val b = boxes[0]
        assertEquals(0f, b.top, 1e-6f)
        assertEquals(70f, b.bottom, 1e-6f)
        assertEquals(100f, b.width, 1e-6f)
    }

    @Test
    fun `two boundaries split into two boxes`() {
        val blocks = listOf(
            block(0f, 20f),      // 题1
            block(25f, 45f),     // 题1
            block(60f, 80f),     // 题2
            block(85f, 100f),    // 题2
        )
        val boxes = CandidateBoxGenerator.generate(blocks, listOf(0, 2))
        assertEquals(2, boxes.size)
        assertEquals(0f, boxes[0].top, 1e-6f)
        assertEquals(45f, boxes[0].bottom, 1e-6f)
        assertEquals(60f, boxes[1].top, 1e-6f)
        assertEquals(100f, boxes[1].bottom, 1e-6f)
    }

    @Test
    fun `preamble blocks before first boundary are excluded`() {
        val blocks = listOf(
            block(0f, 20f),      // 卷头（非题）
            block(30f, 50f),     // 题1
            block(60f, 80f),     // 题1
        )
        val boxes = CandidateBoxGenerator.generate(blocks, listOf(1))
        assertEquals(1, boxes.size)
        assertEquals(30f, boxes[0].top, 1e-6f)
        assertEquals(80f, boxes[0].bottom, 1e-6f)
    }

    @Test
    fun `adjacent blocks merge into one union box`() {
        // 相邻但 x 偏移不同的块 → 外接矩形包含两者
        val blocks = listOf(
            block(0f, 20f, left = 0f, right = 50f),
            block(5f, 25f, left = 200f, right = 300f),
        )
        val boxes = CandidateBoxGenerator.generate(blocks, listOf(0))
        val b = boxes.single()
        assertEquals(0f, b.left, 1e-6f)
        assertEquals(300f, b.right, 1e-6f)
        assertEquals(0f, b.top, 1e-6f)
        assertEquals(25f, b.bottom, 1e-6f)
    }

    @Test
    fun `boundary beyond block list is skipped safely`() {
        val blocks = listOf(block(0f, 20f))
        val boxes = CandidateBoxGenerator.generate(blocks, listOf(5))
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun `three questions yield three boxes in order`() {
        // 块 i: top=i*30, bottom=i*30+20 → 三段 [0,1,2] [3,4,5] [6,7,8]
        val blocks = (0 until 9).map { i -> block(i * 30f, i * 30f + 20f) }
        val boxes = CandidateBoxGenerator.generate(blocks, listOf(0, 3, 6))
        assertEquals(3, boxes.size)
        assertEquals(0f, boxes[0].top, 1e-6f)
        assertEquals(80f, boxes[0].bottom, 1e-6f)
        assertEquals(90f, boxes[1].top, 1e-6f)
        assertEquals(170f, boxes[1].bottom, 1e-6f)
        assertEquals(180f, boxes[2].top, 1e-6f)
        assertEquals(260f, boxes[2].bottom, 1e-6f)
    }
}
