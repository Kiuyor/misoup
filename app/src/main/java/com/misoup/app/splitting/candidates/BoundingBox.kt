package com.misoup.app.splitting.candidates

/**
 * 候选框（模块 06 T01c 输出）：纯 JVM 矩形，避免 android.graphics.RectF
 * 在 JVM 单测中抛 Stub!（工程未开 isReturnDefaultValues，同 OcrResult.Box 裁决）。
 */
data class BoundingBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val left: Float get() = x
    val top: Float get() = y
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val area: Float get() = width * height

    /** 与另一框的交集面积（不重叠 → 0） */
    fun intersectionArea(other: BoundingBox): Float {
        val w = minOf(right, other.right) - maxOf(left, other.left)
        val h = minOf(bottom, other.bottom) - maxOf(top, other.top)
        return if (w > 0f && h > 0f) w * h else 0f
    }

    /** 与另一框是否重叠（含边界相切） */
    fun intersects(other: BoundingBox): Boolean = intersectionArea(other) > 0f

    /** 并集外接盒 */
    fun union(other: BoundingBox): BoundingBox {
        val l = minOf(left, other.left)
        val t = minOf(top, other.top)
        val r = maxOf(right, other.right)
        val b = maxOf(bottom, other.bottom)
        return BoundingBox(l, t, r - l, b - t)
    }

    /** 外扩（padding >= 0 安全） */
    fun expand(padding: Float): BoundingBox {
        val p = maxOf(0f, padding)
        return BoundingBox(left - p, top - p, width + 2 * p, height + 2 * p)
    }

    /** 中心点 */
    fun center(): Pair<Float, Float> = Pair(left + width / 2f, top + height / 2f)

    companion object {
        /** 由边界构造（保证宽高非负） */
        fun ofBounds(left: Float, top: Float, right: Float, bottom: Float): BoundingBox =
            BoundingBox(left, top, maxOf(0f, right - left), maxOf(0f, bottom - top))
    }
}
