package com.misoup.app.sync.webdav

import com.misoup.app.data.security.CredentialKeys
import com.misoup.app.data.security.CredentialStore

/**
 * 凭据存取抽象（模块 09 T01）：生产 = EncryptedSharedPreferences（CredentialStore），
 * 测试 = 内存 fake。CredentialStore 依赖 Android 运行时无法在 JVM 单测实例化，
 * 故同步层只依赖本接口（key 规则仍由 CredentialKeys 保证，见 data/security）。
 */
interface DavCredentialStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun has(key: String): Boolean
}

/** 生产适配：委托 CredentialStore（EncryptedSharedPreferences） */
class CredentialStoreBridge(private val delegate: CredentialStore) : DavCredentialStore {
    override fun get(key: String): String? = delegate.get(key)
    override fun put(key: String, value: String) = delegate.put(key, value)
    override fun remove(key: String) = delegate.remove(key)
    override fun has(key: String): Boolean = delegate.has(key)
}

/**
 * WebDAV 凭据仓库：DavConfig ⇄ 加密存储（模块 09 T01）
 *
 * - 地址/用户名/密码分别落 CredentialKeys.WEBDAV_* 三个加密 key
 * - "清除凭据"一键 = clear()
 */
class DavCredentialRepository(private val store: DavCredentialStore) {

    /** 读取当前配置（未配置项为空串，不抛错） */
    fun load(): DavConfig = DavConfig(
        url = store.get(CredentialKeys.WEBDAV_URL).orEmpty(),
        username = store.get(CredentialKeys.WEBDAV_USERNAME).orEmpty(),
        password = store.get(CredentialKeys.WEBDAV_PASSWORD).orEmpty()
    )

    /** 保存配置（先校验：不完整/非 https 拒绝落盘，返回原因） */
    fun save(config: DavConfig): Result<Unit> {
        config.validate().getOrElse { return Result.failure(it) }
        store.put(CredentialKeys.WEBDAV_URL, config.url.trim())
        store.put(CredentialKeys.WEBDAV_USERNAME, config.username.trim())
        store.put(CredentialKeys.WEBDAV_PASSWORD, config.password)
        return Result.success(Unit)
    }

    /** 一键清除凭据 */
    fun clear() {
        store.remove(CredentialKeys.WEBDAV_URL)
        store.remove(CredentialKeys.WEBDAV_USERNAME)
        store.remove(CredentialKeys.WEBDAV_PASSWORD)
    }

    /** 是否已配置完整 */
    val isConfigured: Boolean
        get() = load().isConfigured
}
