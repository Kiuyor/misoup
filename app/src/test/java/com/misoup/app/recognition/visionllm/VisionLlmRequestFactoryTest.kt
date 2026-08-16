package com.misoup.app.recognition.visionllm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T02/T04 请求构造与响应解析（纯逻辑）：
 * - chatUrl 拼装（去尾斜杠）
 * - 视觉请求体：messages[0] 含 image_url 数据 URI + 定稿 Prompt，且带 model
 * - ping 请求体：纯文本 "ping"，无图片
 * - 响应提取：choices[0].message.content（字符串/文本数组两形态）
 * - 错误提取：error.message 优先
 */
class VisionLlmRequestFactoryTest {

    private val json = Json

    // ---------- chatUrl ----------

    @Test
    fun `chatUrl-拼装-去尾斜杠`() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            VisionLlmRequestFactory.chatUrl("https://open.bigmodel.cn/api/paas/v4")
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            VisionLlmRequestFactory.chatUrl("https://open.bigmodel.cn/api/paas/v4/")
        )
    }

    // ---------- 视觉请求体 ----------

    @Test
    fun `buildChatBody-结构符合OpenAI兼容定稿`() {
        val body = VisionLlmRequestFactory.buildChatBody(
            dataUrl = "data:image/png;base64,AAA",
            prompt = VisionPrompt.PROMPT,
            model = "glm-4v-flash",
        )
        val root = json.parseToJsonElement(body).jsonObject
        assertEquals("glm-4v-flash", root["model"]?.jsonPrimitive?.content)

        val messages = root["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val first = messages[0].jsonObject
        assertEquals("user", first["role"]?.jsonPrimitive?.content)

        val content = first["content"]!!.jsonArray
        assertEquals(2, content.size)

        // 第一段：image_url 数据 URI
        val image = content[0].jsonObject
        assertEquals("image_url", image["type"]?.jsonPrimitive?.content)
        val imageUrl = image["image_url"]!!.jsonObject
        assertEquals("data:image/png;base64,AAA", imageUrl["url"]?.jsonPrimitive?.content)

        // 第二段：定稿 Prompt
        val text = content[1].jsonObject
        assertEquals("text", text["type"]?.jsonPrimitive?.content)
        assertEquals(VisionPrompt.PROMPT, text["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `buildChatBody-数据URI前缀-是png`() {
        assertTrue(VisionLlmRequestFactory.DATA_URL_PREFIX.startsWith("data:image/png;base64,"))
        assertEquals("data:image/png;base64,", VisionLlmRequestFactory.DATA_URL_PREFIX)
    }

    // ---------- ping 请求体（T04） ----------

    @Test
    fun `buildPingBody-最小纯文本-无图片`() {
        val body = VisionLlmRequestFactory.buildPingBody("glm-4v-flash")
        val root = json.parseToJsonElement(body).jsonObject
        assertEquals("glm-4v-flash", root["model"]?.jsonPrimitive?.content)
        val content = root["messages"]!!.jsonArray[0].jsonObject["content"]
        // 纯文本字符串（非数组）→ 无图片，1 次最小请求
        assertEquals("ping", content?.jsonPrimitive?.content)
    }

    // ---------- 响应提取 ----------

    @Test
    fun `parseChatResponse-字符串content-提取成功`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"{\"question\":\"q\",\"answer\":\"a\"}"}}]}"""
        assertEquals(
            "{\"question\":\"q\",\"answer\":\"a\"}",
            VisionLlmRequestFactory.parseChatResponse(body).getOrThrow()
        )
    }

    @Test
    fun `parseChatResponse-文本数组content-拼接提取`() {
        val body = """{"choices":[{"message":{"content":[{"type":"text","text":"part1"},{"type":"text","text":"part2"}]}}]}"""
        assertEquals("part1part2", VisionLlmRequestFactory.parseChatResponse(body).getOrThrow())
    }

    @Test
    fun `parseChatResponse-空choices-失败`() {
        assertTrue(VisionLlmRequestFactory.parseChatResponse("""{"choices":[]}""").isFailure)
        assertTrue(VisionLlmRequestFactory.parseChatResponse("not json").isFailure)
        assertTrue(VisionLlmRequestFactory.parseChatResponse("").isFailure)
    }

    @Test
    fun `parseChatResponse-缺message或缺content-失败`() {
        assertTrue(VisionLlmRequestFactory.parseChatResponse("""{"choices":[{}]}""").isFailure)
        assertTrue(VisionLlmRequestFactory.parseChatResponse("""{"choices":[{"message":{}}]}""").isFailure)
    }

    // ---------- 错误提取 ----------

    @Test
    fun `errorMessage-优先error-message`() {
        val body = """{"error":{"message":"额度不足","type":"insufficient_quota"}}"""
        assertEquals("额度不足", VisionLlmRequestFactory.errorMessage(429, body))
    }

    @Test
    fun `errorMessage-无错误体-回退HTTP状态`() {
        assertEquals("HTTP 500", VisionLlmRequestFactory.errorMessage(500, ""))
        assertEquals("HTTP 500", VisionLlmRequestFactory.errorMessage(500, "not json"))
    }

    // ---------- Prompt 定稿不可变（T06 契约） ----------

    @Test
    fun `Prompt-定稿文本逐字一致`() {
        val expected = "你是错题录入助手。识别图片中的题目并只输出 JSON：\n" +
            "{\"question\":\"题干，公式用 \$LaTeX\$ 包裹\",\"answer\":\"答案\",\"explanation\":\"解析\"}\n" +
            "规则：中文原文保留；公式/数学符号必须转 \$LaTeX\$；看不清的文字用[图]占位；答案或解析不存在时填空字符串。"
        assertEquals(expected, VisionPrompt.PROMPT)
        // 关键要素抽查
        assertTrue(VisionPrompt.PROMPT.contains("你是错题录入助手"))
        assertTrue(VisionPrompt.PROMPT.contains("只输出 JSON"))
        assertTrue(VisionPrompt.PROMPT.contains("\$LaTeX\$"))
        assertTrue(VisionPrompt.PROMPT.contains("[图]"))
        assertTrue(VisionPrompt.PROMPT.contains("填空字符串"))
    }
}
