package com.misoup.app.splitting.llmrefine

import com.misoup.app.splitting.candidates.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRefineCoordinatorTest {

    private val offline = listOf(
        BoundingBox(0f, 0f, 100f, 50f),
        BoundingBox(0f, 60f, 100f, 50f),
        BoundingBox(0f, 120f, 100f, 50f),
    )

    // ---------- §4 降级：无 Key ----------

    @Test
    fun `no key returns offline boxes degraded`() {
        val r = LlmRefineCoordinator.refine(offline, llmJson = "{\"operations\":[{\"op\":\"delete\",\"index\":0}]}", enabled = false)
        assertTrue(r.degraded)
        assertEquals(offline, r.refined)
        assertEquals(LlmRefineCoordinator.MSG_NO_KEY, r.message)
    }

    @Test
    fun `null or blank llm json degrades even when enabled`() {
        val r1 = LlmRefineCoordinator.refine(offline, llmJson = null, enabled = true)
        assertTrue(r1.degraded)
        assertEquals(offline, r1.refined)

        val r2 = LlmRefineCoordinator.refine(offline, llmJson = "   ", enabled = true)
        assertTrue(r2.degraded)
        assertEquals(offline, r2.refined)
    }

    // ---------- §4 降级：坏 JSON ----------

    @Test
    fun `bad json degrades to offline without crashing`() {
        val r = LlmRefineCoordinator.refine(offline, llmJson = "garbage{{{", enabled = true)
        assertTrue(r.degraded)
        assertEquals(offline, r.refined)
        assertEquals(LlmRefineCoordinator.MSG_BAD_JSON, r.message)
    }

    // ---------- 正常路径 ----------

    @Test
    fun `valid ops are applied and not degraded`() {
        val r = LlmRefineCoordinator.refine(
            offline,
            llmJson = "{\"operations\":[{\"op\":\"merge\",\"indices\":[0,1]},{\"op\":\"delete\",\"index\":1}]}",
            enabled = true
        )
        assertFalse(r.degraded)
        // merge [0,1] → union 替换下标 0；原 0、1 移除；再 delete index=1 → 删掉 union 后面的原 2
        assertEquals(1, r.refined.size)
        val merged = r.refined.single()
        assertEquals(0f, merged.top, 1e-6f)
        assertEquals(110f, merged.bottom, 1e-6f)
    }

    @Test
    fun `empty ops list returns offline unchanged not degraded`() {
        val r = LlmRefineCoordinator.refine(offline, llmJson = "{\"operations\":[]}", enabled = true)
        assertFalse(r.degraded)
        assertEquals(offline, r.refined)
    }

    // ---------- RefineOp.apply 语义 ----------

    @Test
    fun `merge unions boxes and inserts at min index`() {
        val boxes = listOf(
            BoundingBox(0f, 0f, 10f, 10f),
            BoundingBox(20f, 20f, 10f, 10f),
            BoundingBox(100f, 100f, 10f, 10f),
        )
        val out = RefineOp.Merge(listOf(0, 2)).apply(boxes)
        assertEquals(2, out.size)
        // union(0,2) 放在 min index=0；原 1 保持
        assertEquals(BoundingBox(0f, 0f, 110f, 110f), out[0])
        assertEquals(boxes[1], out[1])
    }

    @Test
    fun `merge with invalid indices is noop`() {
        val out = RefineOp.Merge(listOf(0, 99)).apply(offline)
        assertEquals(offline, out)
    }

    @Test
    fun `split divides box vertically at midpoint`() {
        val boxes = listOf(BoundingBox(0f, 0f, 100f, 50f))
        val out = RefineOp.Split(0).apply(boxes)
        assertEquals(2, out.size)
        assertEquals(BoundingBox(0f, 0f, 50f, 50f), out[0])
        assertEquals(BoundingBox(50f, 0f, 50f, 50f), out[1])
    }

    @Test
    fun `split out of range is noop`() {
        assertEquals(offline, RefineOp.Split(99).apply(offline))
    }

    @Test
    fun `delete removes box`() {
        val out = RefineOp.Delete(1).apply(offline)
        assertEquals(2, out.size)
        assertEquals(offline[0], out[0])
        assertEquals(offline[2], out[1])
    }

    @Test
    fun `delete out of range is noop`() {
        assertEquals(offline, RefineOp.Delete(99).apply(offline))
    }

    @Test
    fun `keep with box replaces bounds`() {
        val newBox = BoundingBox(5f, 5f, 30f, 30f)
        val out = RefineOp.Keep(0, newBox).apply(offline)
        assertEquals(newBox, out[0])
        assertEquals(offline[1], out[1])
    }

    @Test
    fun `keep without box is noop`() {
        assertEquals(offline, RefineOp.Keep(0).apply(offline))
    }

    @Test
    fun `sequential ops compose in order`() {
        // split 0 → delete 新下标 1 → merge [0,2]
        var boxes = RefineOp.Split(0).apply(offline)
        boxes = RefineOp.Delete(1).apply(boxes)
        boxes = RefineOp.Merge(listOf(0, 2)).apply(boxes)
        // split: [左半, 右半, b1, b2] → delete 1: [左半, b1, b2] → merge [0,2]: union(左半,b2) + b1
        assertEquals(2, boxes.size)
    }
}
