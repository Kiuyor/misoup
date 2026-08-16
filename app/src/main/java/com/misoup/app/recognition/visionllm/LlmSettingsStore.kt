package com.misoup.app.recognition.visionllm

import android.content.Context
import android.content.SharedPreferences

/**
 * 视觉 LLM 非敏感配置持久化（模块 05 T01）
 *
 * 只存 baseUrl / model / enabled / rateLimit（非敏感，明文 SharedPreferences 即可）；
 * apiKey 属敏感凭据，一律走 [LlmCredentialStore]（EncryptedSharedPreferences）。
 *
 * 接口化以便 JVM 单测注入内存实现；生产实现 [PrefsLlmSettingsStore] 依赖 Android。
 */
interface LlmSettingsStore {

    /** 读取非敏感配置；无记录时返回全默认 [LlmConfig]（apiKey 恒为空，由 credential store 合并） */
    fun load(): LlmConfig

    /** 保存非敏感配置（调用方需先剥离 apiKey） */
    fun save(config: LlmConfig)

    companion object {
        const val PREFS_NAME = "misoup_llm_settings"
        const val KEY_BASE_URL = "vision_base_url"
        const val KEY_MODEL = "vision_model"
        const val KEY_ENABLED = "vision_enabled"
        const val KEY_RATE_LIMIT = "vision_rate_limit"
    }
}

/** 生产实现：普通 SharedPreferences（非敏感字段；敏感 apiKey 不落这里） */
class PrefsLlmSettingsStore(context: Context) : LlmSettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(LlmSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): LlmConfig {
        val rateLimit = prefs.getInt(LlmSettingsStore.KEY_RATE_LIMIT, LlmConfig.DEFAULT_RATE_LIMIT)
        return LlmConfig(
            baseUrl = prefs.getString(LlmSettingsStore.KEY_BASE_URL, "") ?: "",
            model = prefs.getString(LlmSettingsStore.KEY_MODEL, "") ?: "",
            enabled = prefs.getBoolean(LlmSettingsStore.KEY_ENABLED, false),
            rateLimit = rateLimit.coerceIn(LlmConfig.MIN_RATE_LIMIT, LlmConfig.MAX_RATE_LIMIT),
            // apiKey 不在明文区：由 LlmConfigRepository.load 从凭据区合并
            apiKey = "",
        )
    }

    override fun save(config: LlmConfig) {
        prefs.edit()
            .putString(LlmSettingsStore.KEY_BASE_URL, config.baseUrl)
            .putString(LlmSettingsStore.KEY_MODEL, config.model)
            .putBoolean(LlmSettingsStore.KEY_ENABLED, config.enabled)
            .putInt(
                LlmSettingsStore.KEY_RATE_LIMIT,
                config.rateLimit.coerceIn(LlmConfig.MIN_RATE_LIMIT, LlmConfig.MAX_RATE_LIMIT)
            )
            .apply()
    }
}
