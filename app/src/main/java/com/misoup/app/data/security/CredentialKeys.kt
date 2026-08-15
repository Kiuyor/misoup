package com.misoup.app.data.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 凭据 Key 命名规则与凭据 DTO（纯逻辑，无 Android 依赖，可 JVM 单测）
 *
 * - 统一前缀 "misoup_"：与普通 SharedPreferences 键隔离，也便于肉眼识别加密区键
 * - 各凭据 key 常量：WebDAV（模块 09）/ 百度 OCR（模块 04/05）/ BYOK（模块 05）
 * - 凭据 DTO 供 05/09 模块直接复用；序列化走 [CredentialCodec]（容错 Json）
 */
object CredentialKeys {

    /** 统一命名空间前缀：所有凭据 key 必须以此开头 */
    const val PREFIX = "misoup_"

    /** 加密 SharedPreferences 文件名（独立于普通配置，杜绝明文/密文混存） */
    const val PREFS_NAME = "misoup_secure_creds"

    // ---- WebDAV 同步（模块 09）----
    const val WEBDAV_URL = PREFIX + "webdav_url"
    const val WEBDAV_USERNAME = PREFIX + "webdav_username"
    const val WEBDAV_PASSWORD = PREFIX + "webdav_password"

    // ---- 百度 OCR（模块 04/05；API Key + Secret Key 成对）----
    const val BAIDU_API_KEY = PREFIX + "baidu_api_key"
    const val BAIDU_SECRET_KEY = PREFIX + "baidu_secret_key"

    // ---- BYOK 自定义模型（模块 05；视觉/文本各一套 baseUrl+key+model）----
    const val BYOK_VISION_BASE_URL = PREFIX + "byok_vision_base_url"
    const val BYOK_VISION_KEY = PREFIX + "byok_vision_key"
    const val BYOK_VISION_MODEL = PREFIX + "byok_vision_model"
    const val BYOK_TEXT_BASE_URL = PREFIX + "byok_text_base_url"
    const val BYOK_TEXT_KEY = PREFIX + "byok_text_key"
    const val BYOK_TEXT_MODEL = PREFIX + "byok_text_model"

    /** 全部凭据 key（供"清除全部凭据"等批量操作遍历） */
    val allKeys: List<String> = listOf(
        WEBDAV_URL, WEBDAV_USERNAME, WEBDAV_PASSWORD,
        BAIDU_API_KEY, BAIDU_SECRET_KEY,
        BYOK_VISION_BASE_URL, BYOK_VISION_KEY, BYOK_VISION_MODEL,
        BYOK_TEXT_BASE_URL, BYOK_TEXT_KEY, BYOK_TEXT_MODEL,
    )

    /**
     * key 合法性：必须带前缀且前缀后非空。
     * 防止误把普通配置键（无前缀）写进加密区、或空 key 污染。
     */
    fun isValid(key: String): Boolean = key.startsWith(PREFIX) && key.length > PREFIX.length
}

/** WebDAV 同步凭据（模块 09 用） */
@Serializable
data class WebDavCredential(
    val url: String = "",
    val username: String = "",
    val password: String = "",
)

/** 百度 OCR 凭据（模块 04/05 用） */
@Serializable
data class BaiduCredential(
    val apiKey: String = "",
    val secretKey: String = "",
)

/** BYOK 自定义模型凭据（模块 05 用；视觉/文本模型各存一份） */
@Serializable
data class ByokCredential(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
)

/**
 * 凭据 DTO 序列化（容错 Json，风格同 data/store/MetaJson）
 *
 * - ignoreUnknownKeys：未来加字段不影响旧密文解析（向后兼容）
 * - encodeDefaults：默认值也写入，密文自洽可读
 * - 使用场景：把整套凭据（如 WebDAV 三项）JSON 化后存进 EncryptedSharedPreferences 单 key
 */
object CredentialCodec {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(cred: WebDavCredential): String = json.encodeToString(cred)

    fun encode(cred: BaiduCredential): String = json.encodeToString(cred)

    fun encode(cred: ByokCredential): String = json.encodeToString(cred)

    /** 解析；失败返回 failure（调用方按"未配置"处理） */
    fun decodeWebDav(text: String): Result<WebDavCredential> = runCatching {
        json.decodeFromString<WebDavCredential>(text)
    }

    /** 解析；失败返回 failure（调用方按"未配置"处理） */
    fun decodeBaidu(text: String): Result<BaiduCredential> = runCatching {
        json.decodeFromString<BaiduCredential>(text)
    }

    /** 解析；失败返回 failure（调用方按"未配置"处理） */
    fun decodeByok(text: String): Result<ByokCredential> = runCatching {
        json.decodeFromString<ByokCredential>(text)
    }
}
