package com.misoup.app.pipeline.compress

import com.misoup.app.data.store.ImageScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T06 Compressor 纯决策逻辑测试（JVM，不依赖 Android Bitmap）：
 * 降级链状态机（q80 → q60 → 缩边 1600）、300KB 边界、降级标签、缩边判定。
 * Bitmap.compress 实际编码（Android 运行时）需模拟器/真机验证。
 */
class CompressorLogicTest {

    private val MAX = CompressorLogic.TARGET_MAX_BYTES // 307200

    // ---------- 状态机：正常路径 ----------

    @Test
    fun `首压q80达标_直接收尾`() {
        assertEquals(CompressorLogic.Step.DONE, CompressorLogic.nextStep(CompressorLogic.Step.INITIAL_Q80, MAX - 1))
    }

    @Test
    fun `恰好300KB_视为达标`() {
        assertEquals(CompressorLogic.Step.DONE, CompressorLogic.nextStep(CompressorLogic.Step.INITIAL_Q80, MAX))
        assertEquals(CompressorLogic.Step.DONE, CompressorLogic.nextStep(CompressorLogic.Step.RETRY_Q60, MAX))
    }

    // ---------- 状态机：降级链 ----------

    @Test
    fun `首压超过300KB_降质量至60`() {
        assertEquals(CompressorLogic.Step.RETRY_Q60, CompressorLogic.nextStep(CompressorLogic.Step.INITIAL_Q80, MAX + 1))
        assertEquals(CompressorLogic.Step.RETRY_Q60, CompressorLogic.nextStep(CompressorLogic.Step.INITIAL_Q80, MAX * 2))
    }

    @Test
    fun `q60后仍超过300KB_长边缩至1600`() {
        assertEquals(CompressorLogic.Step.SCALE_1600, CompressorLogic.nextStep(CompressorLogic.Step.RETRY_Q60, MAX + 1))
    }

    @Test
    fun `缩边后无论大小_收尾不再降级`() {
        // 缩边后仍大 → 接受现状（文档：仍大则缩边，无后续步骤）
        assertEquals(CompressorLogic.Step.DONE, CompressorLogic.nextStep(CompressorLogic.Step.SCALE_1600, MAX * 10))
        assertEquals(CompressorLogic.Step.DONE, CompressorLogic.nextStep(CompressorLogic.Step.SCALE_1600, MAX - 1))
        assertEquals(CompressorLogic.Step.DONE, CompressorLogic.nextStep(CompressorLogic.Step.DONE, MAX + 1))
    }

    @Test
    fun `非法字节数_按达标处理`() {
        assertEquals(CompressorLogic.Step.DONE, CompressorLogic.nextStep(CompressorLogic.Step.INITIAL_Q80, 0))
        assertEquals(CompressorLogic.Step.DONE, CompressorLogic.nextStep(CompressorLogic.Step.INITIAL_Q80, -1))
    }

    // ---------- 降级链完整模拟（与 Compressor 实现路径一致） ----------

    @Test
    fun `完整降级链_全部走降质量与缩边`() {
        // 模拟一张压缩后 400KB 的图：q80 → 超 → q60 → 超 → 缩边 → 收尾
        var step = CompressorLogic.Step.INITIAL_Q80
        val path = mutableListOf(step)
        var bytes = 400 * 1024

        step = CompressorLogic.nextStep(step, bytes)
        path += step                       // RETRY_Q60
        bytes = 350 * 1024
        step = CompressorLogic.nextStep(step, bytes)
        path += step                       // SCALE_1600
        bytes = 280 * 1024                 // 缩边后达标
        step = CompressorLogic.nextStep(step, bytes)
        path += step                       // DONE

        assertEquals(
            listOf(
                CompressorLogic.Step.INITIAL_Q80,
                CompressorLogic.Step.RETRY_Q60,
                CompressorLogic.Step.SCALE_1600,
                CompressorLogic.Step.DONE,
            ),
            path
        )
        assertEquals(listOf("compress_q60", "compress_scale_1600"), CompressorLogic.fallbackTagsTaken(path))
    }

    @Test
    fun `仅降质量_标签只含q60`() {
        val path = listOf(
            CompressorLogic.Step.INITIAL_Q80,
            CompressorLogic.Step.RETRY_Q60,
            CompressorLogic.Step.DONE,
        )
        assertEquals(listOf("compress_q60"), CompressorLogic.fallbackTagsTaken(path))
    }

    @Test
    fun `未降级_标签为空`() {
        assertEquals(emptyList<String>(), CompressorLogic.fallbackTagsTaken(listOf(CompressorLogic.Step.INITIAL_Q80, CompressorLogic.Step.DONE)))
    }

    // ---------- 缩边判定与尺寸（复用 ImageScale 纯逻辑） ----------

    @Test
    fun `长边不超过1600_不缩边`() {
        assertFalse(CompressorLogic.scaleNeeded(1600, 800))
        assertFalse(CompressorLogic.scaleNeeded(100, 50))
        assertFalse(CompressorLogic.scaleNeeded(1599, 1600))
    }

    @Test
    fun `长边超过1600_需要缩边`() {
        assertTrue(CompressorLogic.scaleNeeded(1601, 800))
        assertTrue(CompressorLogic.scaleNeeded(4096, 3072))
        assertTrue(CompressorLogic.scaleNeeded(800, 4000))
    }

    @Test
    fun `非法尺寸_不缩边`() {
        assertFalse(CompressorLogic.scaleNeeded(0, 100))
        assertFalse(CompressorLogic.scaleNeeded(100, 0))
    }

    @Test
    fun `缩边尺寸_长边恰好1600等比`() {
        val scale = ImageScale.scaleForMaxLongEdge(4096, 2048, CompressorLogic.FALLBACK_LONG_EDGE)
        assertEquals(1600 to 800, ImageScale.scaledSize(4096, 2048, scale))
    }

    @Test
    fun `常量与模块01文档一致`() {
        assertEquals(300 * 1024, CompressorLogic.TARGET_MAX_BYTES) // ≤300KB
        assertEquals(80, CompressorLogic.QUALITY_FIRST)            // WEBP 80
        assertEquals(60, CompressorLogic.QUALITY_FALLBACK)         // 降 60
        assertEquals(1600, CompressorLogic.FALLBACK_LONG_EDGE)     // 长边 1600px
    }
}
