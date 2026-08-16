package com.misoup.app.recognition.paddle.rec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T04 rec 输入尺寸 + cls 方向判定（纯逻辑） */
class RecClsLogicTest {

    // ---------- RecPreprocess.resizeDims ----------

    @Test
    fun `行图等比缩放-高48-宽按比例`() {
        assertEquals(RecPreprocess.ResizeSpec(96, 48), RecPreprocess.resizeDims(100, 50))
        assertEquals(RecPreprocess.ResizeSpec(48, 48), RecPreprocess.resizeDims(50, 50))
        assertEquals(RecPreprocess.ResizeSpec(5, 48), RecPreprocess.resizeDims(10, 100))
    }

    @Test
    fun `超宽行-宽度封顶320`() {
        // 200x20 → 48*200/20 = 480 → 封顶 320
        assertEquals(RecPreprocess.ResizeSpec(320, 48), RecPreprocess.resizeDims(200, 20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法行尺寸-抛异常`() {
        RecPreprocess.resizeDims(0, 10)
    }

    // ---------- ClsLogic ----------

    @Test
    fun `180度高置信-旋转180`() {
        assertEquals(ClsLogic.ROTATE_180, ClsLogic.rotationDegrees(floatArrayOf(0.05f, 0.95f)))
    }

    @Test
    fun `180度低置信-不旋转`() {
        assertEquals(ClsLogic.ROTATE_0, ClsLogic.rotationDegrees(floatArrayOf(0.2f, 0.8f)))
        assertEquals(ClsLogic.ROTATE_0, ClsLogic.rotationDegrees(floatArrayOf(0.9f, 0.1f)))
    }

    @Test
    fun `异常输入-按0度处理`() {
        assertEquals(ClsLogic.ROTATE_0, ClsLogic.rotationDegrees(floatArrayOf(1f)))
        assertEquals(ClsLogic.ROTATE_0, ClsLogic.rotationDegrees(floatArrayOf()))
    }

    @Test
    fun `softmax-归一化且保序`() {
        val p = ClsLogic.softmax(floatArrayOf(2f, 1f))
        assertEquals(1f, p[0] + p[1], 1e-4f)
        assertTrue(p[0] > p[1])     // argmax 保持
        assertTrue(p[0] > 0.5f)
    }
}
