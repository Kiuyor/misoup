package com.misoup.app.recognition.visionllm

/**
 * 视觉 LLM 通道配置（模块 05 T01）
 *
 * 字段严格按 module/05 文档定稿：baseUrl / apiKey / model / enabled / rateLimit。
 * - apiKey 为敏感凭据：持久化走 CredentialStore（EncryptedSharedPreferences），
 *   本对象仅作为运行时聚合载体（[LlmConfigRepository.load] 合并产出）
 * - rateLimit：免费通道限速，单位"请求/10s"（令牌桶容量），默认 3
 */
data class LlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val enabled: Boolean = false,
    val rateLimit: Int = DEFAULT_RATE_LIMIT,
) {
    companion object {
        /** 默认限速：3 req/10s（module/05 文档定稿；保护智谱免费通道共享额度） */
        const val DEFAULT_RATE_LIMIT = 3

        /** 限速可调范围（设置页滑块 1-20，module/10 文档定稿） */
        const val MIN_RATE_LIMIT = 1
        const val MAX_RATE_LIMIT = 20

        /** 空配置（未设置任何端点） */
        val EMPTY = LlmConfig()
    }
}

/** 是否已具备发起视觉请求的基本条件（enabled + 端点 + key + model） */
fun LlmConfig.isReady(): Boolean =
    enabled && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
