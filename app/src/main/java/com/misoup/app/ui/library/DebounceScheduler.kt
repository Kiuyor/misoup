package com.misoup.app.ui.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 输入防抖调度器（T03）：300ms 内重复输入只取最后一次。
 *
 * - [submit] 取消上一次未触发的任务并重新计时
 * - 计时结束回调 [onSettled]（带最终值）
 * - scope 可注入（测试用 StandardTestDispatcher + runTest 虚拟时间）
 */
class DebounceScheduler(
    private val debounceMs: Long = 300,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private var job: Job? = null

    /** 提交新输入；防抖窗口内重复提交只保留最后一次 */
    fun submit(value: String, onSettled: (String) -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(debounceMs)
            onSettled(value)
        }
    }

    /** 立即取消未触发的任务（销毁/清空搜索时调用） */
    fun cancel() {
        job?.cancel()
        job = null
    }

    override fun toString(): String = "DebounceScheduler($debounceMs ms)"
}
