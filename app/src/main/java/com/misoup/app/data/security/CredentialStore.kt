// EncryptedSharedPreferences / MasterKey 在 security-crypto 1.1.0 标记弃用，
// 但模块文档「安全实现」定稿仍用 EncryptedSharedPreferences（KeyStore 加密），故压制弃用告警
@file:Suppress("DEPRECATION")

package com.misoup.app.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 凭据安全存储：EncryptedSharedPreferences 统一封装（任务 10-T07）
 *
 * - WebDAV 密码 / 百度 Key / BYOK Key 等敏感凭据一律走本类，禁止落明文 SharedPreferences
 * - 底层：MasterKey（AES256_GCM，密钥存 KeyStore）+ EncryptedSharedPreferences
 *   （键加密 AES256_SIV / 值加密 AES256_GCM，详见模块文档「安全实现」）
 * - 线程安全：单例懒加载（double-checked locking）；SharedPreferences 读写本身线程安全
 * - key 必须带 "misoup_" 前缀（见 [CredentialKeys]），防串键
 *
 * 注意：EncryptedSharedPreferences 依赖 Android 运行时，JVM 单测无法直接覆盖，
 * 需真机/模拟器验证；key 规则与 DTO 序列化等纯逻辑已抽到
 * [CredentialKeys] / [CredentialCodec] 并由 JVM 单测覆盖。
 */
class CredentialStore private constructor(private val prefs: SharedPreferences) {

    /** 写入凭据（apply 异步落盘；key 非法直接抛 IllegalArgumentException） */
    fun put(key: String, value: String) {
        require(CredentialKeys.isValid(key)) { "凭据 key 必须以 '${CredentialKeys.PREFIX}' 开头: $key" }
        prefs.edit().putString(key, value).apply()
    }

    /** 读取凭据；不存在返回 null */
    fun get(key: String): String? = prefs.getString(key, null)

    /** 删除凭据（apply 异步落盘；key 非法直接抛 IllegalArgumentException） */
    fun remove(key: String) {
        require(CredentialKeys.isValid(key)) { "凭据 key 必须以 '${CredentialKeys.PREFIX}' 开头: $key" }
        prefs.edit().remove(key).apply()
    }

    /** 是否存在该凭据 */
    fun has(key: String): Boolean = prefs.contains(key)

    companion object {
        @Volatile
        private var instance: CredentialStore? = null

        /** 单例获取（线程安全） */
        fun getInstance(context: Context): CredentialStore =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        /** 测试用：重置单例 */
        fun reset() {
            instance = null
        }

        // EncryptedSharedPreferences / MasterKey.Builder 在 security-crypto 1.1.0 标记弃用，
        // 但模块文档「安全实现」定稿仍用 EncryptedSharedPreferences（KeyStore 加密），故压制弃用告警
        private fun build(context: Context): CredentialStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                CredentialKeys.PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return CredentialStore(prefs)
        }
    }
}
