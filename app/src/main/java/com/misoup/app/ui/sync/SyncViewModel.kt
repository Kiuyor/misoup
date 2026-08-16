package com.misoup.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.sync.DavSyncService
import com.misoup.app.sync.SyncBackoff
import com.misoup.app.sync.SyncReport
import com.misoup.app.sync.webdav.DavConfig
import com.misoup.app.sync.webdav.DavCredentialRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 同步设置页 UI 状态（模块 09 T05） */
data class SyncUiState(
    val config: DavConfig = DavConfig(),
    val isConfigured: Boolean = false,
    val syncing: Boolean = false,
    val autoSync: Boolean = false,
    val lastReport: SyncReport? = null,
    /** 失败重试倒计时：非空 = 正在退避等待（毫秒） */
    val retryInMs: Long? = null,
    val failedAttempts: Int = 0,
    val message: String? = null
)

/**
 * 同步设置 ViewModel（模块 09 T05）：配置读写 + 手动触发 + 报告展示 + 失败指数退避
 *
 * - 配置：经 [DavCredentialRepository] 落加密存储（https 校验失败 → message 警告）
 * - 手动触发：串行协程（syncing 期间忽略重复点击）
 * - 失败：按 [SyncBackoff] 指数退避自动重试（30s→2m→10m），成功即重置
 * - autoSync 默认关（文档：录入后自动同步默认关，极客可开）；开时每次录入后由 UI 层调用 [syncNow]
 *
 * 无 Android 依赖（除 ViewModel 基类），JVM 单测可全量覆盖。
 */
class SyncViewModel(
    private val credRepo: DavCredentialRepository,
    private val syncAction: suspend () -> SyncReport,
    private val backoff: SyncBackoff = SyncBackoff()
) : ViewModel() {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private var syncJob: Job? = null
    private var retryJob: Job? = null

    init {
        refreshConfig()
    }

    /** 从加密存储读取配置 */
    fun refreshConfig() {
        val cfg = credRepo.load()
        _state.value = _state.value.copy(
            config = cfg,
            isConfigured = credRepo.isConfigured,
            message = if (cfg.url.isNotBlank() && !cfg.isHttps) {
                "⚠ WebDAV 仅支持 HTTPS：当前 http 地址将被拒绝连接"
            } else {
                _state.value.message
            }
        )
    }

    /** 保存配置（repository 校验：不完整/非 https 拒绝落盘，原因进 message） */
    fun saveConfig(url: String, username: String, password: String) {
        val candidate = DavConfig(url = url.trim(), username = username.trim(), password = password)
        credRepo.save(candidate).onSuccess {
            refreshConfig()
            _state.value = _state.value.copy(message = "配置已保存")
        }.onFailure { e ->
            refreshConfig()
            _state.value = _state.value.copy(message = "保存失败：${e.message}")
        }
    }

    /** 一键清除凭据 */
    fun clearCredentials() {
        credRepo.clear()
        _state.value = _state.value.copy(
            config = DavConfig(),
            isConfigured = false,
            lastReport = null,
            retryInMs = null,
            failedAttempts = 0,
            message = "凭据已清除"
        )
        retryJob?.cancel()
    }

    /** 手动触发同步（syncing 中忽略；失败按退避自动重试） */
    fun syncNow() {
        if (_state.value.syncing) return
        retryJob?.cancel()
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _state.value = _state.value.copy(syncing = true, message = null)
            val report = syncAction()
            if (report.isSuccess) {
                _state.value = _state.value.copy(
                    syncing = false,
                    lastReport = report,
                    failedAttempts = 0,
                    retryInMs = null,
                    message = "同步完成：上传 ${report.uploaded} / 下载 ${report.downloaded} / 冲突 ${report.conflicts}"
                )
            } else {
                scheduleRetry(report)
            }
        }
    }

    /** 失败 → 指数退避自动重试（30s→2m→10m），期间展示倒计时 */
    private fun scheduleRetry(report: SyncReport) {
        val attempts = _state.value.failedAttempts
        val delayMs = backoff.delayFor(attempts)
        _state.value = _state.value.copy(
            syncing = false,
            lastReport = report,
            failedAttempts = backoff.nextAttempt(attempts),
            retryInMs = delayMs,
            message = "同步失败：${report.errors.firstOrNull() ?: "未知错误"}（${delayMs / 1000}s 后自动重试）"
        )
        retryJob?.cancel()
        retryJob = viewModelScope.launch {
            delay(delayMs)
            _state.value = _state.value.copy(retryInMs = null)
            syncNow()
        }
    }

    /** 录入后自动同步开关（默认关） */
    fun setAutoSync(enabled: Boolean) {
        _state.value = _state.value.copy(autoSync = enabled)
    }

    override fun onCleared() {
        syncJob?.cancel()
        retryJob?.cancel()
    }
}
