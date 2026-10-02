package com.misoup.app.ui.screens.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.recognition.visionllm.EndpointPreset
import com.misoup.app.recognition.visionllm.LlmConfig
import com.misoup.app.recognition.visionllm.LlmConfigRepository
import com.misoup.app.recognition.visionllm.isReady
import com.misoup.app.settings.ai.RecognitionStrategy
import com.misoup.app.settings.ai.StrategySettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * AI 增强设置状态（模块 10 T02）
 *
 * - 模型配置（视觉/文本共用同一份 [LlmConfigRepository]，工程现状：文本通道复用 05 配置）
 *   - 端点预设一键填入（[EndpointPresets]）、BYOK 手填（baseUrl/apiKey/model）、连通测试
 * - 识别策略三档（[RecognitionStrategy] → [StrategySettingsStore]）
 * - 限速滑块（[LlmConfig.rateLimit]，1-20，默认 3）
 *
 * 依赖全部函数/接口注入，JVM 单测可全量覆盖。
 */
data class AiSettingsUiState(
    val config: LlmConfig = LlmConfig(),
    val hasApiKey: Boolean = false,
    val strategy: RecognitionStrategy = RecognitionStrategy.DEFAULT,
    val testing: Boolean = false,
    val pingResult: String? = null,
    val message: String? = null,
) {
    /** 是否已具备发起请求条件（enabled + 端点 + key + model） */
    val ready: Boolean get() = config.isReady()
}

class AiSettingsViewModel(
    private val repo: LlmConfigRepository,
    private val strategyStore: StrategySettingsStore,
    private val pingAction: suspend () -> Result<String>,
) : ViewModel() {

    private val _state = MutableStateFlow(AiSettingsUiState())
    val state: StateFlow<AiSettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 从仓库/凭据区重读（保存、清 Key 后调用） */
    fun refresh() {
        _state.update {
            it.copy(
                config = repo.load(),
                hasApiKey = repo.hasApiKey(),
                strategy = strategyStore.load(),
                message = null,
            )
        }
    }

    /**
     * 保存完整配置（apiKey 由仓库剥离进凭据区；限速自动收敛到 [MIN_RATE_LIMIT, MAX_RATE_LIMIT]）。
     * 安全约束（对齐 PRIVACY.md「仅允许 HTTPS」承诺）：Base URL 非 https 拒绝保存并给出原因
     * （错误反馈模式与 WebDAV 侧 DavConfig.validate 一致）。
     */
    fun save(config: LlmConfig) {
        val url = config.baseUrl.trim()
        if (url.isNotEmpty() && !url.lowercase().startsWith("https://")) {
            _state.update { it.copy(message = "保存失败：Base URL 仅支持 HTTPS（当前为 $url）") }
            return
        }
        repo.save(config)
        refresh()
        _state.update { it.copy(message = "模型配置已保存") }
    }

    /** 端点预设一键填入（仅改 baseUrl/model，不动其余字段） */
    fun applyPreset(preset: EndpointPreset, modelOverride: String? = null) {
        val applied = repo.applyPreset(preset, modelOverride)
        _state.update { it.copy(config = applied, message = "已填入预设：${preset.displayName}") }
    }

    /** 限速滑块（1-20）：保存时随配置落盘 */
    fun setRateLimit(rateLimit: Int) {
        _state.update {
            it.copy(config = it.config.copy(rateLimit = rateLimit.coerceIn(LlmConfig.MIN_RATE_LIMIT, LlmConfig.MAX_RATE_LIMIT)))
        }
    }

    /** 识别策略切换（立即持久化） */
    fun setStrategy(strategy: RecognitionStrategy) {
        strategyStore.save(strategy)
        _state.update { it.copy(strategy = strategy, message = "识别策略：${strategy.displayName}") }
    }

    /** 清除 apiKey（BYOK 换 Key / 隐私清理） */
    fun clearApiKey() {
        repo.clearApiKey()
        refresh()
        _state.update { it.copy(message = "已清除 API Key") }
    }

    /** 连通测试：1 次最小纯文本请求（不发送图片） */
    fun testConnection() {
        if (_state.value.testing) return
        _state.update { it.copy(testing = true, pingResult = null) }
        viewModelScope.launch {
            val result = pingAction()
            _state.update {
                it.copy(
                    testing = false,
                    pingResult = result.fold(
                        onSuccess = { "连通正常（OK）" },
                        onFailure = { e -> "连通失败：${e.message ?: "未知错误"}" },
                    ),
                )
            }
        }
    }
}
