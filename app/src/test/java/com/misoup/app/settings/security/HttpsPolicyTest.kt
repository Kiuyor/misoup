package com.misoup.app.settings.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** HTTPS 强制策略（模块 10 T08） */
class HttpsPolicyTest {

    @Test
    fun `https 地址判定 - 大小写与空白不敏感`() {
        assertTrue(HttpsPolicy.isSecureUrl("https://open.bigmodel.cn/api/paas/v4"))
        assertTrue(HttpsPolicy.isSecureUrl("  HTTPS://EXAMPLE.COM  "))
        assertFalse(HttpsPolicy.isSecureUrl("http://example.com"))
        assertFalse(HttpsPolicy.isSecureUrl("example.com"))
        assertFalse(HttpsPolicy.isSecureUrl(""))
    }

    @Test
    fun `http 地址判定 - 明文 http 可识别`() {
        assertTrue(HttpsPolicy.isHttpUrl("http://192.168.1.10:8080/dav"))
        assertTrue(HttpsPolicy.isHttpUrl("HTTP://EXAMPLE.COM"))
        assertFalse(HttpsPolicy.isHttpUrl("https://example.com"))
        assertFalse(HttpsPolicy.isHttpUrl("ftp://example.com"))
    }

    @Test
    fun `requireHttps - https 放行`() {
        val r = HttpsPolicy.requireHttps("https://dav.jianguoyun.com/dav/")
        assertTrue(r.isSuccess)
    }

    @Test
    fun `requireHttps - http 拒绝并携带提示文案`() {
        val r = HttpsPolicy.requireHttps("http://192.168.1.10/dav")
        assertTrue(r.isFailure)
        val e = r.exceptionOrNull()!!
        assertTrue(e is InsecureHttpException)
        assertTrue(e.message!!.contains("仅支持 HTTPS"))
        assertTrue(e.message!!.contains("http://192.168.1.10/dav"))
    }

    @Test
    fun `requireHttps - 空地址与缺协议头拒绝`() {
        assertTrue(HttpsPolicy.requireHttps("").isFailure)
        assertTrue(HttpsPolicy.requireHttps("   ").isFailure)
        val noScheme = HttpsPolicy.requireHttps("dav.jianguoyun.com/dav")
        assertTrue(noScheme.isFailure)
        assertTrue(noScheme.exceptionOrNull()!!.message!!.contains("缺少协议头"))
    }

    @Test
    fun `rejectMessage - 文案包含原地址`() {
        val msg = HttpsPolicy.rejectMessage("http://x.com")
        assertTrue(msg.contains("http://x.com"))
        assertTrue(msg.contains("已拦截"))
    }

    @Test
    fun `网络层拦截语义 - http 一律失败即使其余字段完整`() {
        // WebDAV/LLM 均以本策略兜底：http 地址绝不放行
        val evil = HttpsPolicy.requireHttps("http://example.com/api")
        assertTrue(evil.isFailure)
        try {
            throw evil.exceptionOrNull()!!
        } catch (expected: InsecureHttpException) {
            // 预期：专门异常类型便于网络层 catch 后转 UI 提示
        } catch (unexpected: Throwable) {
            fail("应为 InsecureHttpException，实际 ${unexpected::class.simpleName}")
        }
    }
}
