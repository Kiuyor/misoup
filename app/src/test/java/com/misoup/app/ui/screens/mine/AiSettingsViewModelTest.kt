package com.misoup.app.ui.screens.mine

import com.misoup.app.recognition.visionllm.EndpointPresets
import com.misoup.app.recognition.visionllm.LlmConfig
import com.misoup.app.recognition.visionllm.LlmConfigRepository
import com.misoup.app.recognition.visionllm.LlmCredentialStore
import com.misoup.app.recognition.visionllm.LlmSettingsStore
import com.misoup.app.settings.ai.RecognitionStrategy
import com.misoup.app.settings.ai.StrategySettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** AI 增强设置 ViewModel（模块 10 T02） */
@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest {

    private lateinit var dispatcher: kotlinx.coroutines.test.TestDispatcher
    private lateinit var settings: MemLlmSettingsStore
    private lateinit var creds: MemLlmCredentialStore
    private lateinit var strategy: MemStrategyStore
    private var pingResults = ArrayDeque<Result<String>>()

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        settings = MemLlmSettingsStore()
        creds = MemLlmCredentialStore()
        strategy = MemStrategyStore()
        pingResults = ArrayDeque()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun repo() = LlmConfigRepository(settings, creds)

    private fun vm() = AiSettingsViewModel(
        repo = repo(),
        strategyStore = strategy,
        pingAction = { pingResults.removeFirstOrNull() ?: Result.success("OK") },
    )

    private fun readyConfig() = LlmConfig(
        baseUrl = EndpointPresets.ZHIPU_FLASH.baseUrl,
        apiKey = "sk-test-123",
        model = "glm-4v-flash",
        enabled = true,
        rateLimit = 3,
    )

    @Test
    fun `初始状态 - 读取配置与策略`() {
        settings.save(readyConfig().copy(apiKey = ""))
        creds.setApiKey("sk-test-123")
        strategy.save(RecognitionStrategy.OFFLINE_ONLY)
        val v = vm()
        val s = v.state.value
        assertEquals("glm-4v-flash", s.config.model)
        assertTrue(s.hasApiKey)
        assertTrue(s.ready)
        assertEquals(RecognitionStrategy.OFFLINE_ONLY, s.strategy)
    }

    @Test
    fun `保存 - apiKey 剥离进凭据区 明文区无 Key`() {
        val v = vm()
        v.save(readyConfig())
        // 明文设置区不含 apiKey
        assertFalse(settings.saved.apiKey.isNotBlank())
        // 凭据区持有 Key
        assertEquals("sk-test-123", creds.getApiKey())
        // 状态刷新
        assertTrue(v.state.value.hasApiKey)
        assertTrue(v.state.value.config.apiKey.isNotBlank())   // 运行时聚合可见
    }

    @Test
    fun `限速收敛 - 越界值被收敛到 1-20`() {
        val v = vm()
        v.save(readyConfig().copy(rateLimit = 999))
        assertEquals(LlmConfig.MAX_RATE_LIMIT, settings.saved.rateLimit)
        v.setRateLimit(0)
        assertEquals(LlmConfig.MIN_RATE_LIMIT, v.state.value.config.rateLimit)
    }

    @Test
    fun `端点预设 - 仅改 baseUrl model`() {
        val v = vm()
        v.save(readyConfig())
        v.applyPreset(EndpointPresets.QWEN)
        val s = v.state.value
        assertEquals(EndpointPresets.QWEN_BASE_URL, s.config.baseUrl)
        assertEquals("qwen-vl-plus", s.config.model)
        // 预设不碰 enabled/key/rateLimit
        assertTrue(s.config.enabled)
        assertEquals(3, s.config.rateLimit)
    }

    @Test
    fun `策略切换 - 立即持久化`() {
        val v = vm()
        v.setStrategy(RecognitionStrategy.OFFLINE_FIRST)
        assertEquals(RecognitionStrategy.OFFLINE_FIRST, strategy.saved)
        assertEquals(RecognitionStrategy.OFFLINE_FIRST, v.state.value.strategy)
    }

    @Test
    fun `清除 Key - 凭据区清空`() {
        val v = vm()
        v.save(readyConfig())
        assertTrue(creds.hasApiKey())
        v.clearApiKey()
        assertFalse(creds.hasApiKey())
        assertFalse(v.state.value.hasApiKey)
    }

    @Test
    fun `连通测试 - 成功与失败文案`() {
        pingResults.add(Result.success("OK"))
        val v = vm()
        v.testConnection()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.pingResult!!.contains("连通正常"))

        pingResults.add(Result.failure(IllegalStateException("401 Unauthorized")))
        v.testConnection()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.pingResult!!.contains("连通失败"))
        assertTrue(v.state.value.pingResult!!.contains("401"))
    }

    @Test
    fun `未配置 - ready 为 false`() {
        val v = vm()
        assertFalse(v.state.value.ready)
        assertNull(v.state.value.config.baseUrl.takeIf { it.isNotBlank() })
    }

    // ---------- 内存实现 ----------

    private class MemLlmSettingsStore : LlmSettingsStore {
        var saved: LlmConfig = LlmConfig()
        override fun load(): LlmConfig = saved
        override fun save(config: LlmConfig) { saved = config }
    }

    private class MemLlmCredentialStore : LlmCredentialStore {
        private var key: String? = null
        override fun getApiKey(): String? = key
        override fun setApiKey(apiKey: String?) { key = apiKey }
        override fun hasApiKey(): Boolean = !key.isNullOrBlank()
    }

    private class MemStrategyStore : StrategySettingsStore {
        var saved: RecognitionStrategy? = null
        override fun load(): RecognitionStrategy = saved ?: RecognitionStrategy.DEFAULT
        override fun save(strategy: RecognitionStrategy) { saved = strategy }
    }
}
