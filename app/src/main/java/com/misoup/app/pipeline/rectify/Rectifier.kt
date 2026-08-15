package com.misoup.app.pipeline.rectify

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * T04 文档矫正器（Rectifier）
 *
 * 负责将拍照产生的倾斜/透视变形的试卷图片矫正为正视图：
 * 1. 灰度 → 高斯模糊(5x5) → Canny 边缘检测(50,150)
 * 2. findContours 找轮廓 → 按面积取最大轮廓
 * 3. approxPolyDP 近似为 4 点 → getPerspectiveTransform + warpPerspective
 *
 * 降级判定（返回 null 表示调用方使用原图，见模块 01 文档）：
 * - 最大轮廓面积 < 画面面积 10%
 * - 近似后不是恰好 4 个点
 * - 任何异常（含 OpenCV JNI 不可用）也返回 null 降级，保证录入永不阻塞
 *
 * ⚠️ 本实现依赖 OpenCV JNI（org.opencv.android.Utils / Imgproc），
 * JVM 单元测试环境不可用，需在模拟器/真机验证；
 * 判定逻辑已抽为纯函数 [RectifierLogic]，可在 JVM 全覆盖测试。
 */
object Rectifier {

    /**
     * 尝试矫正输入位图；失败（无合格四边轮廓/异常）返回 null，由调用方降级用原图。
     * 矫正成功输出与输入同尺寸的透视校正位图（ARGB_8888）。
     */
    fun rectify(input: Bitmap): Bitmap? {
        if (input.width <= 0 || input.height <= 0) return null
        val src = Mat()
        return try {
            // Bitmap(RGBA) → Mat
            Utils.bitmapToMat(input, src)

            // 1) 灰度 → 高斯模糊(5x5) → Canny(50,150)
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            val blurred = Mat()
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            val edges = Mat()
            Imgproc.Canny(blurred, edges, 50.0, 150.0)

            // 2) findContours → 按面积取最大轮廓
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null

            // 3) approxPolyDP 近似：epsilon = 0.02 × 轮廓周长
            val approx = MatOfPoint2f()
            val contour2f = MatOfPoint2f(*largest.toArray())
            val epsilon = RectifierLogic.APPROX_EPSILON_RATIO * Imgproc.arcLength(contour2f, true)
            Imgproc.approxPolyDP(contour2f, approx, epsilon, true)

            // 判定：面积占比 ≥10% 且恰好 4 点，否则降级
            val imageArea = src.rows().toDouble() * src.cols().toDouble()
            val vertices = approx.toArray().map { it.x to it.y }
            if (!RectifierLogic.isUsableQuad(Imgproc.contourArea(largest), imageArea, vertices.size)) {
                return null
            }

            // 4) 四点排序 → 透视变换 → 输出与输入同尺寸
            val ordered = RectifierLogic.orderQuadPoints(vertices)
            val srcQuad = MatOfPoint2f(*ordered.map { Point(it.first, it.second) }.toTypedArray())
            val dstQuad = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(src.cols().toDouble(), 0.0),
                Point(src.cols().toDouble(), src.rows().toDouble()),
                Point(0.0, src.rows().toDouble())
            )
            val transform = Imgproc.getPerspectiveTransform(srcQuad, dstQuad)
            val warped = Mat()
            Imgproc.warpPerspective(src, warped, transform, Size(src.cols().toDouble(), src.rows().toDouble()))

            val out = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, out)
            out
        } catch (e: Exception) {
            // 任何异常（JNI 不可用、内存不足等）→ 降级用原图，录入不阻塞
            null
        } finally {
            src.release()
        }
    }
}

/**
 * T04 纯判定逻辑（不依赖 OpenCV，JVM 单元测试全覆盖）。
 */
object RectifierLogic {

    /** 轮廓面积占比低于该阈值视为「找不到文档」→ 降级 */
    const val MIN_CONTOUR_AREA_RATIO = 0.10

    /** 透视矫正要求的顶点数 */
    const val QUAD_POINT_COUNT = 4

    /** approxPolyDP 近似精度系数（相对轮廓周长比例） */
    const val APPROX_EPSILON_RATIO = 0.02

    /**
     * 判定最大轮廓是否可作为文档四边：
     * 面积占比 ≥ 10%（文档要求：<10% 降级）且 近似顶点数恰为 4。
     * 非法画面面积（<=0）一律 false。
     */
    fun isUsableQuad(
        contourArea: Double,
        imageArea: Double,
        approxPointCount: Int,
    ): Boolean {
        if (imageArea <= 0.0) return false
        val ratio = contourArea / imageArea
        return ratio >= MIN_CONTOUR_AREA_RATIO && approxPointCount == QUAD_POINT_COUNT
    }

    /**
     * 四点排序（顺时针：左上 → 右上 → 右下 → 左下），供透视变换映射：
     * - x+y 最小 = 左上，x+y 最大 = 右下
     * - 剩余两点按 x−y 降序：最大 = 右上（x 大 y 小），最小 = 左下（x 小 y 大）
     * 输入必须恰好 4 点，否则抛 [IllegalArgumentException]（调用方已先经 isUsableQuad 判定）。
     */
    fun orderQuadPoints(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        require(points.size == QUAD_POINT_COUNT) { "需要恰好 4 个顶点，实际 ${points.size}" }
        val bySum = points.sortedBy { it.first + it.second }
        val topLeft = bySum[0]
        val bottomRight = bySum[3]
        val middle = bySum.subList(1, 3).sortedByDescending { it.first - it.second }
        val topRight = middle[0]
        val bottomLeft = middle[1]
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }
}
