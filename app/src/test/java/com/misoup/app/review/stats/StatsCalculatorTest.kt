package com.misoup.app.review.stats

import com.misoup.app.data.model.FsrsState
import com.misoup.app.data.model.Review
import com.misoup.app.makeMeta
import com.misoup.app.review.fsrs.FsrsTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * T08 学情统计聚合：总数/科目分布/复习率/正确率/30 天趋势/知识点掌握度。
 * 全部用固定 UTC 时区，保证确定。
 */
class StatsCalculatorTest {

    private val zone = ZoneOffset.UTC
    // 2026-08-16T12:00:00Z
    private val NOW = FsrsTime.toEpochMs("2026-08-16T12:00:00Z")!!

    private val today = LocalDate.of(2026, 8, 16)

    private fun review(dateIso: String, rating: Int, stateAfter: Int = 2, intervalDays: Double = 3.0) =
        Review(date = dateIso, rating = rating, stateAfter = stateAfter, intervalDays = intervalDays)

    /** 造一条 meta：fsrs 可控 + 可选 reviews */
    private fun meta(
        id: String,
        subject: String = "数学",
        kps: List<String> = listOf("二次函数"),
        state: Int = 2,
        dueDaysFromNow: Long? = 0,          // null = 新卡
        lastReviewDaysAgo: Long? = 0,
        reviews: List<Review> = emptyList()
    ) = makeMeta(
        id = id,
        subject = subject,
        knowledgePoints = kps,
        fsrs = FsrsState(
            state = state,
            due = dueDaysFromNow?.let { FsrsTime.toIso(NOW + it * 86_400_000L) },
            stability = 3.0,
            difficulty = 5.0,
            reps = reviews.size,
            lastReview = lastReviewDaysAgo?.let { FsrsTime.toIso(NOW - it * 86_400_000L) }
        ),
        reviews = reviews
    )

    // ---------- 总数与科目分布 ----------

    @Test
    fun `total与bySubject-正确统计`() {
        val metas = listOf(
            meta("a", subject = "数学"),
            meta("b", subject = "数学"),
            meta("c", subject = "物理")
        )
        val s = StatsCalculator.calculate(metas, NOW, zone)
        assertEquals(3, s.total)
        assertEquals(mapOf("数学" to 2, "物理" to 1), s.bySubject)
    }

    @Test
    fun `空库-统计全零不崩`() {
        val s = StatsCalculator.calculate(emptyList(), NOW, zone)
        assertEquals(0, s.total)
        assertTrue(s.bySubject.isEmpty())
        assertEquals(0.0, s.reviewRate, 1e-9)
        assertEquals(0.0, s.accuracy, 1e-9)
        assertEquals(30, s.trend30d.size)          // 窗口恒 30 天
        assertTrue(s.trend30d.all { it.second == 0 })
        assertTrue(s.mastery.isEmpty())
    }

    // ---------- 复习率 ----------

    @Test
    fun `reviewRate-今天完成除以应完成`() {
        val metas = listOf(
            // 应完成（due<=now）且今天已复习 → 完成
            meta("done", dueDaysFromNow = -1, lastReviewDaysAgo = 0),
            // 应完成但昨天才复习 → 未完成
            meta("late", dueDaysFromNow = 0, lastReviewDaysAgo = 1),
            // 应完成（新卡）未复习 → 未完成
            meta("new1", state = 0, dueDaysFromNow = null, lastReviewDaysAgo = null),
            // 不应完成（未来 due）
            meta("future", dueDaysFromNow = 5, lastReviewDaysAgo = 0)
        )
        val s = StatsCalculator.calculate(metas, NOW, zone)
        assertEquals(1.0 / 3.0, s.reviewRate, 1e-9)
    }

    @Test
    fun `reviewRate-应完成为0时返回0不除零`() {
        val s = StatsCalculator.calculate(listOf(meta("f", dueDaysFromNow = 5)), NOW, zone)
        assertEquals(0.0, s.reviewRate, 1e-9)
    }

    // ---------- 正确率 ----------

    @Test
    fun `accuracy-Good加Easy占比`() {
        val metas = listOf(
            meta("a", reviews = listOf(review("2026-08-10T00:00:00Z", 2), review("2026-08-11T00:00:00Z", 3))),
            meta("b", reviews = listOf(review("2026-08-12T00:00:00Z", 0), review("2026-08-13T00:00:00Z", 1)))
        )
        val s = StatsCalculator.calculate(metas, NOW, zone)
        assertEquals(0.5, s.accuracy, 1e-9)   // 2/4
    }

    @Test
    fun `accuracy-无复习记录为0`() {
        val s = StatsCalculator.calculate(listOf(meta("a")), NOW, zone)
        assertEquals(0.0, s.accuracy, 1e-9)
    }

    // ---------- 30 天趋势 ----------

    @Test
    fun `trend30d-每天复习数且缺失补0`() {
        val metas = listOf(
            meta("a", reviews = listOf(
                review("2026-08-16T00:00:00Z", 2),   // 今天 2 条
                review("2026-08-16T10:00:00Z", 3),
                review("2026-08-15T00:00:00Z", 1),   // 昨天
                review("2026-07-01T00:00:00Z", 2),   // 窗口外（>30 天前）
                review("2026-08-17T00:00:00Z", 2)    // 未来（窗口外）
            ))
        )
        val s = StatsCalculator.calculate(metas, NOW, zone)
        assertEquals(30, s.trend30d.size)
        assertEquals(today.minusDays(29), s.trend30d.first().first)
        assertEquals(today, s.trend30d.last().first)
        assertEquals(2, s.trend30d.last().second)              // 今天 2 条
        assertEquals(1, s.trend30d[s.trend30d.size - 2].second) // 昨天 1 条
        assertEquals(0, s.trend30d.first().second)             // 30 天前 0 条
        assertEquals(3, s.trend30dTotal)                       // 窗口内合计 3
    }

    @Test
    fun `trend30d-损坏日期不参与趋势但正确率仍按评分统计`() {
        val metas = listOf(
            meta("a", reviews = listOf(Review(date = "坏日期", rating = 2)))
        )
        val s = StatsCalculator.calculate(metas, NOW, zone)
        assertTrue(s.trend30d.all { it.second == 0 })   // 坏日期不进趋势
        assertEquals(1.0, s.accuracy, 1e-9)             // 正确率只看评分 → 1/1
    }
}
