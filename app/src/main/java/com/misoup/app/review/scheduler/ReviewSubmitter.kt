package com.misoup.app.review.scheduler

import com.misoup.app.data.model.FsrsState
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.model.Review
import com.misoup.app.data.store.StoreService
import com.misoup.app.review.fsrs.Fsrs
import com.misoup.app.review.fsrs.FsrsTime

/**
 * 复习提交（T06）：Fsrs.review → StoreService.update（reviews[] + fsrs 字段原子写盘）
 *
 * 档位映射（⚠️ 模块 03 定稿）：
 * - Fsrs 内部 grade：1=Again 2=Hard 3=Good 4=Easy
 * - meta.Review.rating：0=Again 1=Hard 2=Good 3=Easy（rating = grade - 1）
 *
 * 幂等与持久化：每次提交追加一条 Review 记录并更新 fsrs 状态，经 StoreService.update
 * （Mutex 串行 + 原子替换）落盘；进程杀后重进，reviews 不丢、队列按新 due 重建。
 */
class ReviewSubmitter(
    private val store: StoreService
) {

    /** 提交结果（提交后的 meta + 关键调度字段，UI 展示/测试断言用） */
    data class SubmitResult(
        val meta: MisoupMeta,
        val stateAfter: Int,
        val intervalDays: Double,
        val newDueEpochMs: Long,
        val rating: Int              // 0-3（已映射）
    )

    /**
     * 提交一次复习。
     *
     * @param id    条目 id
     * @param grade Fsrs 档位 1-4（非法值由 Fsrs 保守按 Again）
     * @param now   复习时刻 epoch ms
     * @return 成功 → [SubmitResult]；失败 → 原因（条目不存在/meta 损坏/写盘失败）
     */
    suspend fun submit(id: String, grade: Int, now: Long, params: Fsrs.Params = Fsrs.Params()): Result<SubmitResult> {
        val rating = (grade.takeIf { it in 1..4 } ?: 1) - 1   // grade 1-4 → rating 0-3
        // F8：读改写原子化——FSRS 计算在 update 的 transform 内完成（基于锁内读到的当前 meta），
        // 并发提交（连点/多端同时复习）时后写基于前写结果，fsrs 与 reviews 永不脱节。
        return store.update(id) { current ->
            val card = toCard(current)
            val next = Fsrs.review(card, grade, params, now)
            current.copy(
                fsrs = fsrsOf(next, now),
                reviews = current.reviews + reviewOf(next, now, rating)
            )
        }.map {
            val meta = store.readMetaById(id).getOrThrow()   // 读回磁盘最新（含更新后）
            val dueMs = FsrsTime.toEpochMs(meta.fsrs.due) ?: now
            SubmitResult(
                meta = meta,
                stateAfter = meta.fsrs.state,
                intervalDays = (dueMs - now).toDouble() / Fsrs.DAY_MS,
                newDueEpochMs = dueMs,
                rating = rating
            )
        }
    }

    /** FSRS 计算后状态 → meta.fsrs 字段 */
    private fun fsrsOf(next: Fsrs.Card, now: Long): FsrsState = FsrsState(
        state = next.state,
        due = FsrsTime.toIso(next.due),
        stability = next.stability,
        difficulty = next.difficulty,
        reps = next.reps,
        lapses = next.lapses,
        lastReview = FsrsTime.toIso(now)
    )

    /** FSRS 计算后 → 单条复习记录 */
    private fun reviewOf(next: Fsrs.Card, now: Long, rating: Int): Review = Review(
        date = FsrsTime.toIso(now) ?: "",
        rating = rating,
        stateAfter = next.state,
        intervalDays = (next.due - now).toDouble() / Fsrs.DAY_MS
    )

    /** meta.fsrs → Fsrs.Card（due/lastReview 字符串 → epoch；解析失败按新卡兜底） */
    private fun toCard(meta: MisoupMeta): Fsrs.Card {
        val f = meta.fsrs
        val due = FsrsTime.toEpochMs(f.due) ?: 0L
        val last = FsrsTime.toEpochMs(f.lastReview)
        return Fsrs.Card(
            state = f.state,
            due = due,
            stability = f.stability,
            difficulty = f.difficulty,
            reps = f.reps,
            lapses = f.lapses,
            lastReview = last
        )
    }
}

/**
 * 复习提醒调度器（V2 占位，模块 03 定稿）：
 * 本模块只做应用内横幅/角标，不做系统通知；WorkManager 每日提醒放模块 10（V2）。
 * 接口保留，V2 实现注入系统通知。
 */
interface ReminderScheduler {
    /** 注册每日提醒（V2 实现：WorkManager + POST_NOTIFICATIONS） */
    fun scheduleDaily(now: Long)

    /** 取消每日提醒 */
    fun cancel()
}
