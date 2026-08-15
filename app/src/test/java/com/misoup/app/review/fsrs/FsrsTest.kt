package com.misoup.app.review.fsrs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T01/T02 FSRS-4.5 算法单测
 *
 * 覆盖：
 * - 新卡 4 连 Good → due 间隔单调递增（验收标准）
 * - Again → state=1(学) 且 interval 缩短（验收标准）
 * - 20 组评分序列 vs fsrs4anki 参考值误差 <5%（参考值由 build/ref/gen_ref.py 生成，
 *   公式照抄 fsrs_optimizer v4.18.0 / fsrs4anki v4.10.0，即标准 FSRS-4.5 参数）
 * - 幂等：同一 (card, grade, params, now) 重复调用结果一致
 * - 边界：非法 grade / 非法 w 长度 / 提前复习 elapsed 钳 0 / stability·difficulty 钳制
 * - 失败路径：状态机全迁移（0新/1学/2熟/3重学 × 4 档）、lapses 计数
 */
class FsrsTest {

    private val DAY = 86_400_000L

    // ---------- 验收标准 1：新卡 4 连 Good → due 间隔单调递增 ----------

    @Test
    fun `新卡4连Good-间隔单调递增且状态变已掌握`() {
        var card = Fsrs.Card()                       // 新卡
        var now = 0L
        val intervals = mutableListOf<Long>()
        repeat(4) {
            card = Fsrs.review(card, Fsrs.GRADE_GOOD, now = now)
            intervals += card.due - now
            now = card.due
        }
        // 间隔严格递增（3.17d → 8.47d → 22d → 49d）
        assertTrue("间隔应单调递增: $intervals", intervals.zipWithNext().all { (a, b) -> b > a })
        assertEquals(Fsrs.STATE_REVIEW, card.state)
        assertEquals(4, card.reps)
        assertEquals(0, card.lapses)
    }

    // ---------- 验收标准 2：Again → state=1 且 interval 缩短 ----------

    @Test
    fun `已掌握卡Again-状态变重学且间隔显著缩短`() {
        // 先 3 连 Good 建立稳定状态
        var card = Fsrs.Card()
        var now = 0L
        repeat(3) {
            card = Fsrs.review(card, Fsrs.GRADE_GOOD, now = now)
            now = card.due
        }
        val stableInterval = card.due - card.lastReview!!   // 上一复习间隔（due - 上次复习时刻）
        card = Fsrs.review(card, Fsrs.GRADE_AGAIN, now = now)
        assertEquals(Fsrs.STATE_RELEARNING, card.state)
        assertEquals(1, card.lapses)
        // Again 后 interval = s_after × 0.25，远小于上一间隔
        val againInterval = card.due - now
        assertTrue("Again 间隔($againInterval) 应短于上一间隔($stableInterval)", againInterval < stableInterval)
    }

    @Test
    fun `新卡Again-状态变学习中且间隔缩短`() {
        val card = Fsrs.review(Fsrs.Card(), Fsrs.GRADE_AGAIN, now = 0L)
        assertEquals(Fsrs.STATE_LEARNING, card.state)
        // w0=0.40255 × 0.25 ≈ 0.1 天 → 数小时内重学
        assertTrue(card.due < DAY)
    }

    // ---------- 验收标准 3：20 组序列 vs fsrs4anki 参考值 <5% ----------

    /** 与 gen_ref.py 的 play() 语义一致：按时（now=due）复习，记录每步 */
    private fun play(seq: LongArray): Fsrs.Card {
        var card = Fsrs.Card()
        var now = 0L
        for (g in seq) {
            card = Fsrs.review(card, g.toInt(), now = now)
            now = card.due
        }
        return card
    }

