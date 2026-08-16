package com.misoup.app.ai.mastery

import com.misoup.app.data.model.Review
import com.misoup.app.makeMeta
import com.misoup.app.review.stats.Mastery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T06 薄弱知识点聚合（纯逻辑，复用模块 03 Mastery）：
 * - 空库 → 空；单知识点；多知识点升序 top5
 * - 未复习知识点 0.5 中性分参与排序；复习少者排前
 * - 关联错题列表 / 点击直达 id
 */
class WeakKpAggregatorTest {

    /** 构造错题：某知识点 + 复习记录（2=Good 3=Easy 算对） */
    private fun meta(id: String, kp: String, vararg ratings: Int) = makeMeta(
        id = id,
        subject = "数学",
        knowledgePoints = listOf(kp),
        reviews = ratings.map { Review(date = "2026-08-16T10:00:00+08:00", rating = it) }
    )

    @Test
    fun `空库-空列表`() {
        assertTrue(WeakKpAggregator.topWeak(emptyList()).isEmpty())
    }

    @Test
    fun `单知识点-掌握度与档位`() {
        // 2 次复习 1 对 → 0.5 → 黄
        val kp = WeakKpAggregator.topWeak(listOf(meta("a", "二次函数", 2, 0)), limit = 5)
        assertEquals(1, kp.size)
        assertEquals("二次函数", kp[0].knowledgePoint)
        assertEquals(0.5, kp[0].score, 1e-9)
        assertEquals(Mastery.Level.YELLOW, kp[0].level)
        assertEquals(2, kp[0].totalReviews)
        assertEquals(listOf("a"), kp[0].entryIds)
    }

    @Test
    fun `多知识点-按掌握度升序-薄弱在前`() {
        val metas = listOf(
            meta("a", "二次函数", 0, 0),          // 0.0 红
            meta("b", "三角函数", 2, 2, 3),        // 1.0 绿
            meta("c", "数列", 2, 0),               // 0.5 黄
        )
        val top = WeakKpAggregator.topWeak(metas, limit = 5)
        assertEquals(listOf("二次函数", "数列", "三角函数"), top.map { it.knowledgePoint })
        assertEquals(Mastery.Level.RED, top[0].level)
        assertEquals(Mastery.Level.YELLOW, top[1].level)
        assertEquals(Mastery.Level.GREEN, top[2].level)
    }

    @Test
    fun `limit-只取前N`() {
        val metas = listOf(
            meta("a", "A", 0),
            meta("b", "B", 0),
            meta("c", "C", 0),
            meta("d", "D", 0),
            meta("e", "E", 0),
            meta("f", "F", 0),
        )
        assertEquals(5, WeakKpAggregator.topWeak(metas, limit = 5).size)
        assertEquals(3, WeakKpAggregator.topWeak(metas, limit = 3).size)
        assertTrue(WeakKpAggregator.topWeak(metas, limit = 0).isEmpty())
        assertTrue(WeakKpAggregator.topWeak(metas, limit = -1).isEmpty())
    }

    @Test
    fun `未复习知识点-中性0_5-不垫底`() {
        val metas = listOf(
            meta("a", "未复习", ),                 // 无复习 → 0.5
            meta("b", "掌握差", 0, 0),             // 0.0
        )
        val top = WeakKpAggregator.topWeak(metas)
        assertEquals("掌握差", top[0].knowledgePoint)   // 0.0 在前
        assertEquals(0.5, top[1].score, 1e-9)
        assertEquals(0, top[1].totalReviews)
    }

    @Test
    fun `复习次数少者-同分排前`() {
        val metas = listOf(
            meta("a", "A", 2, 0),        // 0.5，2 次复习
            meta("b", "B", 2),           // 1.0，1 次复习
            meta("c", "C", 2, 2),        // 1.0，2 次复习
        )
        val top = WeakKpAggregator.topWeak(metas)
        assertEquals(listOf("A", "B", "C"), top.map { it.knowledgePoint })
    }

    @Test
    fun `一题多知识点-各自贡献`() {
        val multi = makeMeta(
            id = "m",
            subject = "数学",
            knowledgePoints = listOf("二次函数", "配方"),
            reviews = listOf(Review(date = "2026-08-16T10:00:00+08:00", rating = 2))
        )
        val top = WeakKpAggregator.topWeak(listOf(multi))
        assertEquals(2, top.size)
        top.forEach { assertEquals(listOf("m"), it.entryIds) }
        assertEquals(1.0, top[0].score, 1e-9)
    }

    @Test
    fun `relatedEntries-按知识点过滤-保持顺序`() {
        val metas = listOf(
            meta("a", "二次函数"),
            meta("b", "三角函数"),
            meta("c", "二次函数"),
        )
        val related = WeakKpAggregator.relatedEntries(metas, "二次函数")
        assertEquals(listOf("a", "c"), related.map { it.id })
        assertTrue(WeakKpAggregator.relatedEntries(metas, "不存在的").isEmpty())
    }

    @Test
    fun `分数边界-档位判定-复用Mastery`() {
        // 0.4 边界 → 黄；0.7 边界 → 绿（与模块 03 一致）
        val metas = listOf(
            meta("a", "X", 2, 2, 0),       // 2/3 ≈ 0.667 黄
            meta("b", "Y", 2, 2, 2, 2, 0), // 4/5 = 0.8 绿
        )
        val top = WeakKpAggregator.topWeak(metas)
        assertEquals(Mastery.Level.YELLOW, top.first { it.knowledgePoint == "X" }.level)
        assertEquals(Mastery.Level.GREEN, top.first { it.knowledgePoint == "Y" }.level)
    }
}
