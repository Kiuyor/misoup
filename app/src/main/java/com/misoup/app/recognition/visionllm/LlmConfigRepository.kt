package com.misoup.app.recognition.visionllm

/**
 * 视觉 LLM 配置仓库（模块 05 T01/T03/T08 纯逻辑，可 JVM 单测）
 *
 * 职责：
 * - [load]：非敏感字段（settings store）⊕ apiKey（credential store）合并为运行时 [LlmConfig]
 * - [save]：按敏感度分流持久化——apiKey → CredentialStore（加密区），其余 → 明文设置区
 * - [applyPreset]：端点预设一键填入 baseUrl/model（T03，设置页入口）
 * - [setApiKey]/[clearApiKey]：引导回填（T04）与 BYOK 录入
 * - 识别策略（T08）默认值与本类解耦，见 [RecognitionStrategy]
 */
class LlmConfigRepository(
    private val settings: LlmSettingsStore,
    private val credentials: LlmCredentialStore,
) {

    /** 当前完整配置（apiKey 实时从凭据区合并；未配置字段取默认） */
    fun load(): LlmConfig {
        val base = settings.load()
        return base.copy(apiKey = credentials.getApiKey() ?: "")
    }

    /** 持久化：apiKey 剥离进凭据区，其余进设置区（入库前归一化：trim + 限速收敛） */
    fun save(config: LlmConfig) {
        val apiKey = config.apiKey.trim().takeIf { it.isNotBlank() }
        credentials.setApiKey(apiKey)
        settings.save(
            config.copy(
                apiKey = "",
                rateLimit = config.rateLimit.coerceIn(LlmConfig.MIN_RATE_LIMIT, LlmConfig.MAX_RATE_LIMIT),
            )
        )
    }

    /** 是否已配置 apiKey（引导流程/设置页"已配置"展示用） */
    fun hasApiKey(): Boolean = credentials.hasApiKey()

    /** 清除 apiKey（BYOK 换 Key / 隐私清理） */
    fun clearApiKey() {
        credentials.setApiKey(null)
    }

    /**
     * 端点预设一键填入（T03）：仅改 baseUrl/model，不动 enabled/apiKey/rateLimit。
     * 返回新配置，由调用方决定是否 [save]（设置页保存时调用）。
     */
    fun applyPreset(preset: EndpointPreset, modelOverride: String? = null): LlmConfig {
        val current = load()
        val model = modelOverride ?: preset.defaultModel
        return current.copy(baseUrl = preset.baseUrl, model = model)
    }
}
