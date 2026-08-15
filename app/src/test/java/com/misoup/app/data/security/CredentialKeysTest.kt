package com.misoup.app.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务 10-T07 凭据安全：key 命名规则 / 常量 / 凭据 DTO 序列化（纯逻辑部分）
 *
 * EncryptedSharedPreferences 依赖 Android 运行时，JVM 单测不覆盖；
 * 其封装调用（put/get/remove/has）需真机/模拟器验证。
 */
class CredentialKeysTest {

    @Test
    fun `所有凭据key带统一前缀`() {
        assertTrue(CredentialKeys.allKeys.isNotEmpty())
        CredentialKeys.allKeys.forEach { key ->
            assertTrue("key 缺前缀: $key", key.startsWith(CredentialKeys.PREFIX))
        }
    }

    @Test
    fun `凭据key常量不重复`() {
        assertEquals(CredentialKeys.allKeys.size, CredentialKeys.allKeys.toSet().size)
    }

    @Test
    fun `isValid接受带前缀key`() {
        assertTrue(CredentialKeys.isValid(CredentialKeys.WEBDAV_PASSWORD))
        assertTrue(CredentialKeys.isValid(CredentialKeys.BAIDU_API_KEY))
        assertTrue(CredentialKeys.isValid(CredentialKeys.BYOK_VISION_KEY))
        assertTrue(CredentialKeys.isValid("misoup_custom_key"))
    }

    @Test
    fun `isValid拒绝无前缀或空key`() {
        assertFalse(CredentialKeys.isValid("webdav_password")) // 无前缀
        assertFalse(CredentialKeys.isValid(""))                // 空
        assertFalse(CredentialKeys.isValid("misoup_"))         // 只有前缀没有名字
        assertFalse(CredentialKeys.isValid("Misoup_x"))        // 前缀大小写敏感
    }

    @Test
    fun `WebDAV凭据DTO序列化往返`() {
        val cred = WebDavCredential(
            url = "https://dav.example.com",
            username = "user01",
            password = "p@ssw0rd",
        )
        val text = CredentialCodec.encode(cred)
        assertTrue(text.contains("dav.example.com"))
        assertEquals(cred, CredentialCodec.decodeWebDav(text).getOrThrow())
    }

    @Test
    fun `空JSON反序列化为全默认凭据`() {
        assertEquals(WebDavCredential(), CredentialCodec.decodeWebDav("{}").getOrThrow())
        assertEquals(BaiduCredential(), CredentialCodec.decodeBaidu("{}").getOrThrow())
        assertEquals(ByokCredential(), CredentialCodec.decodeByok("{}").getOrThrow())
    }

    @Test
    fun `百度凭据DTO序列化往返`() {
        val cred = BaiduCredential(apiKey = "ak_123", secretKey = "sk_456")
        assertEquals(cred, CredentialCodec.decodeBaidu(CredentialCodec.encode(cred)).getOrThrow())
    }

    @Test
    fun `BYOK凭据DTO序列化往返`() {
        val cred = ByokCredential(baseUrl = "https://api.example.com/v1", apiKey = "k_abc", model = "gpt-4o")
        assertEquals(cred, CredentialCodec.decodeByok(CredentialCodec.encode(cred)).getOrThrow())
    }

    @Test
    fun `解析非法JSON返回failure不抛异常`() {
        assertTrue(CredentialCodec.decodeWebDav("not json{{").isFailure)
        assertTrue(CredentialCodec.decodeBaidu("").isFailure)
        assertTrue(CredentialCodec.decodeByok("").isFailure)
    }
}
