package com.misoup.app.review.stats

import com.misoup.app.data.model.Review
import com.misoup.app.makeMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T10 知识点掌握度：聚合正确率 + 红黄绿三档（<0.4 红 / <0.7 黄 / ≥0.7 绿，边界含 0.4/0.7）
 */
class MasteryTest {

    private fun review(rating: Int) = Review(date = "2026-08-10T00:00:00Z", rating = rating)

    private fun meta(id: String, kps: List<String>, reviews: List<Review> = emptyList()) =
        makeMeta(id = id, knowledgePoints = kps, reviews = reviews)

    // ---------- 档位边界 ----------

    @Test
    fun `levelOf-红黄绿三档边界`() {
        assertEquals(Mastery.Level.RED, Mastery.levelOf(0.0))
        assertEquals(Mastery.Level.RED, Mastery.levelOf(0.3999))
        assertEquals(Mastery.Level.YELLOW, Mastery.levelOf(0.4))     // <0.4 红 → 0.4 黄
        assertEquals(Mastery.Level.YELLOW, Mastery.levelOf(0.69))
        assertEquals(Mastery.Level.GREEN, Mastery.levelOf(0.7))      // ≥0.7 绿
        assertEquals(Mastery.Level.GREEN, Mastery.levelOf(1.0))
    }

    // ---------- 聚合 ----------

    @Test
    fun `calculate-按复习量加权正确率`() {
        val metas = listOf(
            meta("a", listOf("二次函数"), reviews = listOf(review(2), review(2), review(0))),  // 2/3
            meta("b", listOf("二次函数"), reviews = listOf(review(3)))                           // 1/1
        )
        val m = Mastery.calculate(metas)
        assertEquals(3.0 / 4.0, m["二次函数"]!!, 1e-9)
    }

    @Test
    fun `calculate-多知识点错题各贡献一次`() {
        val metas = listOf(
            meta("a", listOf("二次函数", "函数单调性"), reviews = listOf(review(2))),
            meta("b", listOf("函数单调性"), reviews = listOf(review(0)))
        )
        val m = Mastery.calculate(metas)
        assertEquals(1.0, m["二次函数"]!!, 1e-9)
        assertEquals(0.5, m["函数单调性"]!!, 1e-9)   // 1/2（a 的 Good + b 的 Again）
    }

    @Test
    fun `calculate-未复习知识点中性0_5`() {
        val metas = listOf(meta("a", listOf("电磁感应"), reviews = emptyList()))
        val m = Mastery.calculate(metas)
        assertEquals(0.5, m["电磁感应"]!!, 1e-9)
    }

    @Test
    fun `calculate-无知识点与空库`() {
        assertTrue(Mastery.calculate(emptyList()).isEmpty())
        val noKp = Mastery.calculate(listOf(makeMeta(id = "x", knowledgePoints = emptyList())))
        assertTrue(noKp.isEmpty())
    }

    // ---------- of()：单知识点详情 ----------

    @Test
    fun `of-红黄绿与统计信息`() {
        val metas = listOf(
            meta("a", listOf("压强"), reviews = listOf(review(0), review(0), review(1), review(2)))  // 1/4 = 0.25
        )
        val kp = Mastery.of(metas, "压强")
        assertEquals(0.25, kp.score, 1e-9)
        assertEquals(4, kp.totalReviews)
        assertEquals(Mastery.Level.RED, kp.level)
    }

    @Test
    fun `of-知识点不存在返回中性`() {
        val kp = Mastery.of(emptyList(), "不存在的知识点")
        assertEquals(0.5, kp.score, 1e-9)
        assertEquals(0, kp.totalReviews)
        assertEquals(Mastery.Level.YELLOW, kp.level)   // 0.5 → 黄
    }

    @Test
    fun `of-全对绿色全错红色`() {
        val allGood = Mastery.of(listOf(meta("a", listOf("K"), reviews = listOf(review(2), review(3)))), "K")
        assertEquals(Mastery.Level.GREEN, allGood.level)
        val allBad = Mastery.of(listOf(meta("b", listOf("K"), reviews = listOf(review(0), review(1)))), "K")
        assertEquals(Mastery.Level.RED, allBad.level)
    }
}
