package com.misoup.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T06 遮罩开关：toggle / 揭开进度钳制 / 可见性阈值 */
class MaskStateTest {

    @Test
    fun `初始-未隐藏-无遮罩`() {
        val m = MaskState()
        assertFalse(m.hidden)
        assertFalse(m.maskVisible)
        assertEquals(0f, m.revealProgress)
    }

    @Test
    fun `toggle 打开-遮罩可见-进度归零`() {
        // 从关闭状态打开：隐藏生效，揭开进度重置为 0
        val on = MaskState(hidden = false, revealProgress = 0.5f).toggle()
        assertTrue(on.hidden)
        assertEquals(0f, on.revealProgress)
        assertTrue(on.maskVisible)
    }

    @Test
    fun `toggle 关闭-遮罩消失`() {
        val off = MaskState(hidden = true).toggle()
        assertFalse(off.hidden)
        assertFalse(off.maskVisible)
        assertEquals(0f, off.revealProgress)
    }

    @Test
    fun `揭开进度钳制-越界按边界`() {
        val m = MaskState(hidden = true).withRevealProgress(1.5f)
        assertEquals(1f, m.revealProgress)
        assertEquals(0f, MaskState().withRevealProgress(-1f).revealProgress)
        assertEquals(0.3f, MaskState().withRevealProgress(0.3f).revealProgress)
    }

    @Test
    fun `完全揭开后-遮罩不可见-标记 fullyRevealed`() {
        val revealed = MaskState(hidden = true).withRevealProgress(1f)
        assertFalse(revealed.maskVisible)
        assertTrue(revealed.fullyRevealed)
        // 中途进度：遮罩仍可见
        val mid = MaskState(hidden = true).withRevealProgress(0.5f)
        assertTrue(mid.maskVisible)
        assertFalse(mid.fullyRevealed)
    }

    @Test
    fun `未隐藏时-进度再大也不算揭开完成`() {
        val m = MaskState(hidden = false).withRevealProgress(1f)
        assertFalse(m.fullyRevealed)
        assertFalse(m.maskVisible)
    }
}
