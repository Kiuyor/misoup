package com.misoup.app.recognition.paddle.detect

import com.misoup.app.recognition.paddle.Box

/**
 * det 预处理（T03）：长边 640 归一化 + 32 对齐 padding（纯逻辑）
 *
 * PP-OCRv4 det 输入约定：
 * - 长边缩放到 640，短边按比例缩放（不裁剪）
 * - 缩放宽高对齐到 32 的倍数（右下 padding 补零）
 * - 像素归一化 mean=[0.485,0.459,0.408] std=[0.229,0.224,0.225]（ImageNet，RGB）
 *
 * 归一化常量与 Tensor 组装在 [com.misoup.app.recognition.paddle.PaddleOcrEngine]
 * （Android 环境，需真机验证）；本文件只做可 JVM 单测的几何/数学部分。
 */
object DetPreprocess {

    const val LONG_EDGE = 640
    const val ALIGN = 32

    /** 归一化参数（ImageNet 均值/方差，det 推理用） */
    val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** det 后处理概率阈值（DB 二值化） */
    const val DEFAULT_THRESHOLD = 0.3f

    /** DB unclip 简化：外接框按中心放大比例 */
    const val DEFAULT_EXPAND_RATIO = 1.5f

    /** 最小行面积（归一化坐标，过滤噪点） */
    const val DEFAULT_MIN_AREA = 3f

    /** 缩放规格：scale=缩放系数；scaledWidth/scaledHeight=精确缩放尺寸（未对齐）；
     *  width/height=对齐 32 后的实际输入尺寸；padW/padH=右下 padding */
    data class ScaleSpec(
        val scale: Float,
        val scaledWidth: Int,
        val scaledHeight: Int,
        val width: Int,
        val height: Int,
        val padW: Int,
        val padH: Int,
    )

    /**
     * 长边 640 缩放 + 32 对齐：
     * - 短边可能 < 640（不放大），长边 = 640
     * - width/height 为对齐 32 后的实际输入尺寸（≥ 缩放尺寸）
     * - padW = width - round(w*scale)，padH 同理
     */
    fun scaleForLongEdge(originalW: Int, originalH: Int, longEdge: Int = LONG_EDGE): ScaleSpec {
        require(originalW > 0 && originalH > 0) { "原图尺寸非法: ${originalW}x${originalH}" }
        val scale = longEdge.toFloat() / maxOf(originalW, originalH)
        val scaledW = (originalW * scale).roundToIntCeil()
        val scaledH = (originalH * scale).roundToIntCeil()
        val width = alignUp(scaledW, ALIGN)
        val height = alignUp(scaledH, ALIGN)
        return ScaleSpec(scale, scaledW, scaledH, width, height, width - scaledW, height - scaledH)
    }

    /** 向上对齐到 multiple 的倍数 */
    fun alignUp(value: Int, multiple: Int): Int =
        if (value % multiple == 0) value else value + (multiple - value % multiple)

    private fun Float.roundToIntCeil(): Int = kotlin.math.ceil(this).toInt()
}

/**
 * det 后处理（T03，纯 Kotlin 实现 DB 后处理核心）
 *
 * 模型输出为 score map（H×W，值 0..1，已上采样到输入尺寸），
 * 论文 DB 用「阈值二值化 → 连通域 → 外接多边形 → unclip 放大」。
 * 本实现以纯 Kotlin 等价简化（无 OpenCV 依赖，可 JVM 单测）：
 * 1. 阈值二值化 score map
 * 2. BFS 4-邻域连通域 → 每域外接矩形（quad → Box 的边界形式）
 * 3. 面积过滤（< minArea 丢弃）
 * 4. 按中心放大 expandRatio（简化 unclip）
 * 5. 映射回原图坐标：减 padding → 除 scale → 夹取到原图范围
 *
 * 真机若需与官方 PP-OCR 逐像素对齐，可替换为 OpenCV findContours 版
 * （接口不变，见 PaddleOcrEngine）。
 */
object DetPostprocess {

