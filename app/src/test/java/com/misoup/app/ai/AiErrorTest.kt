package com.misoup.app.ai

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T07 失败处理（纯逻辑）：失败 → 明确提示「AI 服务不可用」，不静默。
 * - NotReady → 引导文案；限流（429/限流字眼）→ 限流文案；网络 → 网络文案；其余 → 兜底
 */
class AiErrorTest {

    @Test
    fun `NotReady-引导文案-非错误提示`() {
        val t: Throwable = AiError.NotReady
        assertTrue(AiError.isNotReady(t))
        assertEquals(AiError.NOT_READY_MESSAGE, AiError.messageOf(t))
    }

    @Test
    fun `普通异常-不判NotReady`() {
        assertFalse(AiError.isNotReady(IllegalStateException("boom")))
        assertFalse(AiError.isNotReady(IOException("network")))
    }

    @Test
    fun `限流-429状态码-限流文案`() {
        val t = IllegalStateException("HTTP 429")
        assertEquals(AiError.RATE_LIMIT_MESSAGE, AiError.messageOf(t))
    }

    @Test
    fun `限流-上游限流字眼-限流文案`() {
        val t = IllegalStateException("Rate limit exceeded: RPM 3")
        assertEquals(AiError.RATE_LIMIT_MESSAGE, AiError.messageOf(t))
        val t2 = IllegalStateException("触发限流，请稍后")
        assertEquals(AiError.RATE_LIMIT_MESSAGE, AiError.messageOf(t2))
    }

    @Test
    fun `网络异常-网络文案`() {
        val t = IOException("socket closed")
        assertEquals(AiError.NETWORK_MESSAGE, AiError.messageOf(t))
        // 网络异常子类（SSL/超时）同样归类
        assertEquals(AiError.NETWORK_MESSAGE, AiError.messageOf(javax.net.ssl.SSLException("handshake")))
    }

    @Test
    fun `兜底-服务不可用文案-不静默`() {
        val t = IllegalStateException("500 服务器内部错误")
        assertEquals(AiError.UNAVAILABLE_MESSAGE, AiError.messageOf(t))
    }

    @Test
    fun `空消息异常-兜底`() {
        val t = RuntimeException()
        assertEquals(AiError.UNAVAILABLE_MESSAGE, AiError.messageOf(t))
    }
}