    // 参考值：由 build/ref/gen_ref.py 生成（fsrs_optimizer v4.18.0 公式，标准 FSRS-4.5 参数）。
    // 每组 [state, stability, difficulty, reps, lapses, intervalDays]
    private val SEQS = arrayOf(
        longArrayOf(3, 3, 3, 3), longArrayOf(1, 3, 3, 3), longArrayOf(3, 1, 3, 3),
        longArrayOf(3, 3, 1, 3), longArrayOf(3, 3, 3, 1), longArrayOf(4, 4, 4),
        longArrayOf(2, 2, 2, 2), longArrayOf(1, 1, 1), longArrayOf(3, 4, 3, 4),
        longArrayOf(2, 3, 4, 2, 3), longArrayOf(3, 3, 3, 3, 3, 3, 3),
        longArrayOf(1, 4, 3, 3), longArrayOf(4, 1, 3, 3), longArrayOf(2, 2, 3, 3, 3),
        longArrayOf(3, 2, 3, 2, 3), longArrayOf(1, 2, 3, 4, 3), longArrayOf(3, 3, 1, 1, 3),
        longArrayOf(4, 3, 2, 1, 3, 3), longArrayOf(2, 4, 3, 3, 3, 3), longArrayOf(3, 1, 3, 1, 3, 3)
    )
    private val REFS = arrayOf(
        doubleArrayOf(2.0, 49.0801, 7.1949, 4.0, 0.0, 49.0801),
        doubleArrayOf(2.0, 3.2501, 8.2492, 4.0, 0.0, 3.2501),
        doubleArrayOf(2.0, 1.7495, 9.9743, 4.0, 1.0, 1.7495),
        doubleArrayOf(2.0, 2.0776, 9.9871, 4.0, 1.0, 2.0776),
        doubleArrayOf(3.0, 2.9354, 10.0, 4.0, 1.0, 0.7338),
        doubleArrayOf(2.0, 811.3053, 3.7646, 3.0, 0.0, 1054.6969),
        doubleArrayOf(2.0, 1.95, 10.0, 4.0, 0.0, 1.56),
        doubleArrayOf(1.0, 0.1, 10.0, 3.0, 0.0, 0.025),
        doubleArrayOf(2.0, 373.8802, 4.3009, 4.0, 0.0, 486.0443),
        doubleArrayOf(2.0, 42.5504, 7.7263, 5.0, 0.0, 42.5504),
        doubleArrayOf(2.0, 454.2935, 7.1949, 7.0, 0.0, 454.2935),
        doubleArrayOf(2.0, 9.3567, 6.8089, 4.0, 0.0, 9.3567),
        doubleArrayOf(2.0, 5.191, 9.5484, 4.0, 1.0, 5.191),
        doubleArrayOf(2.0, 8.449, 9.1533, 5.0, 0.0, 8.449),
        doubleArrayOf(2.0, 12.6228, 9.9871, 5.0, 0.0, 12.6228),
        doubleArrayOf(2.0, 5.5489, 8.2311, 5.0, 0.0, 5.5489),
        doubleArrayOf(2.0, 0.6638, 9.9871, 5.0, 1.0, 0.6638),
        doubleArrayOf(2.0, 7.062, 9.9743, 6.0, 1.0, 7.062),
        doubleArrayOf(2.0, 308.6647, 6.2901, 6.0, 0.0, 308.6647),
        doubleArrayOf(2.0, 0.8587, 9.9743, 6.0, 2.0, 0.8587)
    )

    @Test
    fun `20组序列-与fsrs4anki参考值误差小于5百分比`() {
        SEQS.indices.forEach { i ->
            val card = play(SEQS[i])
            val ref = REFS[i]
            val ivlDays = (card.due - card.lastReview!!).toDouble() / DAY
            assertEquals("seq${i + 1} state", ref[0].toInt(), card.state)
            assertEquals("seq${i + 1} reps", ref[3].toInt(), card.reps)
            assertEquals("seq${i + 1} lapses", ref[4].toInt(), card.lapses)
            assertRelErr("seq${i + 1} stability", ref[1], card.stability)
            assertRelErr("seq${i + 1} difficulty", ref[2], card.difficulty)
            assertRelErr("seq${i + 1} intervalDays", ref[5], ivlDays)
        }
    }

    /** 相对误差断言：|实际-参考|/参考 < 5% */
    private fun assertRelErr(label: String, expected: Double, actual: Double) {
        val rel = if (expected == 0.0) {
            if (actual == 0.0) 0.0 else Double.MAX_VALUE
        } else {
            kotlin.math.abs(actual - expected) / kotlin.math.abs(expected)
        }
        assertTrue("$label 误差 ${"%.4f".format(rel * 100)}% 超 5%（期望 $expected 实际 $actual）", rel < 0.05)
    }

