package com.misoup.app.ai

import com.misoup.app.recognition.visionllm.EndpointPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * T01/T04 文本模型选择（纯逻辑）：默认文本模型 DeepSeek-chat / glm-4-flash / 自定义。
 * - 智谱端点 → glm-4-flash（免费通道与视觉共用 Key）
 * - 其余端点（DeepSeek/通义/硅基流动/自定义）→ deepseek-chat（BYOK 主推）
 */
class TextModelPresetsTest {

    @Test
    fun `智谱端点-推荐glm4flash`() {
        assertEquals(
            TextModelPresets.ZHIPU_TEXT_MODEL,
            TextModelPresets.recommendedModelFor(EndpointPresets.ZHIPU_BASE_URL)
        )
    }

    @Test
    fun `DeepSeek端点-推荐deepseek-chat`() {
        assertEquals(
            TextModelPresets.DEEPSEEK_MODEL,
            TextModelPresets.recommendedModelFor(TextModelPresets.DEEPSEEK_BASE_URL)
        )
    }

    @Test
    fun `通义-硅基流动-自定义-推荐deepseek-chat`() {
        assertEquals(TextModelPresets.DEEPSEEK_MODEL, TextModelPresets.recommendedModelFor(EndpointPresets.QWEN.baseUrl))
        assertEquals(TextModelPresets.DEEPSEEK_MODEL, TextModelPresets.recommendedModelFor(EndpointPresets.SILICONFLOW.baseUrl))
        assertEquals(TextModelPresets.DEEPSEEK_MODEL, TextModelPresets.recommendedModelFor("https://custom.example.com/v1"))
        assertEquals(TextModelPresets.DEEPSEEK_MODEL, TextModelPresets.recommendedModelFor(""))
    }

    @Test
    fun `预设-文档定稿模型名与端点`() {
        assertEquals("deepseek-chat", TextModelPresets.DEEPSEEK.model)
        assertEquals("https://api.deepseek.com/v1", TextModelPresets.DEEPSEEK.baseUrl)
        assertEquals("glm-4-flash", TextModelPresets.ZHIPU_TEXT.model)
        assertEquals(EndpointPresets.ZHIPU_BASE_URL, TextModelPresets.ZHIPU_TEXT.baseUrl)
        // 两个默认文本模型都在列表里
        assertEquals(2, TextModelPresets.all.size)
    }

    @Test
    fun `预设反查-尾部斜杠归一化-未知返回null`() {
        assertSame(TextModelPresets.DEEPSEEK, TextModelPresets.presetByBaseUrl("https://api.deepseek.com/v1/"))
        assertSame(TextModelPresets.ZHIPU_TEXT, TextModelPresets.presetByBaseUrl(EndpointPresets.ZHIPU_BASE_URL))
        assertNull(TextModelPresets.presetByBaseUrl("https://unknown.example.com"))
    }
}
