package com.misoup.app.settings.review

import com.misoup.app.review.fsrs.Fsrs

/**
 * FSRS 参数文本编解码（模块 10 T04，纯逻辑）
 *
 * 设置页「复习 → FSRS 参数（高级）」：17 个 w 值 + short-term 开关。
 * 纯文本 ⇄ [Fsrs.Params]；非法输入给出可读原因（不全/非数字/负数/非有限数）。
 */
object FsrsParamsCodec {

    /** FSRS-4.5 参数个数（Fsrs.DEFAULT_W 长度） */
    const val W_COUNT = 17

    /**
     * 文本 → w 数组。分隔符：逗号（中英文）/ 空白 / 换行。
     * 要求恰好 17 个非负有限数；否则 failure（带第几个参数非法的定位）。
     */
    fun parseW(text: String): Result<DoubleArray> = runCatching {
        val parts = text.split(',', '，', ' ', '\n', '\t', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        require(parts.size == W_COUNT) {
            "需要 $W_COUNT 个参数，当前 ${parts.size} 个"
        }
        DoubleArray(W_COUNT) { i ->
            val v = try {
                parts[i].toDouble()
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("第 ${i + 1} 个参数非法：${parts[i]}（不是数字）")
            }
            require(v.isFinite() && v >= 0.0) {
                "第 ${i + 1} 个参数非法：${parts[i]}（需为非负有限数）"
            }
            v
        }
    }

    /** w 数组 → 展示文本（每项保留 5 位小数） */
    fun formatW(w: DoubleArray): String = w.joinToString(", ") { "%.5f".format(it) }

    /** 完整性校验（保存前兜底）：17 个 + 全部非负有限 */
    fun validate(w: DoubleArray): Result<Unit> = when {
        w.size != W_COUNT -> Result.failure(IllegalArgumentException("参数个数必须为 $W_COUNT，当前 ${w.size}"))
        w.any { !it.isFinite() || it < 0.0 } ->
            Result.failure(IllegalArgumentException("参数必须为非负有限数"))
        else -> Result.success(Unit)
    }

    /** 由 w 文本 + short-term 开关构建 [Fsrs.Params]（供设置页保存） */
    fun toParams(wText: String, enableShortTerm: Boolean): Result<Fsrs.Params> =
        parseW(wText).map { Fsrs.Params(w = it, enableShortTerm = enableShortTerm) }
}