    // ---------- 幂等（模块文档要求） ----------

    @Test
    fun `同一输入重复调用-结果完全一致`() {
        val card = play(longArrayOf(3, 3, 1, 3))
        val a = Fsrs.review(card, Fsrs.GRADE_GOOD, now = 1_000_000L)
        val b = Fsrs.review(card, Fsrs.GRADE_GOOD, now = 1_000_000L)
        assertEquals(a, b)
    }

    // ---------- 边界与失败路径 ----------

    @Test
    fun `非法grade-按Again容错不崩`() {
        val card = Fsrs.review(Fsrs.Card(), 99, now = 0L)
        assertEquals(Fsrs.STATE_LEARNING, card.state)   // Again 语义
        val card0 = Fsrs.review(Fsrs.Card(), 0, now = 0L)
        assertEquals(Fsrs.STATE_LEARNING, card0.state)
    }

    @Test
    fun `w长度非17-抛异常`() {
        try {
            Fsrs.review(Fsrs.Card(), Fsrs.GRADE_GOOD, Fsrs.Params(w = doubleArrayOf(1.0)), now = 0L)
            assertTrue("应抛 IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `刚复习完立即再复习-经过时间为0留存率1稳定性不变`() {
        // now == lastReview：elapsed=0 → r=1.0 → 回忆稳定性公式中 (e^((1-r)·w10)-1)=0 → 稳定性不变
        val card = Fsrs.Card(
            state = Fsrs.STATE_REVIEW, due = 1_000_000L,
            stability = 10.0, difficulty = 5.0, reps = 2, lastReview = 500_000L
        )
        val out = Fsrs.review(card, Fsrs.GRADE_GOOD, now = 500_000L)
        assertEquals(10.0, out.stability, 1e-9)
        // 难度仍按 mean reversion 更新：w7·w4 + (1-w7)·D = 0.0046·7.1949 + 0.9954·5.0 ≈ 5.0101
        assertEquals(5.01009654, out.difficulty, 1e-6)
    }

    @Test
    fun `稳定性钳制-上限36500下限0_1`() {
        // 高稳定性连续 Easy 不应无限增长
        var card = Fsrs.Card()
        var now = 0L
        repeat(6) {
            card = Fsrs.review(card, Fsrs.GRADE_EASY, now = now)
            now = card.due
        }
        assertTrue(card.stability <= 36500.0)
        assertTrue(card.stability >= 0.1)
        // 连续 Again 不应低于 0.1
        var c2 = Fsrs.Card()
        var t2 = 0L
        repeat(5) {
            c2 = Fsrs.review(c2, Fsrs.GRADE_AGAIN, now = t2)
            t2 = c2.due
        }
        assertTrue(c2.stability >= 0.1)
    }

    @Test
    fun `状态机全迁移-4状态乘4档位`() {
        // 预期迁移表：行=当前状态 0/1/2/3，列=档位 Again/Hard/Good/Easy
        val expect = mapOf(
            "0-1" to Fsrs.STATE_LEARNING, "0-2" to Fsrs.STATE_REVIEW, "0-3" to Fsrs.STATE_REVIEW, "0-4" to Fsrs.STATE_REVIEW,
            "1-1" to Fsrs.STATE_LEARNING, "1-2" to Fsrs.STATE_REVIEW, "1-3" to Fsrs.STATE_REVIEW, "1-4" to Fsrs.STATE_REVIEW,
            "2-1" to Fsrs.STATE_RELEARNING, "2-2" to Fsrs.STATE_REVIEW, "2-3" to Fsrs.STATE_REVIEW, "2-4" to Fsrs.STATE_REVIEW,
            "3-1" to Fsrs.STATE_RELEARNING, "3-2" to Fsrs.STATE_REVIEW, "3-3" to Fsrs.STATE_REVIEW, "3-4" to Fsrs.STATE_REVIEW,
        )
        for (s in 0..3) {
            for (g in 1..4) {
                val card = Fsrs.Card(state = s, due = 0L, stability = 10.0, difficulty = 5.0, reps = 1, lastReview = 0L)
                val out = Fsrs.review(card, g, now = DAY)   // 复习发生在 due 后 1 天
                assertEquals("state $s grade $g", expect["$s-$g"], out.state)
            }
        }
    }

    @Test
    fun `lapses计数-仅在已掌握卡Again时累加`() {
        // 新卡 Again：lapses 不累加
        var card = Fsrs.review(Fsrs.Card(), Fsrs.GRADE_AGAIN, now = 0L)
        assertEquals(0, card.lapses)
        // 学习中 Again：不累加
        card = Fsrs.review(card, Fsrs.GRADE_AGAIN, now = card.due)
        assertEquals(0, card.lapses)
        // 已掌握 Again：+1
        card = Fsrs.review(card, Fsrs.GRADE_GOOD, now = card.due)
        assertEquals(Fsrs.STATE_REVIEW, card.state)
        card = Fsrs.review(card, Fsrs.GRADE_AGAIN, now = card.due)
        assertEquals(1, card.lapses)
        // 重学中 Again：不再 +1
        card = Fsrs.review(card, Fsrs.GRADE_AGAIN, now = card.due)
        assertEquals(1, card.lapses)
        // 重学 Good 恢复后 Again：+1
        card = Fsrs.review(card, Fsrs.GRADE_GOOD, now = card.due)
        card = Fsrs.review(card, Fsrs.GRADE_AGAIN, now = card.due)
        assertEquals(2, card.lapses)
    }

    @Test
    fun `enableShortTerm关闭-间隔取整天且至少1天`() {
        val params = Fsrs.Params(enableShortTerm = false)
        val card = Fsrs.review(Fsrs.Card(), Fsrs.GRADE_AGAIN, params, now = 0L)
        // Again: 0.40255×0.25=0.1 天 → 取整 0 → 至少 1 天
        assertEquals(DAY, card.due)
        // Good: 3.173 → 3 天
        val g = Fsrs.review(Fsrs.Card(), Fsrs.GRADE_GOOD, params, now = 0L)
        assertEquals(3 * DAY, g.due)
    }

    @Test
    fun `enableShortTerm开启-短间隔保留小数天精度`() {
        val card = Fsrs.review(Fsrs.Card(), Fsrs.GRADE_AGAIN, now = 0L)
        // 0.40255 × 0.25 × 86400000 ≈ 8,695,080 ms（约 2.4 小时，低于 1 天）
        assertTrue(card.due > 0L)
        assertTrue(card.due < DAY)
    }

    @Test
    fun `遗忘曲线-边界值`() {
        assertEquals(1.0, Fsrs.forgettingCurve(0.0, 10.0), 1e-9)          // 刚复习
        assertEquals(0.9, Fsrs.forgettingCurve(10.0, 10.0), 1e-9)         // t=S → (1+1/9)^-1 = 0.9
        assertEquals(1.0, Fsrs.forgettingCurve(5.0, 0.0), 1e-9)           // 非法 S → 1
        assertTrue(Fsrs.forgettingCurve(100.0, 1.0) < 0.1)                // 超期 → 低留存
    }

    @Test
    fun `难度钳制-始终在1到10之间`() {
        var card = Fsrs.Card()
        var now = 0L
        repeat(8) {
            card = Fsrs.review(card, Fsrs.GRADE_HARD, now = now)
            now = card.due
        }
        assertTrue(card.difficulty in 1.0..10.0)
        // 连续 Easy 后难度下降但不低于 1
        var c2 = Fsrs.Card()
        var t2 = 0L
        repeat(8) {
            c2 = Fsrs.review(c2, Fsrs.GRADE_EASY, now = t2)
            t2 = c2.due
        }
        assertTrue(c2.difficulty in 1.0..10.0)
    }

    @Test
    fun `due计算-now加interval乘86400秒`() {
        val card = Fsrs.review(Fsrs.Card(), Fsrs.GRADE_GOOD, now = 1_000L)
        // w2=3.173 天 × 86400000 ms
        assertEquals(1_000L + kotlin.math.round(3.173 * DAY).toLong(), card.due)
    }
}
