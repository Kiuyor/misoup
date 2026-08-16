package com.misoup.app.recognition.visionllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T06 JSON 解析（坏 JSON → 重试 → 降级的解析侧）：
 * - 标准输出解析成功（含 $LaTeX$）
 * - 代码围栏 ```json 容忍
 * - 前后杂散文本容忍
 * - 缺字段 → 填空字符串（文档定稿）
 * - 坏 JSON / 非对象 / 空输出 → failure
 */
class VisionJsonParserTest {

    @Test
    fun `标准输出-解析成功-含LaTeX`() {
        val content = """{"question":"求函数 ${'$'}f(x)=x^2${'$'} 的最值","answer":"3","explanation":"配方法"}"""
        val r = VisionJsonParser.parse(content).getOrThrow()
        assertEquals("求函数 ${'$'}f(x)=x^2${'$'} 的最值", r.question)
        assertEquals("3", r.answer)
        assertEquals("配方法", r.explanation)
    }

    @Test
    fun `代码围栏-容忍`() {
        val content = "```json\n{\"question\":\"q\",\"answer\":\"a\",\"explanation\":\"e\"}\n```"
        val r = VisionJsonParser.parse(content).getOrThrow()
        assertEquals("q", r.question)
        assertEquals("a", r.answer)
        assertEquals("e", r.explanation)
    }

    @Test
    fun `围栏带json标注-容忍`() {
        val content = "```json\n{\"question\":\"q\"}\n```"
        assertEquals("q", VisionJsonParser.parse(content).getOrThrow().question)
    }

    @Test
    fun `前后杂散文本-截取对象`() {
        val content = "好的，以下是识别结果：\n{\"question\":\"q1\"}\n希望有帮助"
        assertEquals("q1", VisionJsonParser.parse(content).getOrThrow().question)
    }

    @Test
    fun `缺字段-填空字符串`() {
        val r = VisionJsonParser.parse("""{"question":"仅题干"}""").getOrThrow()
        assertEquals("仅题干", r.question)
        assertEquals("", r.answer)
        assertEquals("", r.explanation)
    }

    @Test
    fun `全部字段缺失-空对象-成功但全空`() {
        val r = VisionJsonParser.parse("{}").getOrThrow()
        assertTrue(r.isEmpty)
    }

    @Test
    fun `字段非字符串-容错为空串`() {
        val r = VisionJsonParser.parse("""{"question":123,"answer":true}""").getOrThrow()
        assertEquals("", r.question)
        assertEquals("", r.answer)
    }

    @Test
    fun `坏JSON-失败`() {
        assertTrue(VisionJsonParser.parse("not json at all").isFailure)
        assertTrue(VisionJsonParser.parse("""{"question": "未闭合""").isFailure)
    }

    @Test
    fun `非对象-JSON数组-失败`() {
        assertTrue(VisionJsonParser.parse("""[{"question":"q"}]""").isFailure)
    }

    @Test
    fun `空输出-失败`() {
        assertTrue(VisionJsonParser.parse("").isFailure)
        assertTrue(VisionJsonParser.parse("   ").isFailure)
    }

    @Test
    fun `无对象大括号-失败`() {
        assertTrue(VisionJsonParser.parse("纯文本无 JSON").isFailure)
    }

    @Test
    fun `toMarkdown-分区格式-与工程约定一致`() {
        val md = StructuredContent(
            question = "求 ${'$'}x${'$'} 值",
            answer = "3",
            explanation = "",
        ).toMarkdown()
        assertEquals("## 题干\n求 ${'$'}x${'$'} 值\n\n## 答案\n3", md)
    }

    @Test
    fun `toMarkdown-全空-返回空串`() {
        assertEquals("", StructuredContent().toMarkdown())
    }

    @Test
    fun `toMarkdown-三段齐全-顺序题干答案解析`() {
        val md = StructuredContent("q", "a", "e").toMarkdown()
        assertEquals("## 题干\nq\n\n## 答案\na\n\n## 解析\ne", md)
    }
}