    /**
     * @param scores score map（行主序，H×W）
     * @param mapW score map 宽（= 对齐后输入宽）
     * @param mapH score map 高
     * @param spec [DetPreprocess.ScaleSpec]（对齐后尺寸与缩放系数）
     * @param origW 原图宽（映射目标坐标系）
     * @param origH 原图高
     * @return 原图坐标系下的行框（阅读顺序未定，T05 负责排序）
     */
    fun scoreMapToBoxes(
        scores: FloatArray,
        mapW: Int,
        mapH: Int,
        spec: DetPreprocess.ScaleSpec,
        origW: Int,
        origH: Int,
        threshold: Float = DetPreprocess.DEFAULT_THRESHOLD,
        minArea: Float = DetPreprocess.DEFAULT_MIN_AREA,
        expandRatio: Float = DetPreprocess.DEFAULT_EXPAND_RATIO,
    ): List<Box> {
        require(scores.size >= mapW * mapH) { "score map 尺寸不符: ${scores.size} < $mapW*$mapH" }
        require(expandRatio >= 1f) { "expandRatio 必须 ≥1: $expandRatio" }

        // 1. 二值化 + 2. 连通域（BFS）
        val visited = BooleanArray(mapW * mapH)
        val components = mutableListOf<IntArray>()   // 每域：minX,minY,maxX,maxY（含）
        for (y in 0 until mapH) {
            for (x in 0 until mapW) {
                val idx = y * mapW + x
                if (visited[idx] || scores[idx] < threshold) continue
                // BFS
                val queue = ArrayDeque<Int>()
                queue.addLast(idx)
                visited[idx] = true
                var minX = x; var maxX = x; var minY = y; var maxY = y
                while (queue.isNotEmpty()) {
                    val cur = queue.removeFirst()
                    val cx = cur % mapW
                    val cy = cur / mapW
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    // 4-邻域（显式边界检查，避免一维索引跨行回绕）
                    if (cx > 0 && !visited[cur - 1] && scores[cur - 1] >= threshold) {
                        visited[cur - 1] = true; queue.addLast(cur - 1)
                    }
                    if (cx < mapW - 1 && !visited[cur + 1] && scores[cur + 1] >= threshold) {
                        visited[cur + 1] = true; queue.addLast(cur + 1)
                    }
                    if (cy > 0 && !visited[cur - mapW] && scores[cur - mapW] >= threshold) {
                        visited[cur - mapW] = true; queue.addLast(cur - mapW)
                    }
                    if (cy < mapH - 1 && !visited[cur + mapW] && scores[cur + mapW] >= threshold) {
                        visited[cur + mapW] = true; queue.addLast(cur + mapW)
                    }
                }
                components += intArrayOf(minX, minY, maxX, maxY)
            }
        }

        // 3-5. 面积过滤 → 放大 → 映射回原图
        return components.mapNotNull { c ->
            val minX = c[0]; val minY = c[1]; val maxX = c[2]; val maxY = c[3]
            val w = (maxX - minX + 1).toFloat()
            val h = (maxY - minY + 1).toFloat()
            if (w * h < minArea) return@mapNotNull null

            // 中心放大（简化 unclip）
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f
            val expW = w * expandRatio / 2f
            val expH = h * expandRatio / 2f
            var left = cx - expW
            var top = cy - expH
            var right = cx + expW
            var bottom = cy + expH

            // 减 padding（模型输出含补零区）→ 除 scale 回原图 → 夹取
            val sx = spec.scale
            left = ((left - spec.padW) / sx).coerceIn(0f, origW.toFloat())
            top = ((top - spec.padH) / sx).coerceIn(0f, origH.toFloat())
            right = ((right - spec.padW) / sx).coerceIn(0f, origW.toFloat())
            bottom = ((bottom - spec.padH) / sx).coerceIn(0f, origH.toFloat())
            if (right - left < 1f || bottom - top < 1f) return@mapNotNull null
            Box(left, top, right, bottom)
        }
    }
}
