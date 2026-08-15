package com.misoup.app.review.stats

import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.review.fsrs.FsrsTime
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 学情统计（T08）——数据类定义按模块 03 文档：
 *
 * ```
 * data class Stats(
 *   val total: Int, val bySubject: Map<String, Int>,
 *   val reviewRate: Double,       // due 内已完成 / 应完成
 *   val accuracy: Double,         // reviews 中 Good+Easy 占比
 *   val trend30d: List<Pair<LocalDate, Int>>,  // 每日复习数
 *   val mastery: Map<String, Double>  // 知识点: 0~1（<0.4 红 / <0.7 黄 / 其余绿）
 * )
 * ```
 *
 * 口径说明（文档裁决）：
 * - total：库内正常条目数（损坏"待修复"条目不计入）
 * - bySubject：科目 → 条目数
 * - reviewRate：应完成 = due<=now（含新卡，同队列口径）；已完成 = 应完成中
 *   lastReview 日期 == 今天的条目。应完成为 0 → 0.0（避免除零）
 * - accuracy：全部 reviews 中 rating∈{2,3}（Good/Easy）占比；无记录 → 0.0
 * - trend30d：含今天在内最近 30 天，每天 reviews 条数（按 date 的 UTC 日期聚合，
 *   缺失日期补 0，保证列表恒为 30 项）
 * - mastery：见 [Mastery]
 */
object StatsCalculator {

    /** 趋势窗口：最近 30 天（含今天） */
    const val TREND_DAYS = 30

    /**
     * 纯函数聚合（不依赖 Android；zone 决定"今天"的切分，测试传 UTC 保证确定）。
     *
     * @param metas 全部正常条目
     * @param now   当前时刻 epoch ms
     * @param zone  时区（默认系统时区；统计按本地日切分）
     */
    fun calculate(metas: List<MisoupMeta>, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Stats {
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now), zone)

        // ---- total / bySubject ----
        val total = metas.size
        val bySubject = metas.groupingBy { it.subject }.eachCount()

        // ---- reviewRate：应完成（due<=now）/ 已完成（lastReview 在今天） ----
        val dueNow = metas.filter { isDue(meta = it, now = now) }
        val dueCount = dueNow.size
        val completed = dueNow.count { meta ->
            FsrsTime.toEpochMs(meta.fsrs.lastReview)?.let {
                LocalDate.ofInstant(java.time.Instant.ofEpochMilli(it), zone) == today
            } == true
        }
        val reviewRate = if (dueCount == 0) 0.0 else completed.toDouble() / dueCount

        // ---- accuracy：全部 reviews 中 Good(2)+Easy(3) 占比 ----
        val allReviews = metas.flatMap { it.reviews }
        val goodEasy = allReviews.count { it.rating == 2 || it.rating == 3 }
        val accuracy = if (allReviews.isEmpty()) 0.0 else goodEasy.toDouble() / allReviews.size

        // ---- trend30d：最近 30 天每日复习数（缺失补 0） ----
        val dayStart = today.minusDays(TREND_DAYS - 1L)
        val counts = HashMap<LocalDate, Int>()
        allReviews.forEach { r ->
            FsrsTime.toEpochMs(r.date)?.let { ms ->
                val d = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(ms), zone)
                if (!d.isBefore(dayStart) && !d.isAfter(today)) {
                    counts[d] = (counts[d] ?: 0) + 1
                }
            }
        }
        val trend30d = (0 until TREND_DAYS).map { i ->
            val d = dayStart.plusDays(i.toLong())
            d to (counts[d] ?: 0)
        }

        // ---- mastery ----
        val mastery = Mastery.calculate(metas)

        return Stats(total, bySubject, reviewRate, accuracy, trend30d, mastery)
    }

    /** 是否已到期：due<=now；新卡（due 空）按立即到期 */
    private fun isDue(meta: MisoupMeta, now: Long): Boolean {
        val due = FsrsTime.toEpochMs(meta.fsrs.due) ?: return true
        return due <= now
    }
}

/**
 * 学情统计（模块 03 数据类定稿）。
 * [trend30d] 为最近 30 天（含今天）每日复习数，恒 30 项。
 */
data class Stats(
    val total: Int,
    val bySubject: Map<String, Int>,
    val reviewRate: Double,
    val accuracy: Double,
    val trend30d: List<Pair<LocalDate, Int>>,
    val mastery: Map<String, Double>
) {
    /** 最近 30 天复习总数（趋势卡片副文案） */
    val trend30dTotal: Int get() = trend30d.sumOf { it.second }
}
