package com.misoup.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** 失败指数退避策略（模块 09 T05）：30s → 2m → 10m 封顶 */
class SyncBackoffTest {

    private val backoff = SyncBackoff()

    @Test
    fun `首次失败 - 30 秒`() {
        assertEquals(30_000L, backoff.delayFor(0))
    }

    @Test
    fun `第二次失败 - 2 分钟`() {
        assertEquals(120_000L, backoff.delayFor(1))
    }

    @Test
    fun `第三次失败 - 10 分钟`() {
        assertEquals(600_000L, backoff.delayFor(2))
    }

    @Test
    fun `超过序列 - 封顶 10 分钟不再增长`() {
        assertEquals(600_000L, backoff.delayFor(3))
        assertEquals(600_000L, backoff.delayFor(10))
        assertEquals(600_000L, backoff.delayFor(999))
    }

    @Test
    fun `attempt 递增`() {
        assertEquals(1, backoff.nextAttempt(0))
        assertEquals(2, backoff.nextAttempt(1))
    }

    @Test
    fun `自定义序列`() {
        val custom = SyncBackoff(listOf(1_000L, 5_000L))
        assertEquals(1_000L, custom.delayFor(0))
        assertEquals(5_000L, custom.delayFor(1))
        assertEquals(5_000L, custom.delayFor(2))   // 封顶
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空序列 - 拒绝构造`() {
        SyncBackoff(emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负 attempt - 拒绝`() {
        backoff.delayFor(-1)
    }
}
