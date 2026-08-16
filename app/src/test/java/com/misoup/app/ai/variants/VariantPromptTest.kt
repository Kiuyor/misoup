package com.misoup.app.ai.variants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T02 变式 Prompt（纯逻辑）：
 * - 文档定稿逐字保留（出题老师/JSON 数组模板/考点/原题）
 * - 数量插值 1-3；越界 → IllegalArgumentException
 * - 原题上下文 = 题干+答案段（解析段排除；无标头整段视为题干）
 */
class VariantPromptTest {

    private val markdown = """
        ## 题干
        求 f(x)=x^2 在 [1,3] 上的最值

        ## 答案
        最小值 1，最大值 9

        ## 解析
        配方后讨论对称轴
    """.trimIndent()

    @Test
    fun `prompt-文档定稿-数量插值与考点`() {
        val p = VariantPrompt.build(listOf("二次函数区间最值", "配方"), markdown, 3)
        assertTrue(p.startsWith("你是出题老师。基于下面的错题，出 3 道同考点的变式题（难度递增），只输出 JSON 数组："))
        assertTrue(p.contains("[{\"question\":\"...\", \"answer\":\"...\", \"hint\":\"...\"}]"))
        assertTrue(p.contains("考点: 二次函数区间最值、配方"))
    }

    @Test
    fun `prompt-原题仅题干加答案-不含解析`() {
        val p = VariantPrompt.build(listOf("二次函数"), markdown, 2)
        assertTrue(p.contains("原题: 题干: 求 f(x)=x^2 在 [1,3] 上的最值"))
        assertTrue(p.contains("答案: 最小值 1，最大值 9"))
        assertFalse(p.contains("配方后讨论对称轴"))   // 解析段不进 Prompt
    }

    @Test
    fun `prompt-考点为空-未标注兜底`() {
        val p = VariantPrompt.build(emptyList(), markdown, 1)
        assertTrue(p.contains("考点: 未标注"))
        val p2 = VariantPrompt.build(listOf("", "  "), markdown, 1)
        assertTrue(p2.contains("考点: 未标注"))
    }

    @Test
    fun `prompt-无标头文本-整段视为题干`() {
        val p = VariantPrompt.build(listOf("a"), "纯文本题干没有标头", 1)
        assertTrue(p.contains("原题: 纯文本题干没有标头"))
    }

    @Test
    fun `prompt-数量越界-抛异常`() {
        try {
            VariantPrompt.build(listOf("a"), markdown, 0)
            assertTrue("应抛异常", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1-3"))
        }
        try {
            VariantPrompt.build(listOf("a"), markdown, 4)
            assertTrue("应抛异常", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1-3"))
        }
    }

    @Test
    fun `extract-空文本-空串`() {
        assertEquals("", VariantPrompt.extractQuestionAndAnswer(""))
        assertEquals("", VariantPrompt.extractQuestionAndAnswer("   "))
    }
}
