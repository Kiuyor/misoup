package com.misoup.app.ai.qanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T05 答疑会话（纯逻辑）：仅内存、不持久化。
 * - 追加/快照/清空；空白消息忽略
 * - recent 限最近 N 条（默认与 Prompt HISTORY_TURNS 一致）
 */
class QaSessionTest {

    @Test
    fun `追加与快照-顺序保持`() {
        val s = QaSession()
        s.addUser("问题1")
        s.addAssistant("回答1")
        s.addUser("问题2")
        assertEquals(3, s.size)
        val all = s.all
        assertEquals(QaMessage.Role.USER, all[0].role)
        assertEquals("问题1", all[0].text)
        assertEquals(QaMessage.Role.ASSISTANT, all[1].role)
        assertEquals("问题2", all[2].text)
    }

    @Test
    fun `空白消息-忽略`() {
        val s = QaSession()
        s.addUser("  ")
        s.addUser("")
        s.addAssistant("\n")
        assertEquals(0, s.size)
    }

    @Test
    fun `recent-限最近N条`() {
        val s = QaSession(maxHistory = 2)
        s.addUser("q1")
        s.addAssistant("a1")
        s.addUser("q2")
        s.addAssistant("a2")
        val recent = s.recent()
        assertEquals(2, recent.size)
        assertEquals("q2", recent[0].text)
        assertEquals("a2", recent[1].text)
        assertTrue(s.all.size == 4)   // all 不受限
    }

    @Test
    fun `recent-不足N条-全量`() {
        val s = QaSession()
        s.addUser("q1")
        assertEquals(1, s.recent().size)
        s.addAssistant("a1")
        assertEquals(2, s.recent().size)
    }

    @Test
    fun `清空-会话重置`() {
        val s = QaSession()
        s.addUser("q")
        s.clear()
        assertEquals(0, s.size)
        assertTrue(s.all.isEmpty())
        assertTrue(s.recent().isEmpty())
    }
}
