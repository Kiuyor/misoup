package com.misoup.app.ai.qanda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T05 答疑 Prompt（纯逻辑）：
 * - 前缀逐字保留（module/07 文档定稿）
 * - 上下文拼接：考点 + 错题内容
 * - 跟随指令：重讲 / 举例 / 换一种解法（含同义表述）
 * - build：前缀 + 上下文 + 历史（最近 4 条）+ 用户提问 + 指令
 */
class QaPromptTest {

    @Test
    fun `前缀-逐字保留`() {
        assertEquals(
            "你是耐心的高中理科老师。讲解下面这道错题，先给思路再给步骤，不要直接给答案除非用户要求。",
            QaPrompt.PREFIX
        )
    }

    @Test
    fun `上下文-考点行+错题内容`() {
        val ctx = QaPrompt.buildContext(listOf("二次函数", "配方"), "## 题干\n求最值")
        assertTrue(ctx.startsWith("考点: 二次函数、配方\n错题内容:\n## 题干\n求最值"))
    }

    @Test
    fun `上下文-无考点-仅错题内容`() {
        val ctx = QaPrompt.buildContext(emptyList(), "题干")
        assertFalse(ctx.contains("考点:"))
        assertTrue(ctx.startsWith("错题内容:\n题干"))
        val ctx2 = QaPrompt.buildContext(listOf("", " "), "题干")
        assertFalse(ctx2.contains("考点:"))
    }

    @Test
    fun `跟随指令-重讲`() {
        assertEquals(QaPrompt.FOLLOW_UP_RETELL, QaPrompt.followUpInstruction("请重讲一遍"))
        assertEquals(QaPrompt.FOLLOW_UP_RETELL, QaPrompt.followUpInstruction("再讲一遍吧"))
        assertNull(QaPrompt.followUpInstruction("这道题怎么做"))
    }

    @Test
    fun `跟随指令-举例`() {
        assertEquals(QaPrompt.FOLLOW_UP_EXAMPLE, QaPrompt.followUpInstruction("举一个例子"))
        assertEquals(QaPrompt.FOLLOW_UP_EXAMPLE, QaPrompt.followUpInstruction("能举个例子吗"))
        assertNull(QaPrompt.followUpInstruction("谢谢"))
    }

    @Test
    fun `跟随指令-换一种解法`() {
        assertEquals(QaPrompt.FOLLOW_UP_ALT_METHOD, QaPrompt.followUpInstruction("换一种解法"))
        assertEquals(QaPrompt.FOLLOW_UP_ALT_METHOD, QaPrompt.followUpInstruction("换个解法试试"))
        assertEquals(QaPrompt.FOLLOW_UP_ALT_METHOD, QaPrompt.followUpInstruction("有没有另一种解法"))
        assertEquals(QaPrompt.FOLLOW_UP_ALT_METHOD, QaPrompt.followUpInstruction("别的方法呢"))
    }

    @Test
    fun `build-前缀+上下文+提问+指令`() {
        val ctx = QaPrompt.buildContext(listOf("二次函数"), "错题题干")
        val p = QaPrompt.build(ctx, "这道题为什么要配方？", emptyList())
        assertTrue(p.startsWith(QaPrompt.PREFIX))
        assertTrue(p.contains("考点: 二次函数"))
        assertTrue(p.contains("用户问题: 这道题为什么要配方？"))
        assertFalse(p.contains("历史对话:"))
    }

    @Test
    fun `build-跟随指令追加`() {
        val ctx = QaPrompt.buildContext(emptyList(), "题干")
        val p = QaPrompt.build(ctx, "换一种解法", emptyList())
        assertTrue(p.endsWith(QaPrompt.FOLLOW_UP_ALT_METHOD))
    }

    @Test
    fun `build-历史对话-按顺序拼接-限最近4条`() {
        val ctx = "错题内容:\n题干"
        val history = (1..6).map { i ->
            if (i % 2 == 1) QaMessage(QaMessage.Role.USER, "问$i")
            else QaMessage(QaMessage.Role.ASSISTANT, "答$i")
        }
        val p = QaPrompt.build(ctx, "新问题", history)
        assertTrue(p.contains("历史对话:"))
        // 只带最近 4 条（问5/答6/问3? → 实际最近4条= 问5,答6 之前的…）
        assertFalse(p.contains("问1"))
        assertFalse(p.contains("答2"))
        assertTrue(p.contains("问5"))
        assertTrue(p.contains("答6"))
        // 顺序：用户在前老师在后
        val userIdx = p.indexOf("用户: 问5")
        val teacherIdx = p.indexOf("老师: 答6")
        assertTrue(userIdx in 0 until teacherIdx)
    }

    @Test
    fun `build-历史超出-截断至HISTORY_TURNS`() {
        val history = (1..10).map { QaMessage(QaMessage.Role.USER, "m$it") }
        val p = QaPrompt.build("ctx", "q", history)
        val lines = p.split("\n")
        // 只带最近 4 条（m7-m10）；m1-m6 不出现（按整行判断，避免 "用户: m10" 误命中 "m1"）
        assertTrue(lines.contains("用户: m7"))
        assertTrue(lines.contains("用户: m10"))
        assertFalse(lines.contains("用户: m1"))
        assertFalse(lines.contains("用户: m6"))
    }
}
