package com.misoup.app.splitting.candidates

/**
 * T04 裁剪范围纯逻辑：候选框（原图坐标）→ 位图像素裁剪矩形（越界钳制）。
 *
 * 原图可能小于候选框（烂图/缩放误差），裁剪前必须钳制到位图边界，防崩溃。
 */
object CropCalculator {

    /**
     * @param box 候选框（原图坐标，可为负/超界）
     * @param pageWidth 位图宽
     * @param pageHeight 位图高
     * @return 钳制后的裁剪矩形（整数像素，恒在 [0, w) x [0, h) 内）；
     *         完全在界外 → null（调用方跳过该框）
     */
    fun clamp(box: BoundingBox, pageWidth: Int, pageHeight: Int): IntRect? {
        if (pageWidth <= 0 || pageHeight <= 0) return null
        if (box.width <= 0f || box.height <= 0f) return null
        // 与页面完全无交集（整框在界外）→ null（调用方跳过该框）
        if (box.right <= 0f || box.bottom <= 0f || box.left >= pageWidth || box.top >= pageHeight) return null
        val left = box.left.toInt().coerceIn(0, pageWidth - 1)
        val top = box.top.toInt().coerceIn(0, pageHeight - 1)
        val right = box.right.toInt().coerceIn(left + 1, pageWidth)
        val bottom = box.bottom.toInt().coerceIn(top + 1, pageHeight)
        if (right <= left || bottom <= top) return null
        return IntRect(left, top, right - left, bottom - top)
    }
}

/** 整数裁剪矩形（x, y = 左上角；width/height 恒为正） */
data class IntRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)
