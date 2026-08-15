package com.misoup.app.pipeline.enhance

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T05 Enhancer 纯逻辑测试（JVM，不依赖 OpenCV）：
 * 背景光照估计核尺寸计算 + 文档常量一致性。
 * CLAHE 实际应用（OpenCV JNI）需模拟器/真机验证。
 */
class EnhancerLogicTest {

    @Test
    fun `小图使用最小核31`() {
        assertEquals(31, EnhancerLogic.illuminationKernelSize(200, 100))
        assertEquals(31, EnhancerLogic.illuminationKernelSize(64, 64))
        assertEquals(31, EnhancerLogic.illuminationKernelSize(495, 300)) // 495/16=30 → 升 31
    }

    @Test
    fun `偶数商向上取奇数`() {
        // 800/16 = 50（偶数）→ 51
        assertEquals(51, EnhancerLogic.illuminationKernelSize(800, 600))
        // 10000/16 = 625（奇数）→ 625
        assertEquals(625, EnhancerLogic.illuminationKernelSize(10000, 5000))
    }

    @Test
    fun `大图核随长边增长_始终奇数`() {
        // 4096/16 = 256（偶数）→ 257
        val kernel = EnhancerLogic.illuminationKernelSize(4096, 2048)
        assertEquals(257, kernel)
        assertEquals(1, kernel % 2) // 必须奇数（高斯核要求）
    }

    @Test
    fun `非法输入返回最小核_不除零`() {
        assertEquals(31, EnhancerLogic.illuminationKernelSize(0, 100))
        assertEquals(31, EnhancerLogic.illuminationKernelSize(100, 0))
        assertEquals(31, EnhancerLogic.illuminationKernelSize(-5, 100))
        assertEquals(31, EnhancerLogic.illuminationKernelSize(0, 0))
    }

    @Test
    fun `常量与模块01文档一致`() {
        assertEquals(2.0, EnhancerLogic.CLAHE_CLIP_LIMIT, 1e-9) // clipLimit=2.0
        assertEquals(8, EnhancerLogic.CLAHE_TILE_SIZE)           // tile=8x8
    }
}
