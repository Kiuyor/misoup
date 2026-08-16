package com.misoup.app.ai

import com.misoup.app.recognition.visionllm.EndpointPresets

/**
 * 文本模型选择（模块 07「模型选择」：默认文本模型 DeepSeek-chat / glm-4-flash / 自定义）
 *
 * 文档定稿（module/07「变式出题·模型选择」）：默认文本模型
 * （DeepSeek-chat / glm-4-flash / 用户自定义）。
 *
 * 设计裁决：M4 复用 05 的同一通道（baseUrl/apiKey/model/enabled），不新增独立模型设置；
 * 本对象提供：
 * - [DEEPSEEK] / [ZHIPU_TEXT]：两个文档定稿文本模型预设（设置页模型名可手填自定义）
 * - [recommendedModelFor]：按当前端点推荐文本模型（智谱 → glm-4-flash，其余 → deepseek-chat）
 */
object TextModelPresets {

    // ---- DeepSeek（BYOK 主推） ----
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com/v1"
    const val DEEPSEEK_MODEL = "deepseek-chat"
    const val DEEPSEEK_REGISTER_URL = "https://platform.deepseek.com/"

    // ---- 智谱文本模型（免费引导通道，与视觉共用端点） ----
    const val ZHIPU_TEXT_MODEL = "glm-4-flash"

    /** 文本模型预设（供设置页/引导展示；自定义 = 任意 OpenAI 兼容模型名手填） */
    data class TextModelPreset(
        val displayName: String,
        val baseUrl: String,
        val model: String,
        val registerUrl: String? = null,
    )

    val DEEPSEEK = TextModelPreset(
        displayName = "DeepSeek（BYOK）",
        baseUrl = DEEPSEEK_BASE_URL,
        model = DEEPSEEK_MODEL,
        registerUrl = DEEPSEEK_REGISTER_URL,
    )

    val ZHIPU_TEXT = TextModelPreset(
        displayName = "智谱 glm-4-flash（免费）",
        baseUrl = EndpointPresets.ZHIPU_BASE_URL,
        model = ZHIPU_TEXT_MODEL,
        registerUrl = EndpointPresets.ZHIPU_REGISTER_URL,
    )

    /** 文档定稿的两个默认文本模型（展示顺序） */
    val all: List<TextModelPreset> = listOf(DEEPSEEK, ZHIPU_TEXT)

    /**
     * 按当前端点推荐文本模型：
     * - 智谱端点 → glm-4-flash（免费通道与视觉共用 Key）
     * - 其余（DeepSeek/通义/硅基流动/自定义）→ deepseek-chat（BYOK 主推）
     */
    fun recommendedModelFor(baseUrl: String): String =
        if (baseUrl.contains("bigmodel")) ZHIPU_TEXT_MODEL else DEEPSEEK_MODEL

    /** 端点是否命中任一文本模型预设（baseUrl 归一化比较）；未知 → null */
    fun presetByBaseUrl(baseUrl: String): TextModelPreset? =
        all.firstOrNull { it.baseUrl == baseUrl.trimEnd('/') }
}
