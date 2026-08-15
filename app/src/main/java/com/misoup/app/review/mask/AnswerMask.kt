package com.misoup.app.review.mask

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 答案区遮罩（T05）
 *
 * 模块 03 定稿："遮罩区位置默认 = 结构拆分的 answer 段，未拆分时用户可长按框选"。
 * 结构拆分（视觉 LLM，M3）尚未落地 → 本模块兜底：
 * - 默认遮罩 = 整张原图（[MaskRect.FULL]，最保守：答案一定被盖住）
 * - 用户长按进入框选模式，拖动绘制矩形，归一化坐标（0..1，相对图片）持久化到
 *   <条目目录>/.mask.json，下次重做沿用（进程杀后不丢）
 *
 * 坐标一律归一化（0..1），与图片实际像素解耦（加载尺寸变化不影响遮罩位置）。
 */
@Serializable
data class MaskRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    /** 归一化钳制：坐标进 0..1；left<right、top<bottom 不满足时交换（容错） */
    fun normalized(): MaskRect {
        var l = left.coerceIn(0f, 1f)
        var r = right.coerceIn(0f, 1f)
        var t = top.coerceIn(0f, 1f)
        var b = bottom.coerceIn(0f, 1f)
        if (l > r) { val tmp = l; l = r; r = tmp }
        if (t > b) { val tmp = t; t = b; b = tmp }
        return MaskRect(l, t, r, b)
    }

    /** 是否覆盖整图（默认态；容差 1e-4 吸收浮点误差） */
    fun isFull(): Boolean {
        val n = normalized()
        return n.left <= 1e-4 && n.top <= 1e-4 && n.right >= 1f - 1e-4 && n.bottom >= 1f - 1e-4
    }

    /** 是否为空矩形（零面积，非法框选） */
    fun isEmpty(): Boolean {
        val n = normalized()
        return n.right - n.left < 1e-4 || n.bottom - n.top < 1e-4
    }

    companion object {
        /** 默认：整图遮罩 */
        val FULL = MaskRect(0f, 0f, 1f, 1f)

        /** 像素坐标 → 归一化（图片显示尺寸已知时用）；非法尺寸 → FULL */
        fun fromPixels(x1: Float, y1: Float, x2: Float, y2: Float, width: Int, height: Int): MaskRect {
            if (width <= 0 || height <= 0) return FULL
            return MaskRect(
                left = x1 / width,
                top = y1 / height,
                right = x2 / width,
                bottom = y2 / height
            ).normalized()
        }
    }
}

/**
 * 框选交互状态机（纯逻辑，可单测）：
 * Idle（未框选）→ Selecting（长按进入）→ 拖动更新 → Selected（松手定稿）
 */
data class MaskSelectionState(
    val selecting: Boolean = false,
    val current: MaskRect? = null,     // 拖动中的矩形
    val committed: MaskRect? = null    // 定稿矩形（null = 未自定义，用默认整图）
) {
    /** 长按进入框选模式 */
    fun enter(): MaskSelectionState = copy(selecting = true, current = null)

    /** 取消框选（保持原定稿） */
    fun cancel(): MaskSelectionState = copy(selecting = false, current = null)

    /** 拖动更新当前矩形 */
    fun update(rect: MaskRect): MaskSelectionState =
        if (!selecting) this else copy(current = rect.normalized())

    /** 松手定稿：空矩形视为取消，否则提交 */
    fun commit(): MaskSelectionState {
        if (!selecting) return this
        val r = current
        if (r == null || r.isEmpty()) return copy(selecting = false, current = null)
        return copy(selecting = false, current = null, committed = r.normalized())
    }

    /** 生效遮罩：有自定义用自定义，否则整图 */
    fun effective(): MaskRect = committed ?: MaskRect.FULL
}

/** .mask.json 序列化（同一 Json 实例，容错解析） */
object MaskJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun serialize(rect: MaskRect): String = json.encodeToString(MaskRect.serializer(), rect)

    /** 解析失败 → null（调用方回退默认整图遮罩，不崩） */
    fun parse(text: String): MaskRect? = runCatching { json.decodeFromString(MaskRect.serializer(), text) }.getOrNull()
}
