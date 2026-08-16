package com.misoup.app.ai.variants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T02 变式文件名 + 变式 .md 渲染/解析（纯逻辑）：
 * - 文件名：yyyyMMdd_HHmmss_变式N.md（N 从 1 起；序号 <1 → 异常）
 * - 渲染：## 题干/## 答案/## 提示（hint 空 → 省略段）
 * - 往返：render → parse 还原（title/question/answer/hint）
 */
class VariantFileTest {

    // ---------- VariantFileName ----------

    @Test
    fun `文件名-格式与序号`() {
        // 2025-08-16 14:05:23 +08:00（epoch 验证过，同 NameGeneratorTest 口径）
        val now = 1_755_324_323_000L
        val name = VariantFileName.generate(now, 1)
        assertEquals("20250816_140523_变式1.md", name)
        assertEquals("20250816_140523_变式2.md", VariantFileName.generate(now, 2))
    }

    @Test
    fun `文件名-格式校验`() {
        assertTrue(VariantFileName.isValid("20260816_140523_变式1.md"))
        assertTrue(VariantFileName.isValid("20260816_140523_变式10.md"))
        assertFalse(VariantFileName.isValid("20260816_140523_变式1.txt"))
        assertFalse(VariantFileName.isValid("变式1.md"))
        assertFalse(VariantFileName.isValid("20260816_140523_变式.md"))
        assertFalse(VariantFileName.isValid("../20260816_140523_变式1.md"))
        assertFalse(VariantFileName.isValid(""))
    }

    @Test
    fun `文件名-序号小于1-异常`() {
        try {
            VariantFileName.generate(0L, 0)
            assertTrue("应抛异常", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("从 1 起"))
        }
    }

    // ---------- VariantMarkdown ----------

    private val variant = Variant(
        title = "变式题干",
        question = "求最值",
        answer = "3",
        hint = "配方"
    )

    @Test
    fun `渲染-分段与提示`() {
        val md = VariantMarkdown.render(variant)
        assertTrue(md.contains("## 题干\n求最值"))
        assertTrue(md.contains("## 答案\n3"))
        assertTrue(md.contains("## 提示\n配方"))
    }

    @Test
    fun `渲染-hint为空-省略提示段`() {
        val md = VariantMarkdown.render(variant.copy(hint = null))
        assertFalse(md.contains("## 提示"))
        val md2 = VariantMarkdown.render(variant.copy(hint = "  "))
        assertFalse(md2.contains("## 提示"))
    }

    @Test
    fun `渲染解析-往返还原`() {
        val parsed = VariantMarkdown.parse(VariantMarkdown.render(variant))!!
        assertEquals(variant.question, parsed.question)
        assertEquals(variant.answer, parsed.answer)
        assertEquals(variant.hint, parsed.hint)
        // title 由题干推导（非持久化字段）：还原后 = titleOf(题干)
        assertEquals(titleOf(variant.question), parsed.title)
    }

    @Test
    fun `解析-无提示段-hint为null`() {
        val md = "## 题干\nQ\n\n## 答案\nA"
        val parsed = VariantMarkdown.parse(md)!!
        assertEquals("Q", parsed.question)
        assertEquals("A", parsed.answer)
        assertNull(parsed.hint)
    }

    @Test
    fun `解析-无题干段-null`() {
        assertNull(VariantMarkdown.parse("## 答案\n只有答案"))
        assertNull(VariantMarkdown.parse(""))
        assertNull(VariantMarkdown.parse("   "))
    }
}
