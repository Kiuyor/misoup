package com.misoup.app.pipeline.rectify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T04 Rectifier 纯判定逻辑测试（JVM，不依赖 OpenCV）：
 * 轮廓面积占比 / 顶点数判定、四点排序、常量与文档一致性。
 */
class RectifierLogicTest {

    // ---------- isUsableQuad：面积占比 ----------

    @Test
    fun `面积占比达标且4点_判定可用`() {
        // 恰好 10% 边界（>=10% 可用，文档：<10% 才降级）
        assertTrue(RectifierLogic.isUsableQuad(1000.0, 10000.0, 4))
        assertTrue(RectifierLogic.isUsableQuad(5000.0, 10000.0, 4))
        // 满幅轮廓
        assertTrue(RectifierLogic.isUsableQuad(10000.0, 10000.0, 4))
    }

    @Test
    fun `面积占比低于10_判定不可用_返回null降级`() {
        assertFalse(RectifierLogic.isUsableQuad(999.0, 10000.0, 4))   // 9.99%
        assertFalse(RectifierLogic.isUsableQuad(100.0, 10000.0, 4))   // 1%
        assertFalse(RectifierLogic.isUsableQuad(0.0, 10000.0, 4))     // 0（无轮廓）
    }

    @Test
    fun `点数不是4_判定不可用`() {
        assertFalse(RectifierLogic.isUsableQuad(5000.0, 10000.0, 3))  // 三角形
        assertFalse(RectifierLogic.isUsableQuad(5000.0, 10000.0, 5))  // 五边形
        assertFalse(RectifierLogic.isUsableQuad(5000.0, 10000.0, 0))  // 无顶点
    }

    @Test
    fun `非法画面面积_判定不可用_不除零`() {
        assertFalse(RectifierLogic.isUsableQuad(100.0, 0.0, 4))       // 画面面积为 0
        assertFalse(RectifierLogic.isUsableQuad(100.0, -1.0, 4))      // 负数
        // 轮廓面积超大但画面非法 → false（不产生 NaN 结果）
        assertFalse(RectifierLogic.isUsableQuad(1e9, 0.0, 4))
    }

    @Test
    fun `常量与模块01文档一致`() {
        assertEquals(0.10, RectifierLogic.MIN_CONTOUR_AREA_RATIO, 1e-9) // 画面 10%
        assertEquals(4, RectifierLogic.QUAD_POINT_COUNT)                // 四边
        assertEquals(0.02, RectifierLogic.APPROX_EPSILON_RATIO, 1e-9)   // 近似精度
    }

    // ---------- orderQuadPoints：四点排序 ----------

    @Test
    fun `四点乱序_排序为左上右上右下左下`() {
        // 输入乱序：BR, TL, BL, TR
        val points = listOf(
            10.0 to 10.0,    // TL
            90.0 to 90.0,    // BR
            10.0 to 90.0,    // BL
            90.0 to 10.0,    // TR
        )
        assertEquals(
            listOf(10.0 to 10.0, 90.0 to 10.0, 90.0 to 90.0, 10.0 to 90.0),
            RectifierLogic.orderQuadPoints(points)
        )
    }

    @Test
    fun `四点排序_任意顺序都能归一`() {
        // 旋转/镜像后的顺序也应归一为 TL→TR→BR→BL
        val points = listOf(5.0 to 5.0, 5.0 to 95.0, 95.0 to 5.0, 95.0 to 95.0)
        val ordered = RectifierLogic.orderQuadPoints(points)
        assertEquals(4, ordered.size)
        assertEquals(5.0 to 5.0, ordered[0])    // 左上
        assertEquals(95.0 to 5.0, ordered[1])   // 右上
        assertEquals(95.0 to 95.0, ordered[2])  // 右下
        assertEquals(5.0 to 95.0, ordered[3])   // 左下
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非4点输入_抛异常`() {
        RectifierLogic.orderQuadPoints(listOf(0.0 to 0.0, 1.0 to 1.0, 2.0 to 2.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空输入_抛异常`() {
        RectifierLogic.orderQuadPoints(emptyList())
    }
}
