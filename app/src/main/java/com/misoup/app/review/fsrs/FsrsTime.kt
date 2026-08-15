package com.misoup.app.review.fsrs

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * meta.fsrs.due / lastReview 的 ISO-8601 字符串 ⇄ epoch 毫秒 转换（纯 JVM，可单测）。
 *
 * 背景：meta.json 的 due 字段为字符串（schema 示例 "2026-08-18T00:00:00+08:00"），
 * FSRS 算法以 epoch ms 计算。本对象是唯一转换入口，容错不崩：
 * - 解析失败/空白 → null（调用方按"未排期/新卡"处理）
 * - 序列化统一用 UTC（Z 后缀），OffsetDateTime.parse 可读回（ISO-8601 兼容）
 */
object FsrsTime {

    /** 解析 ISO-8601 → epoch ms；空白/非法 → null（不抛异常） */
    fun toEpochMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            when {
                // 带偏移（+08:00 / -05:00 / Z）→ OffsetDateTime
                iso.contains('+') || iso.indexOf('-', startIndex = 10) >= 0 || iso.endsWith("Z") ->
                    OffsetDateTime.parse(iso).toInstant().toEpochMilli()
                // 无偏移（"2026-08-18T00:00:00"）→ 按 UTC 解释
                else -> Instant.parse(iso + "Z").toEpochMilli()
            }
        }.getOrNull()
    }

    /** epoch ms → ISO-8601（UTC，Z 后缀）；非法值 → null */
    fun toIso(epochMs: Long): String? {
        if (epochMs < 0) return null
        return Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).toString()
    }

    /** 解析失败兜底：字符串损坏时返回 null 而不是抛异常（与 [toEpochMs] 同语义） */
    fun tryParse(iso: String?): Long? = toEpochMs(iso)

    /** 仅用于测试/调试：验证字符串是否可解析 */
    fun isParsable(iso: String?): Boolean = toEpochMs(iso) != null

    /** ISO-8601 → 本地日期（统计按日聚合用）；失败 → null */
    fun toLocalDate(iso: String?): java.time.LocalDate? {
        val ms = toEpochMs(iso) ?: return null
        return runCatching { Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull()
    }
}
