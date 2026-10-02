package com.misoup.app.pipeline.enhance

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * T05 画质增强器（Enhancer）
 *
 * 模块 01 文档要求（预处理链第 2 步）：
 * - CLAHE（clipLimit=2.0, tile=8x8）作用于亮度通道（Lab 色彩空间 L 通道）
 * - 阴影去除：大核高斯模糊估计背景光照，亮度通道相减（背景光照估计相减）
 *
 * 失败策略：任何异常（含 OpenCV JNI 不可用）→ 返回原图并标记 usedFallback=true，
 * 保证预处理链永不中断。
 *
 * ⚠️ OpenCV JNI 在 JVM 单元测试不可用，需模拟器/真机验证；
 * 核尺寸等纯逻辑抽为 [EnhancerLogic]，JVM 可测。
 */
object Enhancer {

    /**
     * 画质增强。返回 [EnhanceResult]：
     * - bitmap：增强后的位图；失败时与原图同一对象
     * - usedFallback：true 表示发生了降级（供 PreprocessResult.usedFallback 记录）
     */
    fun enhance(input: Bitmap): EnhanceResult<Bitmap> {
        if (input.width <= 0 || input.height <= 0) return EnhanceResult(input, true)
        val src = Mat()
        return try {
            Utils.bitmapToMat(input, src)

            // RGBA → BGR → Lab（L 为亮度通道，a/b 为色彩通道）
            val bgr = Mat()
            Imgproc.cvtColor(src, bgr, Imgproc.COLOR_RGBA2BGR)
            val lab = Mat()
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            val channels = ArrayList<Mat>(3)
            Core.split(lab, channels)

            // 1) CLAHE：clipLimit=2.0, tileGridSize=8x8，仅作用于 L 通道
            val clahe = Imgproc.createCLAHE(EnhancerLogic.CLAHE_CLIP_LIMIT, Size(8.0, 8.0))
            clahe.apply(channels[0], channels[0])

            // 2) 阴影去除：大核高斯模糊估计背景光照 → 亮度相减 → 平移回中间灰度 128
            val illumination = Mat()
            val kernel = EnhancerLogic.illuminationKernelSize(input.width, input.height)
            Imgproc.GaussianBlur(channels[0], illumination, Size(kernel.toDouble(), kernel.toDouble()), 0.0)
            Core.subtract(channels[0], illumination, channels[0])
            Core.add(channels[0], Scalar(128.0), channels[0])

            // 合并回 Lab → BGR → 输出位图
            Core.merge(channels, lab)
            val resultBgr = Mat()
            Imgproc.cvtColor(lab, resultBgr, Imgproc.COLOR_Lab2BGR)
            val out = Bitmap.createBitmap(resultBgr.cols(), resultBgr.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(resultBgr, out)
            EnhanceResult(out, usedFallback = false)
        } catch (e: Exception) {
            // 降级：返回原图（增强失败不阻断录入）
            EnhanceResult(input, usedFallback = true)
        } finally {
            src.release()
        }
    }
}

/** 画质增强结果（T 泛型化便于 JVM 测试注入字符串桩；生产为 Bitmap） */
data class EnhanceResult<out T>(
    val bitmap: T,
    /** true = 发生降级（返回了原图） */
    val usedFallback: Boolean,
)

/**
 * 识别输入降采样（清单 #11）：入 OCR 队列前把位图长边压到 ≤[MAX_EDGE]。
 * 仅用于识别队列输入；落库存储用原图（原图编码走 assetFactory/裁剪图），互不影响。
 * 已小于上限的位图原样返回（不复制）。
 */
object RecognitionInput {

    /** 识别输入长边上限：1280px 足够 OCR 命中文字行，防超大图进队列内存/耗时膨胀 */
    const val MAX_EDGE = 1280

    /** 按比例缩放位图使长边 ≤[MAX_EDGE]；返回新位图（不改传入对象），无需缩放时原样返回 */
    fun downsample(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_EDGE || longEdge == 0) return bitmap
        val scale = MAX_EDGE.toFloat() / longEdge
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}

/**
 * T05 纯逻辑（不依赖 OpenCV，JVM 可测）。
 */
object EnhancerLogic {

    /** CLAHE 对比度限制（文档要求 2.0） */
    const val CLAHE_CLIP_LIMIT = 2.0

    /** CLAHE 分块 tile 尺寸（文档要求 8x8） */
    const val CLAHE_TILE_SIZE = 8

    /** 背景光照估计的最小高斯核（小图也至少 31，保证核远大于文字笔画） */
    const val MIN_ILLUMINATION_KERNEL = 31

    /** 核尺寸 = 长边 / 16（大核：约为画面尺寸的 6%，远大于局部文字） */
    const val KERNEL_DIVISOR = 16

    /**
     * 计算背景光照估计的高斯核尺寸：
     * 长边/16 向上取奇数，且不小于 [MIN_ILLUMINATION_KERNEL]。
     * 非法输入返回最小值，避免除零。
     */
    fun illuminationKernelSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return MIN_ILLUMINATION_KERNEL
        val raw = maxOf(width, height) / KERNEL_DIVISOR
        val odd = if (raw % 2 == 1) raw else raw + 1
        return odd.coerceAtLeast(MIN_ILLUMINATION_KERNEL)
    }
}
