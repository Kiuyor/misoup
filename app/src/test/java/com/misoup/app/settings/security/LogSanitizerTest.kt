package com.misoup.app.settings.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 日志脱敏（模块 10 T08） */
class LogSanitizerTest {

    @Test
    fun `Authorization Bearer 头脱敏`() {
        val out = LogSanitizer.sanitize("请求失败: Authorization: Bearer sk-abc123XYZ url=https://api.x.com")
        assertFalse(out.contains("sk-abc123XYZ"))
        assertTrue(out.contains("Bearer ${LogSanitizer.REDACTED}"))
        // 前缀保留（定位用），密钥不泄漏
        assertTrue(out.contains("Authorization: Bearer"))
    }

    @Test
    fun `Authorization Basic 头脱敏`() {
        val out = LogSanitizer.sanitize("Authorization: Basic dXNlcjpwYXNz")
        assertFalse(out.contains("dXNlcjpwYXNz"))
        assertTrue(out.contains("Basic ${LogSanitizer.REDACTED}"))
    }

    @Test
    fun `JSON 敏感字段脱敏 - apiKey`() {
        val out = LogSanitizer.sanitize("""{"apiKey":"sk-1234567890","model":"glm-4v-flash"}""")
        assertFalse(out.contains("sk-1234567890"))
        assertTrue(out.contains(""""apiKey":"${LogSanitizer.REDACTED}""""))
    }

    @Test
    fun `查询参数脱敏 - api_key 与 token`() {
        val out = LogSanitizer.sanitize("GET /models?api_key=abc123&token=tok456")
        assertFalse(out.contains("abc123"))
        assertFalse(out.contains("tok456"))
        assertTrue(out.contains("api_key=${LogSanitizer.REDACTED}"))
    }

    @Test
    fun `password 字段脱敏 - 等号与冒号两种形态`() {
        val eq = LogSanitizer.sanitize("password=secret123")
        assertFalse(eq.contains("secret123"))
        val colon = LogSanitizer.sanitize("""{"password": "p@ssw0rd!"}""")
        assertFalse(colon.contains("p@ssw0rd"))
    }

    @Test
    fun `普通文本不被误伤`() {
        val text = "识别失败: 文件 /storage/emulated/0/DCIM/IMG_001.jpg 不存在，内容为空"
        assertEquals(text, LogSanitizer.sanitize(text))
    }

    @Test
    fun `无敏感字段的请求日志原样保留`() {
        val log = "POST https://open.bigmodel.cn/api/paas/v4/chat/completions 200 1234ms"
        assertEquals(log, LogSanitizer.sanitize(log))
    }

    @Test
    fun `maskSecrets - 已知密钥整串打码`() {
        val secrets = listOf("sk-live-abcdef", "dav-password-xyz")
        val out = LogSanitizer.maskSecrets(
            "url=https://dav.com?token=sk-live-abcdef, pwd=dav-password-xyz",
            secrets
        )
        assertFalse(out.contains("sk-live-abcdef"))
        assertFalse(out.contains("dav-password-xyz"))
    }

    @Test
    fun `maskSecrets - 短串不误伤常见单词`() {
        val out = LogSanitizer.maskSecrets("用户登录成功 key=ok", listOf("ok"))
        assertEquals("用户登录成功 key=ok", out)
    }

    @Test
    fun `脱敏不打印题目内容 - 内容字段保留结构但密钥清除`() {
        val log = """{"question":"求 x 的值","apiKey":"sk-xxx"}"""
        val out = LogSanitizer.sanitize(log)
        assertTrue(out.contains("求 x 的值"))
        assertFalse(out.contains("sk-xxx"))
    }
}
