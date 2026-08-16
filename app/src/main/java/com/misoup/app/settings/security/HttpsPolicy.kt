package com.misoup.app.settings.security

/**
 * HTTPS 强制策略（模块 10 T08，纯逻辑）
 *
 * 网络安全基线（module/10「安全实现」）：WebDAV / LLM / 百度全 HTTPS，
 * 明文 http 请求直接拦截并提示。本对象为统一判定入口，网络层与 UI 层共用。
 */
object HttpsPolicy {

    /** http 请求被拒时的提示文案（UI/日志展示；%s 为原始地址） */
    fun rejectMessage(url: String): String =
        "仅支持 HTTPS：检测到明文 http 请求（$url），已拦截。请将地址改为 https:// 开头"

    /** 是否 https 地址（大小写不敏感，容忍首尾空白） */
    fun isSecureUrl(url: String): Boolean =
        url.trim().lowercase().startsWith("https://")

    /** 是否明文 http 地址 */
    fun isHttpUrl(url: String): Boolean =
        url.trim().lowercase().startsWith("http://")

    /** 是否已带协议头（http:// 或 https://） */
    fun hasScheme(url: String): Boolean = url.contains("://")

    /**
     * 统一校验：非空 + 带协议 + HTTPS。
     * - 空地址 → 失败（"地址为空"）
     * - 无协议头 → 失败（提示补协议）
     * - http → 失败（[rejectMessage]，明文拦截）
     * - https → 成功
     */
    fun requireHttps(url: String): Result<Unit> = when {
        url.isBlank() -> Result.failure(IllegalArgumentException("地址为空"))
        !hasScheme(url) ->
            Result.failure(IllegalArgumentException("地址缺少协议头（http:// 或 https://）：$url"))
        !isSecureUrl(url) -> Result.failure(InsecureHttpException(rejectMessage(url)))
        else -> Result.success(Unit)
    }
}

/** 明文 http 被拒（T08）；携带提示文案供 UI 展示 */
class InsecureHttpException(message: String) : IllegalArgumentException(message)
