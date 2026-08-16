package com.misoup.app.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 录入后自动同步 UI 状态（供进度页 / 同步页反馈） */
data class AutoSyncState(
    val running: Boolean = false,
    /** 本进程内是否至少触发过；供 UI 区分「从未触发」与「已完成」 */
    val triggered: Boolean = false,
    val lastResult: SyncReport? = null,
    /** 非空 = 失败退避等待中（毫秒） */
    val nextRetryInMs: Long? = null,
    val retries: Int = 0,
)

/**
 * 录入后自动同步（F2 完善版）。
 *
 * 对比 F2 初版的 composition `LaunchedEffect`：
 * - **App 级存活作用域**执行，离开录入进度页/返回首页不被 cancel → 同步不会中途夭折；
 * - **单飞**：同一时刻只跑一轮，杜绝与手动同步/多批次并发写 `.sync-state.json` 竞态；
 * - 失败沿用 [SyncBackoff]（30s→2m→10m）指数退避自动重试，开关关闭或达 [maxRetries] 即停；
 * - 全程结果经 [state] 发布，进度页/同步页可见反馈。
 *
 * 无 Android 依赖（除注入的 `sync` 实现），JVM 可单测。
 */
class AutoSyncCoordinator(
    private val scope: CoroutineScope,
    private val sync: suspend () -> SyncReport,
    private val enabled: () -> Boolean,
    private val backoff: SyncBackoff = SyncBackoff(),
    private val maxRetries: Int = 3,
) {
    private val _state = MutableStateFlow(AutoSyncState())
    val state: StateFlow<AutoSyncState> = _state.asStateFlow()

    private var job: Job? = null

    /** 录入批次完成时调用：开关关闭或在跑 → 无操作（单飞） */
    fun triggerIfEnabled() {
        if (!enabled()) return
        if (job?.isActive == true) return
        _state.update { it.copy(running = true, triggered = true, nextRetryInMs = null) }
        job = scope.launch {
            var attempt = 0
            while (true) {
                val report = runCatching { sync() }.getOrElse {
                    SyncReport(errors = listOf(it.message ?: it.javaClass.simpleName))
                }
                val stop = report.isSuccess || attempt >= maxRetries || !enabled()
                if (stop) {
                    _state.value = AutoSyncState(lastResult = report, triggered = true)
                    return@launch
                }
                val wait = backoff.delayFor(attempt)
                _state.value = _state.value.copy(lastResult = report, nextRetryInMs = wait, retries = attempt + 1)
                delay(wait)
                attempt = backoff.nextAttempt(attempt)
            }
        }
    }

    /** 放弃在跑的自动同步（开关关闭/清除凭据时调用；幂等），下次 trigger 重新放行 */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false, nextRetryInMs = null)
    }
}