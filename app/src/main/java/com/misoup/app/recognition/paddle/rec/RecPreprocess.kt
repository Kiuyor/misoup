package com.misoup.app.recognition.paddle.rec

/**
 * rec/cls 预处理纯逻辑（T04）
 *
 * PP-OCRv4 rec 输入：单行图，高固定 48，宽按长宽比缩放（上限 320，超出截断）。
 * cls 输入：高 48，宽 192（PP-OCRv4 mobile 方向分类默认输入）。
 * 像素归一化（mean/std）与 Tensor 组装在 engine（Android，需真机验证）。
 */
object RecPreprocess {

    const val REC_HEIGHT = 48
    const val REC_MAX_WIDTH = 320

    /** rec 归一化参数（PP-OCRv4 用 [0,1] 均值 0.5 方差 0.5） */
    val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
    val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

    data class ResizeSpec(val width: Int, val height: Int)

    /**
     * 行裁剪图 → rec 输入尺寸：高 48，宽 = round(48 * w/h)，封顶 [REC_MAX_WIDTH]。
     * 长宽比 < 1（竖排误检行）按最小宽 1 处理，由 cls 校正后重试。
     */
    fun resizeDims(cropW: Int, cropH: Int, maxWidth: Int = REC_MAX_WIDTH): ResizeSpec {
        require(cropW > 0 && cropH > 0) { "行裁剪尺寸非法: ${cropW}x${cropH}" }
        val w = (REC_HEIGHT.toFloat() * cropW / cropH).roundToInt().coerceIn(1, maxWidth)
        return ResizeSpec(w, REC_HEIGHT)
    }

    private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()
}

/**
 * cls 方向分类判定（T04，纯逻辑）
 *
 * PP-OCRv4 cls 输出 2 类 [0° 正常, 180° 旋转]，softmax 后取 argmax；
 * 判定阈值 0.9（PP-OCR 官方默认）：180° 置信度 > 0.9 才旋转，
 * 否则视为正常方向（低置信不做旋转，避免误转）。
 */
object ClsLogic {

    const val ROTATE_THRESHOLD = 0.9f
    const val ROTATE_180 = 180f
    const val ROTATE_0 = 0f

    /**
     * @param clsScores 二分类得分（softmax 后，[0]=0°，[1]=180°）
     * @return 需要旋转的角度（0 或 180）
     */
    fun rotationDegrees(clsScores: FloatArray): Float {
        if (clsScores.size < 2) return ROTATE_0
        return if (clsScores[1] > ROTATE_THRESHOLD) ROTATE_180 else ROTATE_0
    }

    /** 归一化（softmax）：未归一化 logits → 概率 */
    fun softmax(logits: FloatArray): FloatArray {
        if (logits.size < 2) return logits.copyOf()
        val max = logits.max()
        val exps = logits.map { kotlin.math.exp(it - max) }
        val sum = exps.sum()
        return exps.map { it / sum }.toFloatArray()
    }
}
