package com.misoup.app.pipeline

import android.graphics.Bitmap
import com.misoup.app.pipeline.compress.Compressor
import com.misoup.app.pipeline.compress.CompressorResult
import com.misoup.app.pipeline.enhance.EnhanceResult
import com.misoup.app.pipeline.enhance.Enhancer
import com.misoup.app.pipeline.rectify.Rectifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T07 预处理链编排器（PreprocessPipeline）
 *
 * 模块 01 文档定义：矫正 → 画质 → 压缩 按顺序执行，每步可独立失败降级；
 * 全程 `Dispatchers.Default`，单题预算 ≤2s。
 *
 * 降级记录 usedFallback（供日志/调试，极客可见）：
 * - "rectify_fallback"     矫正失败（返回 null 用原图）
 * - "enhance_fallback"     画质增强失败（返回原图）
 * - "compress_q60"         压缩首次 >300KB，降质量 60
 * - "compress_scale_1600"  q60 仍 >300KB，长边缩至 1600px
 * - "compress_failed"      压缩抛异常（返回空字节）
 * - "compress_invalid_input" 输入位图非法
 *
 * 编排逻辑 [orchestrate] 为纯函数注入（组件可替换），JVM 单元测试全覆盖；
 * 真实组件（OpenCV/Bitmap.compress）需模拟器/真机验证。
 */
object PreprocessPipeline {

    /**
     * 预处理链入口：三步骤顺序执行，每步独立降级，永不抛出。
     * 运行于 [Dispatchers.Default]（文档要求，避免阻塞主线程）。
     */
    suspend fun run(input: Bitmap): PreprocessResult<Bitmap> = withContext(Dispatchers.Default) {
        orchestrate(input, Rectifier::rectify, Enhancer::enhance, Compressor::compress)
    }

    /**
     * 编排逻辑（internal 供同模块 JVM 测试直接调用，注入桩组件）。
     * 泛型 T 使测试可用字符串占位（生产 T=Bitmap），行为与 [run] 完全一致。
     */
    internal fun <T> orchestrate(
        input: T,
        rectify: (T) -> T?,
        enhance: (T) -> EnhanceResult<T>,
        compress: (T) -> CompressorResult,
    ): PreprocessResult<T> {
        val fallbacks = mutableListOf<String>()

        // 第 1 步：矫正（失败 → null → 用原图）
        val rectified = try {
            rectify(input)
        } catch (e: Exception) {
            null
        }
        if (rectified == null) fallbacks += "rectify_fallback"
        val afterRectify = rectified ?: input

        // 第 2 步：画质（失败 → 返回原图，usedFallback=true）
        val enhanced = try {
            enhance(afterRectify)
        } catch (e: Exception) {
            EnhanceResult(afterRectify, usedFallback = true)
        }
        if (enhanced.usedFallback) fallbacks += "enhance_fallback"

        // 第 3 步：压缩（失败 → 空字节，记录 compress_failed）
        val compressed = try {
            compress(enhanced.bitmap)
        } catch (e: Exception) {
            CompressorResult(ByteArray(0), listOf("compress_failed"))
        }
        fallbacks += compressed.usedFallback

        return PreprocessResult(
            rectified = rectified,
            enhanced = enhanced.bitmap,
            webpBytes = compressed.webpBytes,
            usedFallback = fallbacks,
        )
    }
}

/**
 * 预处理链结果（模块 01 文档定义；T 泛型化便于 JVM 测试，生产为 Bitmap）：
 * - rectified：矫正结果位图；null = 矫正失败用原图
 * - enhanced：画质增强后的位图
 * - webpBytes：压缩后的 WebP 字节（目标 ≤300KB）
 * - usedFallback：本次走过的降级步骤记录（顺序保留）
 */
data class PreprocessResult<out T>(
    val rectified: T?,
    val enhanced: T,
    val webpBytes: ByteArray,
    val usedFallback: List<String>,
)
