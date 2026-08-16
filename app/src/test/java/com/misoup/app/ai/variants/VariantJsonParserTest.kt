package com.misoup.app.ai.variants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T02 变式 JSON 数组解析（纯逻辑）：
 * - 正常数组：question/answer/hint 映射 + title 从题干推导
 * - 容错：围栏剥离 / 缺 answer → 空串 / 缺 hint → null
 * - 失败：坏 JSON / 非数组 / 空数组 / 元素缺题干 / 非对象元素
 */
class VariantJsonParserTest {

    @Test
    fun `parse-正常数组-三要素映射`() {
        val raw = """[
            {"question": "变式题1？", "answer": "答案1", "hint": "提示1"},
            {"question": "变式题2？", "answer": "答案2"}
        ]"""
        val result = VariantJsonParser.parse(raw)
        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(2, list.size)
        assertEquals("变式题1？", list[0].question)
        assertEquals("答案1", list[0].answer)
        assertEquals("提示1", list[0].hint)
        assertEquals("变式题2？", list[1].question)
        assertEquals("答案2", list[1].answer)
        assertNull(list[1].hint)
    }

    @Test
    fun `parse-title-从题干首行推导`() {
        val raw = """[{"question": "已知 f(x) 求最值", "answer": "3"}]"""
        val v = VariantJsonParser.parse(raw).getOrThrow()[0]
        assertEquals("已知f(x)求最值", v.title)   // 去空白
        assertEquals("已知 f(x) 求最值", v.question)
    }

    @Test
    fun `parse-title-超20字截断`() {
        val long = "这是一个非常非常非常非常非常非常非常长的题干用来测试标题截断逻辑是否正常工作"
        val raw = """[{"question": "$long", "answer": "1"}]"""
        val v = VariantJsonParser.parse(raw).getOrThrow()[0]
        assertEquals(20, v.title.length)
    }

    @Test
    fun `parse-围栏剥离-json与纯围栏`() {
        val raw = "```json\n[{\"question\":\"Q\", \"answer\":\"A\"}]\n```"
        assertTrue(VariantJsonParser.parse(raw).isSuccess)
        val raw2 = "```\n[{\"question\":\"Q\", \"answer\":\"A\"}]\n```"
        assertTrue(VariantJsonParser.parse(raw2).isSuccess)
    }

    @Test
    fun `parse-坏JSON-失败`() {
        assertTrue(VariantJsonParser.parse("不是 JSON").isFailure)
        assertTrue(VariantJsonParser.parse("{\"question\":\"对象不是数组\"}").isFailure)
    }

    @Test
    fun `parse-空数组-失败-明确提示`() {
        val result = VariantJsonParser.parse("[]")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("空数组"))
    }

    @Test
    fun `parse-元素缺题干-失败`() {
        val raw = """[{"answer": "没题干"}]"""
        val result = VariantJsonParser.parse(raw)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("题干"))
    }

    @Test
    fun `parse-元素非对象-失败`() {
        val raw = """["字符串不是对象"]"""
        assertTrue(VariantJsonParser.parse(raw).isFailure)
    }

    @Test
    fun `parse-空输出-失败`() {
        assertTrue(VariantJsonParser.parse("").isFailure)
        assertTrue(VariantJsonParser.parse("   ").isFailure)
    }

    @Test
    fun `stripFence-非围栏-原样`() {
        assertEquals("abc", VariantJsonParser.stripFence("abc"))
        assertEquals("[]", VariantJsonParser.stripFence("[]"))
    }

    @Test
    fun `parse-含多余字段-忽略`() {
        val raw = """[{"question":"Q","answer":"A","hint":"H","extra":"x"}]"""
        assertTrue(VariantJsonParser.parse(raw).isSuccess)
    }

    @Test
    fun `parse-答案缺失-空串`() {
        val raw = """[{"question":"只有题干"}]"""
        val v = VariantJsonParser.parse(raw).getOrThrow()[0]
        assertEquals("", v.answer)
        assertFalse(v.question.isBlank())
    }
}
