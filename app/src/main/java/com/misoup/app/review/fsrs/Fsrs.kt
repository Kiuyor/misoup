package com.misoup.app.review.fsrs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * FSRS-4.5 调度算法自实现（纯函数、无外部依赖、幂等）
 *
 * 公式照抄 fsrs4anki 开源实现（fsrs_optimizer v4.18.0 / fsrs4anki v4.10.0，即 FSRS-4.5 论文
 * "A Stochastic Short-Term Model for Reinforcing Memory" 的官方参数与公式），不引第三方库。
 *
 * 与模块 03 文档的对应关系：
 * - grade: 1=Again 2=Hard 3=Good 4=Easy（⚠️ 与 meta.Review.rating 的 0-3 编码不同，
 *   换算在提交层 ReviewSubmitter 完成：rating = grade - 1）
 * - state: 0=新卡 1=学习中 2=已掌握 3=重学（与 meta.fsrs.state / FsrsCardState.code 一致）
 * - interval = stability × interval modifier（Good 1.0 / Hard 0.8 / Easy 1.3；Again 0.25 短时重学）
 * - due = now + interval × 86400s
 *
 * 文档裁决记录：
 * 1. Card 增加 [lastReview]（上次复习的 epoch ms，可为 null）。FSRS 的遗忘曲线 R(t,S) 需要
 *    "距上次复习的经过时间"；meta.fsrs.lastReview 已持久化该字段，Card 携带它才能正确计算。
 *    复习恰好发生在 due 时刻时，elapsed = 上一间隔，R = 9/10（幂等、单调递增可验证）。
 * 2. 模块文档未给 Again 的 interval modifier。裁决：0.25（失败后短时重学，间隔显著缩短，
 *    满足验收标准 "Again → interval 缩短"；对应 Anki 的 relearning step 语义）。
 * 3. enableShortTerm=true（默认）：间隔保留小数天（短间隔可低于 1 天，如重学约数小时）；
 *    enableShortTerm=false：间隔四舍五入到整天且至少 1 天（Anki 式天粒度调度）。
 */
object Fsrs {

    /** 卡片状态：0=新卡 1=学习中 2=已掌握 3=重学 */
    const val STATE_NEW = 0
    const val STATE_LEARNING = 1
    const val STATE_REVIEW = 2
    const val STATE_RELEARNING = 3

    /** 档位：1=Again 2=Hard 3=Good 4=Easy */
    const val GRADE_AGAIN = 1
    const val GRADE_HARD = 2
    const val GRADE_GOOD = 3
    const val GRADE_EASY = 4

    /** 一天 = 86400s = 86_400_000ms */
    const val DAY_MS = 86_400_000L

    /** 遗忘曲线系数（FSRS-4.5 定稿：R(t,S) = (1 + t/(9S))^-1，见 fsrs_optimizer.power_forgetting_curve） */
    private const val FORGETTING_CURVE_NINE = 9.0

    /** stability 钳制区间 [0.1, 36500]（fsrs_optimizer 同款） */
    private const val MIN_STABILITY = 0.1
    private const val MAX_STABILITY = 36500.0

    /**
     * FSRS-4.5 默认参数 17 个（fsrs4anki 初始值，存 settings 后可调）。
     * 索引约定（与 fsrs_optimizer 一致）：
     * w[0..3] 首学 4 档初始 stability；w[4] 初始难度；w[5] 难度-档位斜率；
     * w[6] 难度更新斜率；w[7] mean reversion 权重；w[8..10] 回忆稳定性公式；
     * w[11..14] 遗忘稳定性公式；w[15] Hard 惩罚；w[16] Easy 奖励。
     */
    val DEFAULT_W = doubleArrayOf(
        0.40255, 1.18385, 3.173, 15.69105,   // w0..3 初始稳定性
        7.1949, 0.5345, 1.4604, 0.0046,      // w4..7 难度
        1.54575, 0.1192, 1.01925,            // w8..10 回忆
        1.9395, 0.11, 0.29605, 2.2698,       // w11..14 遗忘
        0.2315, 2.9898                       // w15 Hard 惩罚 / w16 Easy 奖励
    )

