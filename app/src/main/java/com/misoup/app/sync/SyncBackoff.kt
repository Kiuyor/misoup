package com.misoup.app.sync

/**
 * 失败指数退避策略（模块 09 T05）：30s → 2m → 10m，之后封顶 10m
 *
 * 纯逻辑可测：attempt 从 0 开始（首次失败 → 30s），成功后调用方重置。
 */
class SyncBackoff(
    private val delays: List<Long> = DEFAULT_DELAYS
) {

    init {
        require(delays.isNotEmpty()) { "退避序列不能为空" }
        require(delays.all { it > 0 }) { "退避时长必须为正" }
    }

    /** 第 [attempt] 次失败后的等待时长（毫秒）；超过序列长度封顶最后一项 */
    fun delayFor(attempt: Int): Long {
        require(attempt >= 0) { "attempt 不能为负" }
        return delays[minOf(attempt, delays.size - 1)]
    }

    /** 下一次尝试编号（调用方在每次失败后 +1） */
    fun nextAttempt(attempt: Int): Int = attempt + 1

    companion object {
        /** 文档定稿：30s → 2m → 10m */
        val DEFAULT_DELAYS = listOf(30_000L, 120_000L, 600_000L)
    }
}
