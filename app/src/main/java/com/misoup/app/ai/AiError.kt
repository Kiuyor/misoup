package com.misoup.app.ai

import java.io.IOException

/**
 * AI 增强失败统一映射（模块 07 T07：失败明确提示，不静默）
 *
 * 文档定稿（module/07「成本与限流」）：变式/答疑失败（限流/断网）→
 * 明确提示「AI 服务不可用」；无 Key → 引导流程（复用模块 05 引导）。
 *
 * - [NotReady]：未配置/未启用 → UI 走「去配置」引导（而非报错）
 * - [messageOf]：任意异常 → 面向用户的中文提示（限流 / 网络 / 兜底）
 * - [isNotReady]：VM 判定是否进入引导态
 */
object AiError {

    /** 无 Key / 未启用：引导文案（不视为错误） */
    const val NOT_READY_MESSAGE = "尚未配置 AI 服务，请先在「设置 → AI 增强」中完成配置"

    /** 限流（HTTP 429 或上游限流语义） */
    const val RATE_LIMIT_MESSAGE = "AI 服务繁忙（限流），请稍后重试"

    /** 断网 / 网络异常 */
    const val NETWORK_MESSAGE = "AI 服务不可用（网络异常），请检查网络后重试"

    /** 兜底：明确提示「AI 服务不可用」，不静默 */
    const val UNAVAILABLE_MESSAGE = "AI 服务不可用，请稍后重试"

    /** 未配置/未启用哨兵异常（文案即 [NOT_READY_MESSAGE]） */
    object NotReady : Exception(NOT_READY_MESSAGE)

    /** 是否「未配置」失败（UI 据此切引导页而非错误提示） */
    fun isNotReady(t: Throwable): Boolean = t is NotReady

    /**
     * 失败 → 用户提示。
     * - 限流：429 异常（error.message 含 限流/RATE）或消息含 429
     * - 网络：IOException 及子类
     * - 其余：兜底「AI 服务不可用」
     */
    fun messageOf(t: Throwable): String = when {
        isNotReady(t) -> NOT_READY_MESSAGE
        isRateLimit(t) -> RATE_LIMIT_MESSAGE
        t is IOException -> NETWORK_MESSAGE
        else -> UNAVAILABLE_MESSAGE
    }

    private fun isRateLimit(t: Throwable): Boolean {
        val msg = t.message.orEmpty()
        return msg.contains("429") ||
            msg.contains("限流") ||
            msg.contains("rate limit", ignoreCase = true) ||
            msg.contains("RPM", ignoreCase = true)
    }
}
