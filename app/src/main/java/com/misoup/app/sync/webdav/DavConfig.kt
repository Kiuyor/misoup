package com.misoup.app.sync.webdav

/**
 * WebDAV 同步配置（模块 09 T01）
 *
 * - 远程结构：`<dav根>/misoup-library/<科目>/<题目目录>/...`（与本地镜像，见 module/09-sync-backup.md）
 * - 安全性（T04）：仅 HTTPS。http 地址 → [validate] 拒绝并给出明确警告
 *   （本地联调 WebDAV 多为 http，生产强制 https，联调走 allowInsecureHttp 显式开关，UI 必须展示警告）
 * - 密码落 EncryptedSharedPreferences（CredentialStore），见 [DavCredentialRepository]
 *
 * 纯 Kotlin 无 Android 依赖，可 JVM 单测。
 */
data class DavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = ""
) {

    /** 三项齐全才算配置完成 */
    val isConfigured: Boolean
        get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** 是否 https 协议 */
    val isHttps: Boolean
        get() = url.trim().lowercase().startsWith("https://")

    /** 是否已带协议头（http:// 或 https://） */
    val hasScheme: Boolean
        get() = url.contains("://")

    /** 配置校验：完整 + 协议合法 + HTTPS 强制；失败返回带明确原因的 failure */
    fun validate(): Result<Unit> = when {
        !isConfigured ->
            Result.failure(IllegalArgumentException("WebDAV 未配置完整：地址/用户名/密码均不能为空"))
        !hasScheme ->
            Result.failure(IllegalArgumentException("WebDAV 地址需包含协议，如 https://dav.jianguoyun.com/dav/"))
        !isHttps ->
            Result.failure(InsecureHttpException("WebDAV 仅支持 HTTPS：当前地址为 http（$url），已拒绝连接"))
        else -> Result.success(Unit)
    }

    /** 远端同步根目录：`<url 去尾斜杠>/misoup-library` */
    fun syncRoot(): String = "${url.trimEnd('/')}/misoup-library"

    companion object {
        const val REMOTE_ROOT_NAME = "misoup-library"
    }
}

/** http 地址被拒（T04 HTTPS 强制）；携带明确警告文案供 UI/报告展示 */
class InsecureHttpException(message: String) : IllegalArgumentException(message)