    /**
     * 调度参数。[w] 可用 settings 页覆盖；[enableShortTerm] 控制短间隔精度。
     * 幂等：同一 (card, grade, params, now) 重复调用结果完全一致（纯函数）。
     */
    data class Params(
        val w: DoubleArray = DEFAULT_W,
        val enableShortTerm: Boolean = true
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Params) return false
            return enableShortTerm == other.enableShortTerm && w.contentEquals(other.w)
        }

        override fun hashCode(): Int = 31 * w.contentHashCode() + enableShortTerm.hashCode()
    }

    /** 复习卡（与 meta.fsrs 一一映射；[lastReview] 为上次复习 epoch ms，首次复习为 null） */
    data class Card(
        val state: Int = STATE_NEW,
        val due: Long = 0L,
        val stability: Double = 0.0,
        val difficulty: Double = 0.0,
        val reps: Int = 0,
        val lapses: Int = 0,
        val lastReview: Long? = null
    )

    /**
     * 一次复习后的新卡状态（纯函数、幂等）。
     *
     * @param card  复习前的卡（新卡 state=0 时 stability/difficulty 忽略）
     * @param grade 1=Again 2=Hard 3=Good 4=Easy；非法值按 Again 处理（容错不崩）
     * @param params 调度参数（默认 FSRS-4.5 初始 17 参数）
     * @param now   复习时刻（epoch ms）
     * @return 复习后的卡：state 按状态机迁移、due = now + interval×86400s、
     *         stability/difficulty 按 FSRS-4.5 公式更新、reps+1、lapses 按规则累加
     */
    fun review(card: Card, grade: Int, params: Params = Params(), now: Long): Card {
        val g = if (grade in GRADE_AGAIN..GRADE_EASY) grade else GRADE_AGAIN   // 非法档位保守按 Again
        val w = params.w
        require(w.size == 17) { "FSRS-4.5 需要 17 个参数，实际 ${w.size} 个" }

        val newState: Int
        val newStability: Double
        val newDifficulty: Double

        if (card.state == STATE_NEW) {
            // 首学：初始 stability = w[grade-1]；初始难度无 mean reversion（fsrs_optimizer 同款）
            newStability = clampStability(max(w[g - 1], MIN_STABILITY))
            newDifficulty = constrainDifficulty(w[4] - w[5] * (g - 3))
        } else {
            // 距上次复习的经过时间（天）；首次数据缺失回退 due 时刻
            val last = card.lastReview ?: card.due
            val elapsedDays = max(0.0, (now - last).toDouble() / DAY_MS)
            val r = forgettingCurve(elapsedDays, card.stability)

            if (g == GRADE_AGAIN) {
                // 遗忘稳定性：w11 * D^-w12 * ((S+1)^w13 - 1) * e^((1-R)*w14)，上限为当前 S
                newStability = clampStability(
                    min(
                        w[11] * card.difficulty.pow(-w[12]) *
                            ((card.stability + 1.0).pow(w[13]) - 1.0) *
                            exp((1.0 - r) * w[14]),
                        card.stability
                    )
                )
            } else {
                // 回忆稳定性：S * (1 + e^w8 * (11-D) * S^-w9 * (e^((1-R)*w10) - 1) * hardPenalty * easyBonus)
                val hardPenalty = if (g == GRADE_HARD) w[15] else 1.0
                val easyBonus = if (g == GRADE_EASY) w[16] else 1.0
                newStability = clampStability(
                    card.stability * (
                        1.0 + exp(w[8]) * (11.0 - card.difficulty) *
                            card.stability.pow(-w[9]) *
                            (exp((1.0 - r) * w[10]) - 1.0) *
                            hardPenalty * easyBonus
                        )
                )
            }
            // 难度更新：mean reversion(w4, D - w6*(g-3))，钳制 [1,10]
            newDifficulty = constrainDifficulty(
                w[7] * w[4] + (1.0 - w[7]) * (card.difficulty - w[6] * (g - 3))
            )
        }

        // 状态机：0新 1学 2熟 3重学
        newState = when (card.state) {
            STATE_NEW -> if (g == GRADE_AGAIN) STATE_LEARNING else STATE_REVIEW
            STATE_LEARNING -> if (g == GRADE_AGAIN) STATE_LEARNING else STATE_REVIEW
            STATE_REVIEW -> if (g == GRADE_AGAIN) STATE_RELEARNING else STATE_REVIEW
            STATE_RELEARNING -> if (g == GRADE_AGAIN) STATE_RELEARNING else STATE_REVIEW
            else -> card.state
        }

        // interval = stability × modifier（Again 0.25 短时重学 / Hard 0.8 / Good 1.0 / Easy 1.3）
        val intervalDays = newStability * intervalModifier(g)
        val intervalMs: Long = if (params.enableShortTerm) {
            (intervalDays * DAY_MS).roundToLong().coerceAtLeast(1L)
        } else {
            (kotlin.math.round(intervalDays).toLong()).coerceAtLeast(1L) * DAY_MS
        }

        return Card(
            state = newState,
            due = now + intervalMs,
            stability = newStability,
            difficulty = newDifficulty,
            reps = card.reps + 1,
            // lapses：仅"已掌握(2) → Again"计数（Anki 语义：离开 review 状态才算一次遗忘）
            lapses = card.lapses + if (card.state == STATE_REVIEW && g == GRADE_AGAIN) 1 else 0,
            lastReview = now
        )
    }

    /** interval modifier：Again 0.25（短时重学）/ Hard 0.8 / Good 1.0 / Easy 1.3 */
    fun intervalModifier(grade: Int): Double = when (grade) {
        GRADE_AGAIN -> 0.25
        GRADE_HARD -> 0.8
        GRADE_GOOD -> 1.0
        GRADE_EASY -> 1.3
        else -> 1.0
    }

    /** 幂等遗忘曲线 R(t, S) = (1 + t/(9S))^-1（fsrs_optimizer.power_forgetting_curve 同款） */
    fun forgettingCurve(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 1.0   // 防御：非法稳定性按"刚复习过"处理
        return (1.0 + elapsedDays / (FORGETTING_CURVE_NINE * stability)).pow(-1.0)
    }

    /** 难度钳制 [1,10]（与 fsrs4anki constrain_difficulty 一致） */
    fun constrainDifficulty(d: Double): Double = d.coerceIn(1.0, 10.0)

    /** 稳定性钳制 [0.1, 36500] */
    fun clampStability(s: Double): Double = s.coerceIn(MIN_STABILITY, MAX_STABILITY)
}
