package com.misoup.app.recognition.paddle

/**
 * 离线 OCR 结果类型（模块 04 文档定义，纯 JVM 可测）。
 *
 * 文档矛盾裁决：module/04-offline-ocr.md 中 `OcrLine.box: RectF` 为
 * android.graphics.RectF——JVM 单测无法构造（android.jar 桩类构造即抛
 * "Stub!"，且工程未开 isReturnDefaultValues）。本模块以纯类型 [Box] 承载，
 * 边界处（PaddleOcrEngine）再映射回 RectF 供 UI 使用，语义不变。
 */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = width * height

    /** 与另一行的 y 区间重叠（按容差外扩），用于阅读顺序分行 */
    fun overlapsY(other: Box, tolerance: Float = 0f): Boolean =
        top - tolerance <= other.bottom && other.top - tolerance <= bottom

    companion object {
        /** 归一化四边形（4 点，模型输出约定）→ 外接矩形 */
        fun ofQuad(xs: FloatArray, ys: FloatArray): Box? {
            if (xs.size < 4 || ys.size < 4) return null
            val minX = xs.min()
            val maxX = xs.max()
            val minY = ys.min()
            val maxY = ys.max()
            if (maxX - minX <= 0f || maxY - minY <= 0f) return null
            return Box(minX, minY, maxX, maxY)
        }
    }
}

/** 单行识别结果（text = 识别文本，box = 原图坐标系行框） */
data class OcrLine(
    val text: String,
    val box: Box,
)

/** 整图识别结果：lines 按阅读顺序（y 排序），text 为 lines 拼接，latencyMs 为识别耗时 */
data class OcrResult(
    val lines: List<OcrLine>,
    val text: String,
    val latencyMs: Long,
) {
    val isEmpty: Boolean get() = text.isBlank()
}
