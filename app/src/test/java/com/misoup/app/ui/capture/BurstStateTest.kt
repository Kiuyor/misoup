package com.misoup.app.ui.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T03 连拍状态机（纯逻辑）：start/next/abort、边界钳制、done 判定
 */
class BurstStateTest {

    @Test
    fun `start-默认3张-开始拍摄`() {
        val s = BurstState.start()
        assertEquals(3, s.total)
        assertEquals(0, s.taken)
        assertEquals(3, s.remaining)
        assertTrue(s.capturing)
        assertFalse(s.done)
    }

    @Test
    fun `start-非法张数钳制-小于1回退1-大于9封顶9`() {
        assertEquals(1, BurstState.start(0).total)
        assertEquals(1, BurstState.start(-5).total)
        assertEquals(9, BurstState.start(100).total)
    }

    @Test
    fun `next-逐张累加-不超total`() {
        var s = BurstState.start(3)
        s = BurstState.next(s)
        assertEquals(1, s.taken)
        assertEquals(2, s.remaining)
        s = BurstState.next(s)
        s = BurstState.next(s)
        assertEquals(3, s.taken)
        assertTrue(s.done)
        assertEquals(0, s.remaining)
        // 防溢出：继续 next 不超 total
        s = BurstState.next(s)
        assertEquals(3, s.taken)
    }

    @Test
    fun `abort-中止拍摄-计数保留`() {
        var s = BurstState.start(3)
        s = BurstState.next(s)
        s = BurstState.abort(s)
        assertFalse(s.capturing)
        assertEquals(1, s.taken)
        assertFalse(s.done)
    }

    @Test
    fun `连拍流程-3张完整流转`() {
        var s = BurstState.start(3)
        repeat(3) { s = BurstState.next(s) }
        assertTrue(s.done)
        assertTrue(s.capturing)   // done 后 capturing 仍为 true（由 UI 决定是否提交）
        assertEquals(0, s.remaining)
    }
}
