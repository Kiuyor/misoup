package com.misoup.app.pipeline.compress

import android.graphics.Bitmap
import com.misoup.app.data.store.ImageScale
import java.io.ByteArrayOutputStream

/**
 * T06 压缩器（Compressor）
 *
 * 模块 01 文档要求（预处理链第 3 步）：
 * - 首试：Bitmap.compress(WEBP, quality=80)
 * - 若 >300KB：降 quality 至 60 再试
 * - 仍大：长边缩至 1600px（等比缩放）后以当前 quality 再压缩
 * - 最终无论大小都收尾（缩边后不再继续降级，接受现状）
 *
 * 降级路径记录到 [CompressorResult.usedFallback]（供 PreprocessResult 汇总）。
 *
 * ⚠️ Bitmap.compress 依赖 Android 运行时（JVM 单测 stub 不可用），
 * 决策状态机与缩边判定抽为纯函数 [CompressorLogic]，JVM 全覆盖测试。
 */
object Compressor {

    /**
     * 压缩输入位图为 WebP 字节，按文档执行降级链：
     * q80 → 超 300KB 则 q60 → 仍超则长边缩至 1600px 再压。
     * 任何异常返回空字节数组并记录 compress_failed（调用方据此降级）。
     */
    fun compress(input: Bitmap): CompressorResult {
        if (input.width <= 0 || input.height <= 0) {
            return CompressorResult(ByteArray(0), listOf("compress_invalid_input"))
        }
        return try {
            val fallbacks = mutableListOf<String>()
            val steps = mutableListOf(CompressorLogic.Step.INITIAL_Q80)

            // 第 1 次：WEBP q80
            var bitmap = input
            var quality = CompressorLogic.QUALITY_FIRST
            var bytes = encodeWebp(bitmap, quality)

            // 第 2 次：>300KB → q60
            if (CompressorLogic.nextStep(steps.last(), bytes.size) == CompressorLogic.Step.RETRY_Q60) {
                fallbacks += "compress_q60"
                steps += CompressorLogic.Step.RETRY_Q60
                quality = CompressorLogic.QUALITY_FALLBACK
                bytes = encodeWebp(bitmap, quality)
            }

            // 第 3 次：仍 >300KB → 长边缩至 1600px 再压
            if (CompressorLogic.nextStep(steps.last(), bytes.size) == CompressorLogic.Step.SCALE_1600) {
                fallbacks += "compress_scale_1600"
                steps += CompressorLogic.Step.SCALE_1600
                val scale = ImageScale.scaleForMaxLongEdge(bitmap.width, bitmap.height, CompressorLogic.FALLBACK_LONG_EDGE)
                if (scale < 1f) {
                    val (w, h) = ImageScale.scaledSize(bitmap.width, bitmap.height, scale)
                    bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
                }
                bytes = encodeWebp(bitmap, quality)
            }

            CompressorResult(bytes, fallbacks)
        } catch (e: Exception) {
            CompressorResult(ByteArray(0), listOf("compress_failed"))
        }
    }

    /** WebP 编码为字节数组（失败抛异常由调用方捕获） */
    private fun encodeWebp(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.WEBP, quality, out)
        return out.toByteArray()
    }
}

/** 压缩结果 */
data class CompressorResult(
    val webpBytes: ByteArray,
    /** 降级步骤记录：compress_q60 / compress_scale_1600 / compress_failed / compress_invalid_input */
    val usedFallback: List<String>,
)

/**
 * T06 纯决策逻辑（不依赖 Android，JVM 全覆盖测试）。
 *
 * 状态机：
 * INITIAL_Q80 --(≤300KB)--> DONE
 * INITIAL_Q80 --(>300KB)--> RETRY_Q60
 * RETRY_Q60  --(≤300KB)--> DONE
 * RETRY_Q60  --(>300KB)--> SCALE_1600
 * SCALE_1600 --(任意大小)--> DONE   （缩边后收尾，不再降级）
 */
object CompressorLogic {

    /** 压缩目标上限：300KB */
    const val TARGET_MAX_BYTES = 300 * 1024

    /** 首选质量（文档：WEBP 80） */
    const val QUALITY_FIRST = 80

    /** 降级质量（文档：>300KB 时降 60） */
    const val QUALITY_FALLBACK = 60

    /** 二次降级：长边缩至 1600px */
    const val FALLBACK_LONG_EDGE = 1600

    /** 压缩流程步骤 */
    enum class Step { INITIAL_Q80, RETRY_Q60, SCALE_1600, DONE }

    /**
     * 根据「当前步骤 + 本次压缩结果大小」决策下一步。
     * 边界：恰好 300KB（307200 字节）视为达标 → DONE。
     */
    fun nextStep(current: Step, currentBytes: Int): Step {
        if (currentBytes <= TARGET_MAX_BYTES) return Step.DONE
        return when (current) {
            Step.INITIAL_Q80 -> Step.RETRY_Q60
            Step.RETRY_Q60 -> Step.SCALE_1600
            Step.SCALE_1600 -> Step.DONE
            Step.DONE -> Step.DONE
        }
    }

    /**
     * 由走过的步骤序列推导降级标签（与 Compressor 记录一致，纯函数便于测试）。
     */
    fun fallbackTagsTaken(path: List<Step>): List<String> {
        val tags = mutableListOf<String>()
        if (Step.RETRY_Q60 in path) tags += "compress_q60"
        if (Step.SCALE_1600 in path) tags += "compress_scale_1600"
        return tags
    }

    /** 长边是否超过 1600（需要缩边）。非法尺寸返回 false。 */
    fun scaleNeeded(width: Int, height: Int): Boolean =
        ImageScale.scaleForMaxLongEdge(width, height, FALLBACK_LONG_EDGE) < 1f
}
