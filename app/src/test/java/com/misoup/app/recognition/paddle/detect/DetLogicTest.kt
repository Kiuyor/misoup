package com.misoup.app.recognition.paddle.detect

import com.misoup.app.recognition.paddle.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T03 det 预处理（长边 640 归一化）+ DB 后处理（score map → 原图行框）纯逻辑 */
class DetLogicTest {

    // ---------- DetPreprocess.scaleForLongEdge ----------

    @Test
    fun `长边640-等比缩放-32对齐`() {
        // 1280x640：scale=0.5，缩放 640x320，均已是 32 倍数
        val spec = DetPreprocess.scaleForLongEdge(1280, 640)
        assertEquals(0.5f, spec.scale, 1e-4f)
        assertEquals(640, spec.scaledWidth)
        assertEquals(320, spec.scaledHeight)
        assertEquals(640, spec.width)
        assertEquals(320, spec.height)
        assertEquals(0, spec.padW)
        assertEquals(0, spec.padH)
    }

    @Test
    fun `非对齐尺寸-向上补padding到32`() {
        // 800x700：scale=0.8 → 640x560 → 高补到 576（+16）
        val spec = DetPreprocess.scaleForLongEdge(800, 700)
        assertEquals(0.8f, spec.scale, 1e-4f)
        assertEquals(640, spec.scaledWidth)
        assertEquals(560, spec.scaledHeight)
        assertEquals(640, spec.width)
        assertEquals(576, spec.height)
        assertEquals(0, spec.padW)
        assertEquals(16, spec.padH)
    }

    @Test
    fun `短图-仅长边缩放-不放大短边`() {
        // 640x200：scale=1.0，短边 200 → 高补到 224
        val spec = DetPreprocess.scaleForLongEdge(640, 200)
        assertEquals(1.0f, spec.scale, 1e-4f)
        assertEquals(200, spec.scaledHeight)
        assertEquals(224, spec.height)
        assertEquals(24, spec.padH)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法尺寸-抛异常`() {
        DetPreprocess.scaleForLongEdge(0, 100)
    }

    // ---------- DetPostprocess.scoreMapToBoxes ----------

    private fun spec64(padH: Int = 0) = DetPreprocess.ScaleSpec(
        scale = 1f,
        scaledWidth = 64,
        scaledHeight = 64 - padH,
        width = 64,
        height = 64,
        padW = 0,
        padH = padH,
    )

    /** 64x64 score map，指定区域置 0.8 */
    private fun mapWith(vararg regions: IntArray): FloatArray {
        val map = FloatArray(64 * 64)
        for (r in regions) {
            val x1 = r[0]; val y1 = r[1]; val x2 = r[2]; val y2 = r[3]
            for (y in y1..y2) for (x in x1..x2) map[y * 64 + x] = 0.8f
        }
        return map
    }

    @Test
    fun `单区域-输出原图坐标行框-中心放大`() {
        // 区域 (10,20)-(20,24)：w=11 h=5，中心 (15,22)，expand 1.5 → ±8.25/±3.75
        val boxes = DetPostprocess.scoreMapToBoxes(
            scores = mapWith(intArrayOf(10, 20, 20, 24)),
            mapW = 64, mapH = 64, spec = spec64(),
            origW = 64, origH = 64,
        )
        assertEquals(1, boxes.size)
        val b = boxes[0]
        assertEquals(6.75f, b.left, 1e-3f)
        assertEquals(18.25f, b.top, 1e-3f)
        assertEquals(23.25f, b.right, 1e-3f)
        assertEquals(25.75f, b.bottom, 1e-3f)
    }

    @Test
    fun `多区域-互不粘连-各自成框`() {
        val boxes = DetPostprocess.scoreMapToBoxes(
            scores = mapWith(intArrayOf(10, 20, 20, 24), intArrayOf(30, 40, 36, 42)),
            mapW = 64, mapH = 64, spec = spec64(),
            origW = 64, origH = 64,
        )
        assertEquals(2, boxes.size)
        // 两框上下分离（按 y 排序非本层职责，断言两个中心不同）
        val centers = boxes.map { it.centerY }.sorted()
        assertTrue(centers[1] - centers[0] > 10f)
    }

    @Test
    fun `空白score map-无行框`() {
        val boxes = DetPostprocess.scoreMapToBoxes(
            scores = FloatArray(64 * 64),
            mapW = 64, mapH = 64, spec = spec64(),
            origW = 64, origH = 64,
        )
        assertTrue(boxes.isEmpty())
    }

    @Test
    fun `低置信像素-低于阈值不构成行`() {
        val map = mapWith(intArrayOf(10, 20, 20, 24))
        map[21 * 64 + 15] = 0.1f   // 挖洞：连通性破坏（8邻域非本实现范围，4邻域洞可隔断）
        val boxes = DetPostprocess.scoreMapToBoxes(
            scores = map,
            mapW = 64, mapH = 64, spec = spec64(),
            origW = 64, origH = 64,
        )
        // 4-邻域下被单点洞隔断 → 两个分量，但都来自同一区域；断言总量 > 0 即可
        assertTrue(boxes.isNotEmpty())
    }

    @Test
    fun `padding区域的行-映射回原图坐标-底部夹取`() {
        // 内容区只有 40 行（padH=24），行框落在 padding 区 → 映射后夹取到 origH=40
        val boxes = DetPostprocess.scoreMapToBoxes(
            scores = mapWith(intArrayOf(10, 58, 20, 62)),
            mapW = 64, mapH = 64, spec = spec64(padH = 24),
            origW = 64, origH = 40,
        )
        assertEquals(1, boxes.size)
        val b = boxes[0]
        assertTrue(b.top >= 0f)
        assertTrue(b.bottom <= 40f)          // 夹取不回超出原图
        assertTrue(b.bottom >= 30f)          // 且确实映射到了底部区域
    }

    @Test
    fun `微小区域-面积过滤`() {
        val map = FloatArray(64 * 64)
        map[30 * 64 + 30] = 0.9f   // 单像素：面积 1 < minArea 3
        val boxes = DetPostprocess.scoreMapToBoxes(
            scores = map,
            mapW = 64, mapH = 64, spec = spec64(),
            origW = 64, origH = 64,
        )
        assertTrue(boxes.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `score map尺寸不符-抛异常`() {
        DetPostprocess.scoreMapToBoxes(
            scores = FloatArray(10),
            mapW = 64, mapH = 64, spec = spec64(),
            origW = 64, origH = 64,
        )
    }
}
