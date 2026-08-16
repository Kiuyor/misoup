package com.misoup.app.recognition.visionllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T01 内存实现：非敏感设置（明文区） */
class MemoryLlmSettingsStore : LlmSettingsStore {
    var stored: LlmConfig = LlmConfig()
    override fun load(): LlmConfig = stored
    override fun save(config: LlmConfig) {
        stored = config
    }
}

/** T01 内存实现：apiKey（凭据区/加密区） */
class MemoryLlmCredentialStore : LlmCredentialStore {
    private var value: String? = null
    override fun getApiKey(): String? = value
    override fun setApiKey(apiKey: String?) {
        value = apiKey?.takeIf { it.isNotBlank() }
    }
    override fun hasApiKey(): Boolean = !value.isNullOrBlank()
}

/**
 * T01 LlmConfig + 凭据存取：
 * - 默认值（enabled=false / rateLimit=3）
 * - save/load 分流：apiKey 进凭据区（明文区恒为空），其余进设置区
 * - hasApiKey / clearApiKey / 换 Key
 * - applyPreset 一键填 baseUrl+model（不动其他字段）
 * - rateLimit 越界收敛到可调范围
 */
class LlmConfigRepositoryTest {

    private val settings = MemoryLlmSettingsStore()
    private val credentials = MemoryLlmCredentialStore()
    private val repo = LlmConfigRepository(settings, credentials)

    // ---------- 默认值 ----------

    @Test
    fun `默认配置-未启用-限速3-其余空`() {
        val cfg = repo.load()
        assertFalse(cfg.enabled)
        assertEquals(LlmConfig.DEFAULT_RATE_LIMIT, cfg.rateLimit)
        assertEquals("", cfg.baseUrl)
        assertEquals("", cfg.model)
        assertEquals("", cfg.apiKey)
        assertEquals(3, LlmConfig.DEFAULT_RATE_LIMIT)   // module/05 定稿：3 req/10s
    }

    // ---------- save/load 分流 ----------

    @Test
    fun `save-完整配置-load-往返一致`() {
        repo.save(
            LlmConfig(
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                apiKey = "sk-secret-123",
                model = "glm-4v-flash",
                enabled = true,
                rateLimit = 5,
            )
        )
        val loaded = repo.load()
        assertEquals("https://open.bigmodel.cn/api/paas/v4", loaded.baseUrl)
        assertEquals("sk-secret-123", loaded.apiKey)
        assertEquals("glm-4v-flash", loaded.model)
        assertTrue(loaded.enabled)
        assertEquals(5, loaded.rateLimit)
    }

    @Test
    fun `save-apiKey-进凭据区-明文设置区恒为空`() {
        repo.save(LlmConfig(apiKey = "sk-abc", baseUrl = "https://x/v1"))
        // 凭据区持有 key
        assertTrue(credentials.hasApiKey())
        assertEquals("sk-abc", credentials.getApiKey())
        // 明文设置区不含 key（安全定稿：敏感凭据不落明文）
        assertEquals("", settings.load().apiKey)
    }

    @Test
    fun `save-空apiKey-不写凭据区`() {
        repo.save(LlmConfig(apiKey = ""))
        assertFalse(credentials.hasApiKey())
    }

    @Test
    fun `save-apiKey-去除首尾空白`() {
        repo.save(LlmConfig(apiKey = "  sk-trim  "))
        assertEquals("sk-trim", credentials.getApiKey())
    }

    // ---------- hasApiKey / clearApiKey ----------

    @Test
    fun `hasApiKey-未配置false-配置后true`() {
        assertFalse(repo.hasApiKey())
        repo.save(LlmConfig(apiKey = "sk-1"))
        assertTrue(repo.hasApiKey())
    }

    @Test
    fun `clearApiKey-清除后load为空-设置区不受影响`() {
        repo.save(LlmConfig(apiKey = "sk-1", baseUrl = "https://x/v1", enabled = true))
        repo.clearApiKey()
        assertFalse(repo.hasApiKey())
        val cfg = repo.load()
        assertNull(credentials.getApiKey())
        assertEquals("", cfg.apiKey)
        // 非敏感字段保留（清 Key 不重置端点/开关）
        assertEquals("https://x/v1", cfg.baseUrl)
        assertTrue(cfg.enabled)
    }

    @Test
    fun `换Key-新值覆盖旧值`() {
        repo.save(LlmConfig(apiKey = "sk-old"))
        repo.save(LlmConfig(apiKey = "sk-new"))
        assertEquals("sk-new", repo.load().apiKey)
    }

    // ---------- applyPreset（T03 一键填） ----------

    @Test
    fun `applyPreset-智谱flash-一键填baseUrl与model`() {
        val preset = EndpointPresets.ZHIPU_FLASH
        val updated = repo.applyPreset(preset)
        assertEquals(preset.baseUrl, updated.baseUrl)
        assertEquals(preset.defaultModel, updated.model)
        assertEquals("glm-4v-flash", updated.model)
    }

    @Test
    fun `applyPreset-带model覆盖-用指定模型`() {
        val updated = repo.applyPreset(EndpointPresets.ZHIPU_FLASH, modelOverride = "glm-4v-plus")
        assertEquals("glm-4v-plus", updated.model)
        assertEquals(EndpointPresets.ZHIPU_FLASH.baseUrl, updated.baseUrl)
    }

    @Test
    fun `applyPreset-不动enabled与apiKey`() {
        repo.save(LlmConfig(apiKey = "sk-keep", enabled = true, rateLimit = 7))
        val updated = repo.applyPreset(EndpointPresets.QWEN)
        assertTrue(updated.enabled)
        assertEquals("sk-keep", updated.apiKey)
        assertEquals(7, updated.rateLimit)
    }

    // ---------- rateLimit 越界收敛（设置页滑块 1-20） ----------

    @Test
    fun `rateLimit-越界-保存时收敛到可调范围`() {
        repo.save(LlmConfig(rateLimit = 0))
        assertEquals(LlmConfig.MIN_RATE_LIMIT, repo.load().rateLimit)
        repo.save(LlmConfig(rateLimit = 999))
        assertEquals(LlmConfig.MAX_RATE_LIMIT, repo.load().rateLimit)
    }

    // ---------- isReady ----------

    @Test
    fun `isReady-全部就绪才true`() {
        assertFalse(LlmConfig().isReady())
        // 缺 model / 缺 key / 未启用 均 false
        assertFalse(LlmConfig(enabled = true, baseUrl = "https://x/v1", apiKey = "k").isReady())
        assertFalse(LlmConfig(enabled = true, baseUrl = "https://x/v1", model = "m").isReady())
        assertFalse(LlmConfig(baseUrl = "https://x/v1", apiKey = "k", model = "m").isReady())
        // 全部就绪 → true
        assertTrue(LlmConfig(enabled = true, baseUrl = "https://x/v1", apiKey = "k", model = "m").isReady())
    }
}
