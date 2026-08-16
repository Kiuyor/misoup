package com.misoup.app.sync

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AutoSyncCoordinator（F2 完善：单飞 + 指数退避 + 开关控制 + 状态反馈） */
class AutoSyncCoordinatorTest {

    private fun ok() = SyncReport(uploaded = 1)
    private fun fail() = SyncReport(errors = listOf("boom"))

    @Test
    fun `开关关闭时不触发`() = runTest {
        var calls = 0
        val c = AutoSyncCoordinator(this, { calls++; ok() }, { false })
        c.triggerIfEnabled()
        runCurrent()
        assertEquals(0, calls)
        assertFalse(c.state.value.triggered)
    }

    @Test
    fun `成功同步发布结果且不再运行`() = runTest {
        val c = AutoSyncCoordinator(this, { ok() }, { true })
        c.triggerIfEnabled()
        runCurrent()
        assertTrue(c.state.value.triggered)
        assertTrue(c.state.value.lastResult?.isSuccess == true)
        assertFalse(c.state.value.running)
    }

    @Test
    fun `单飞-在跑时重复触发被忽略`() = runTest {
        var calls = 0
        // 第一次同步用 delay 挂起，制造「仍在跑」窗口
        val c = AutoSyncCoordinator(
            scope = this,
            sync = {
                calls++
                kotlinx.coroutines.delay(10_000)
                ok()
            },
            enabled = { true },
            backoff = SyncBackoff(listOf(1_000L))
        )
        c.triggerIfEnabled()
        runCurrent()
        c.triggerIfEnabled()   // 在跑 → 忽略
        runCurrent()
        assertEquals(1, calls)
        advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test
    fun `失败按退避重试后成功`() = runTest {
        var calls = 0
        val c = AutoSyncCoordinator(
            scope = this,
            sync = { calls++; if (calls < 3) fail() else ok() },
            enabled = { true },
            backoff = SyncBackoff(listOf(1_000L, 2_000L))
        )
        c.triggerIfEnabled()
        advanceTimeBy(3_000)   // 首次失败等 1000ms + 再次失败等 2000ms
        runCurrent()
        assertEquals(3, calls)
        assertTrue(c.state.value.lastResult?.isSuccess == true)
        assertFalse(c.state.value.running)
    }

    @Test
    fun `失败达上限停止`() = runTest {
        var calls = 0
        val c = AutoSyncCoordinator(
            scope = this,
            sync = { calls++; fail() },
            enabled = { true },
            backoff = SyncBackoff(listOf(1_000L)),
            maxRetries = 2
        )
        c.triggerIfEnabled()
        advanceUntilIdle()
        assertEquals(3, calls)               // 初始 + 2 次退避重试
        assertTrue(c.state.value.triggered)  // 终止状态重置 retries/nextRetryInMs，仅保留结果与 triggered
        assertFalse(c.state.value.running)
        assertFalse(c.state.value.lastResult?.isSuccess == true)
    }

    @Test
    fun `开关关闭后停止后续重试`() = runTest {
        var enabled = true
        var calls = 0
        val c = AutoSyncCoordinator(
            scope = this,
            sync = { calls++; fail() },
            enabled = { enabled },
            backoff = SyncBackoff(listOf(1_000L))
        )
        c.triggerIfEnabled()
        runCurrent()
        enabled = false
        advanceUntilIdle()
        // 已排程的一次退避重试会照常执行（调用 2 次），随后检查开关 → 停止，不再无限重试
        assertEquals(2, calls)
        assertFalse(c.state.value.running)
        assertFalse(c.state.value.lastResult?.isSuccess == true)
    }

    @Test
    fun `cancel-立即放弃在跑重试`() = runTest {
        var calls = 0
        val c = AutoSyncCoordinator(
            scope = this,
            sync = { calls++; fail() },
            enabled = { true },
            backoff = SyncBackoff(listOf(1_000L))
        )
        c.triggerIfEnabled()
        runCurrent()
        c.cancel()
        advanceUntilIdle()
        assertEquals(1, calls)               // 仅初始一次；cancel 终止后续退避
        assertFalse(c.state.value.running)
        assertFalse(c.state.value.nextRetryInMs != null)
    }
}