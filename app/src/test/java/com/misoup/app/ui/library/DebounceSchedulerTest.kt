package com.misoup.app.ui.library

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T03 搜索防抖：300ms 窗口内重复输入只触发最后一次 */
class DebounceSchedulerTest {

    private fun scheduler(scope: kotlinx.coroutines.CoroutineScope) =
        DebounceScheduler(debounceMs = 300, scope = scope)

    @Test
    fun `300ms 内不触发-到达 300ms 触发`() = runTest {
        val settled = mutableListOf<String>()
        val s = scheduler(this)
        s.submit("二次函数") { settled += it }
        advanceTimeBy(299)
        assertTrue(settled.isEmpty())
        advanceTimeBy(1)
        runCurrent()   // 执行恰好排定在当前虚拟时刻的任务
        assertEquals(listOf("二次函数"), settled)
    }

    @Test
    fun `连续输入-只触发最后一次`() = runTest {
        val settled = mutableListOf<String>()
        val s = scheduler(this)
        s.submit("二") { settled += it }
        advanceTimeBy(100)
        s.submit("二次") { settled += it }
        advanceTimeBy(100)
        s.submit("二次函数") { settled += it }
        advanceTimeBy(299)
        assertTrue(settled.isEmpty())          // 前两次已被取消
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("二次函数"), settled)
    }

    @Test
    fun `输入后立即取消-不触发`() = runTest {
        val settled = mutableListOf<String>()
        val s = scheduler(this)
        s.submit("x") { settled += it }
        s.cancel()
        advanceUntilIdle()
        assertTrue(settled.isEmpty())
    }

    @Test
    fun `空白输入也会回调-由调用方决定是否清搜索`() = runTest {
        val settled = mutableListOf<String>()
        val s = scheduler(this)
        s.submit("") { settled += it }
        advanceUntilIdle()
        assertEquals(listOf(""), settled)
    }

    @Test
    fun `多个调度器互不影响`() = runTest {
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        val s1 = scheduler(this)
        val s2 = DebounceScheduler(debounceMs = 500, scope = this)
        s1.submit("甲") { a += it }
        s2.submit("乙") { b += it }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("甲"), a)
        assertTrue(b.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("乙"), b)
    }
}
