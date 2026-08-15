package com.misoup.app.pipeline

import android.graphics.Bitmap
import com.misoup.app.pipeline.compress.Compressor
import com.misoup.app.pipeline.compress.CompressorResult
import com.misoup.app.pipeline.enhance.EnhanceResult
import com.misoup.app.pipeline.enhance.Enhancer
import com.misoup.app.pipeline.rectify.Rectifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T07 PreprocessPipeline 编排逻辑测试（JVM）：
 * 三步骤顺序执行、每步独立失败降级、usedFallback 记录顺序。
 * 组件以桩函数注入（[PreprocessPipeline.orchestrate] 泛型 T=String），
 * 真实组件（OpenCV / Bitmap.compress）需模拟器/真机验证。
 */
class PreprocessPipelineTest {

    /** 记录增强/压缩步骤实际收到的输入（验证矫正失败后用原图继续） */
    private class Spy {
        val enhanceReceived = mutableListOf<String?>()
        val compressReceived = mutableListOf<String?>()
    }

    // ---------- 全部成功 ----------

    @Test
    fun `三步全部成功_无降级_结果透传`() {
        val spy = Spy()
        val result = PreprocessPipeline.orchestrate(
            input = "原图",
            rectify = { it },
            enhance = { spy.enhanceReceived += it; EnhanceResult(it, usedFallback = false) },
            compress = { spy.compressReceived += it; CompressorResult(byteArrayOf(1, 2, 3), emptyList()) },
        )
        assertEquals("原图", result.rectified)
        assertEquals("原图", result.enhanced)
        assertTrue(result.usedFallback.isEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3), result.webpBytes)
        assertEquals(listOf("原图"), spy.enhanceReceived)
        assertEquals(listOf("原图"), spy.compressReceived)
    }

    // ---------- 矫正失败降级 ----------

    @Test
    fun `矫正失败_记录rectify_fallback_画质用原图`() {
        val spy = Spy()
        val result = PreprocessPipeline.orchestrate(
            input = "原图",
            rectify = { null },
            enhance = { spy.enhanceReceived += it; EnhanceResult(it, usedFallback = false) },
            compress = { spy.compressReceived += it; CompressorResult(byteArrayOf(1, 2, 3), emptyList()) },
        )
        assertNull(result.rectified)
        assertEquals(listOf("rectify_fallback"), result.usedFallback)
        // 矫正失败 → 原图喂给画质
        assertEquals(listOf("原图"), spy.enhanceReceived)
    }

    @Test
    fun `矫正抛异常_同样降级为rectify_fallback`() {
        val result = PreprocessPipeline.orchestrate(
            input = "原图",
            rectify = { throw IllegalStateException("JNI 不可用") },
            enhance = { EnhanceResult(it, usedFallback = false) },
            compress = { CompressorResult(byteArrayOf(1, 2, 3), emptyList()) },
        )
        assertNull(result.rectified)
        assertEquals(listOf("rectify_fallback"), result.usedFallback)
    }

    // ---------- 画质失败降级 ----------

    @Test
    fun `画质失败_记录enhance_fallback`() {
        val result = PreprocessPipeline.orchestrate(
            input = "原图",
            rectify = { it },
            enhance = { EnhanceResult(it, usedFallback = true) },
            compress = { CompressorResult(byteArrayOf(1, 2, 3), emptyList()) },
        )
        assertEquals(listOf("enhance_fallback"), result.usedFallback)
        assertEquals("原图", result.enhanced) // 降级返回原图
    }

    @Test
    fun `画质抛异常_降级并保留原图`() {
        val result = PreprocessPipeline.orchestrate(
            input = "原图",
            rectify = { it },
            enhance = { throw RuntimeException("增强失败") },
            compress = { CompressorResult(byteArrayOf(1, 2, 3), emptyList()) },
        )
        assertEquals(listOf("enhance_fallback"), result.usedFallback)
        assertEquals("原图", result.enhanced)
    }

    // ---------- 压缩失败降级 ----------

    @Test
    fun `压缩降级链标签透传`() {
        val result = PreprocessPipeline.orchestrate(
            input = "原图",
            rectify = { it },
            enhance = { EnhanceResult(it, usedFallback = false) },
            compress = { CompressorResult(byteArrayOf(9), listOf("compress_q60", "compress_scale_1600")) },
        )
        assertEquals(listOf("compress_q60", "compress_scale_1600"), result.usedFallback)
        assertArrayEquals(byteArrayOf(9), result.webpBytes)
    }

    @Test
    fun `压缩抛异常_记录compress_failed_空字节`() {
        val result = PreprocessPipeline.orchestrate(
            input = "原图",
            rectify = { it },
            enhance = { EnhanceResult(it, usedFallback = false) },
            compress = { throw RuntimeException("编码失败") },
        )
        assertEquals(listOf("compress_failed"), result.usedFallback)
        assertEquals(0, result.webpBytes.size)
    }

    // ---------- 多步组合降级 ----------

    @Test
    fun `全部失败_按顺序记录三个降级标签`() {
        val result = PreprocessPipeline.orchestrate(
            input = "原图",
            rectify = { null },
            enhance = { EnhanceResult(it, usedFallback = true) },
            compress = { throw RuntimeException("boom") },
        )
        assertEquals(
            listOf("rectify_fallback", "enhance_fallback", "compress_failed"),
            result.usedFallback,
        )
    }

    @Test
    fun `各步独立_矫正失败不影响画质压缩成功`() {
        val result = PreprocessPipeline.orchestrate(
            input = "原图",
            rectify = { null },
            enhance = { EnhanceResult(it, usedFallback = false) },
            compress = { CompressorResult(byteArrayOf(1, 2, 3), emptyList()) },
        )
        // 只有矫正降级，画质/压缩正常
        assertEquals(listOf("rectify_fallback"), result.usedFallback)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.webpBytes)
        assertEquals("原图", result.enhanced)
    }

    @Test
    fun `画质失败后压缩仍在增强输出上执行`() {
        val spy = Spy()
        val result = PreprocessPipeline.orchestrate(
            input = "原图",
            rectify = { it },
            enhance = { spy.enhanceReceived += it; EnhanceResult(it, usedFallback = true) },
            compress = { spy.compressReceived += it; CompressorResult(byteArrayOf(1, 2, 3), emptyList()) },
        )
        // 画质失败返回原图，压缩仍收到该输出
        assertEquals(listOf("原图"), spy.compressReceived)
        assertEquals("原图", result.enhanced)
        assertEquals(listOf("enhance_fallback"), result.usedFallback)
    }

    // ---------- 真实组件冒烟（JVM 无 OpenCV/JNI，验证全链降级不崩） ----------

    @Test
    fun `真实组件在JVM_整链降级不崩溃`() {
        // JVM 下无法构造真实 Bitmap（android.jar stub），且 OpenCV JNI 不可用。
        // 用 orchestrate<Bitmap?> + 真实组件 + it!!（null → NPE）等价验证：
        // 真实 Rectifier/Enhancer/Compressor 的异常均被编排层捕获 → 三步全部降级、永不抛出。
        // （run() 的完整 Bitmap 路径与 orchestrate 等价，需模拟器/真机验证。）
        val result = PreprocessPipeline.orchestrate<Bitmap?>(
            input = null,
            rectify = { Rectifier.rectify(it!!) },
            enhance = { Enhancer.enhance(it!!) },
            compress = { Compressor.compress(it!!) },
        )
        assertNull(result.rectified)
        assertTrue(result.usedFallback.contains("rectify_fallback"))
        assertTrue(result.usedFallback.contains("enhance_fallback"))
        assertTrue(result.usedFallback.contains("compress_failed"))
        assertEquals(0, result.webpBytes.size)
    }
}
