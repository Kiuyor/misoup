package com.misoup.app.recognition.visionllm

import com.misoup.app.data.security.CredentialKeys
import com.misoup.app.data.security.CredentialStore

/**
 * 视觉 LLM apiKey 凭据存取（模块 05 T01）
 *
 * apiKey 属敏感凭据，必须走 CredentialStore（EncryptedSharedPreferences），
 * 禁止落明文 SharedPreferences（module/10「安全实现」定稿）。
 *
 * 接口化以便 JVM 单测注入内存实现；生产实现 [CredentialStoreLlmKeyStore] 包装
 * [CredentialStore]（EncryptedSharedPreferences 依赖 Android 运行时，真机/模拟器验证）。
 */
interface LlmCredentialStore {

    /** 读取 apiKey；未配置返回 null */
    fun getApiKey(): String?

    /** 保存 apiKey；null/空白 = 清除 */
    fun setApiKey(apiKey: String?)

    /** 是否已配置 apiKey */
    fun hasApiKey(): Boolean
}

/**
 * 生产实现：CredentialStore 适配器。
 * 复用凭据命名空间 BYOK_VISION_KEY（module/05 视觉模型 BYOK 通道；智谱免费引导 Key 同槽位）。
 */
class CredentialStoreLlmKeyStore(
    private val credentialStore: CredentialStore,
) : LlmCredentialStore {

    override fun getApiKey(): String? = credentialStore.get(CredentialKeys.BYOK_VISION_KEY)

    override fun setApiKey(apiKey: String?) {
        if (apiKey.isNullOrBlank()) {
            if (credentialStore.has(CredentialKeys.BYOK_VISION_KEY)) {
                credentialStore.remove(CredentialKeys.BYOK_VISION_KEY)
            }
        } else {
            credentialStore.put(CredentialKeys.BYOK_VISION_KEY, apiKey.trim())
        }
    }

    override fun hasApiKey(): Boolean = credentialStore.has(CredentialKeys.BYOK_VISION_KEY)
}
