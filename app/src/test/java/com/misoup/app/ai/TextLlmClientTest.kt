package com.misoup.app.ai

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.misoup.app.recognition.visionllm.LlmConfig

/**
 * T01 文本 LLM 客户端（mock 网络层：fake OkHttp 拦截器）：
 * - 成功：请求结构（URL/Auth/body 含 prompt 与 model）+ 回答内容解析
 * - 429 → 退避重试 1 次 → 成功；429 两次 → 失败（限流文案）
 * - 未配置/未启用 → NotReady 直接失败（不发请求）→ isNotReady 判定
 * - 网络异常 → 失败（网络文案）；HTTP 错误 → 失败（透出 error.message）
 */
class TextLlmClientTest {

    private class FakeInterceptor(
        val responses: List<Pair<Int, String>>,
    ) : Interceptor {
        val callCount = AtomicInteger(0)
        val requests = mutableListOf<okhttp3.Request>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val idx = callCount.getAndIncrement()
            requests.add(chain.request())
            val (code, body) = responses[minOf(idx, responses.size - 1)]
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Err")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun client(fake: FakeInterceptor, cfg: () -> LlmConfig): TextLlmClient =
        TextLlmClient(OkHttpClient.Builder().addInterceptor(fake).build(), cfg)

    private fun readyConfig() = LlmConfig(
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "sk-test",
        model = "deepseek-chat",
        enabled = true,
    )

    private val chatOk = """{"choices":[{"message":{"content":"回答内容"}}]}"""

    // ---------- 成功路径 ----------

    @Test
    fun `complete-成功-请求结构正确-解析回答`() = runTest {
        val fake = FakeInterceptor(listOf(200 to chatOk))
        val c = client(fake) { readyConfig() }

        val result = c.complete("你是出题老师…")
        assertTrue(result.isSuccess)
        assertEquals("回答内容", result.getOrThrow())

        assertEquals(1, fake.callCount.get())
        val req = fake.requests[0]
        assertEquals("https://api.deepseek.com/v1/chat/completions", req.url.toString())
        assertEquals("Bearer sk-test", req.header("Authorization"))
        val body = readBody(req.body!!)
        assertTrue(body.contains("你是出题老师"))
        assertTrue(body.contains("\"model\":\"deepseek-chat\""))
        // 纯文本请求：content 为字符串，无 image_url
        assertTrue(!body.contains("image_url"))
        assertTrue(!body.contains("data:image"))
    }

    @Test
    fun `complete-content为文本片段数组-仍解析`() = runTest {
        val body = """{"choices":[{"message":{"content":[{"type":"text","text":"片段一"},{"type":"text","text":"片段二"}]}}]}"""
        val fake = FakeInterceptor(listOf(200 to body))
        val c = client(fake) { readyConfig() }
        assertEquals("片段一片段二", c.complete("p").getOrThrow())
    }

    @Test
    fun `isReady-反映配置`() {
        val c = client(FakeInterceptor(listOf(200 to chatOk))) { readyConfig() }
        assertTrue(c.isReady())
        val c2 = client(FakeInterceptor(listOf(200 to chatOk))) { LlmConfig() }
        assertFalse(c2.isReady())
    }

    // ---------- 429 退避重试 ----------

    @Test
    fun `429-退避重试1次-第二次成功`() = runTest {
        val fake = FakeInterceptor(listOf(429 to """{"error":{"message":"限流"}}""", 200 to chatOk))
        val c = client(fake) { readyConfig() }
        val result = c.complete("p")
        assertTrue(result.isSuccess)
        assertEquals(2, fake.callCount.get())
    }

    @Test
    fun `429-两次-重试后仍失败-限流文案`() = runTest {
        val fake = FakeInterceptor(listOf(429 to """{"error":{"message":"限流"}}"""))
        val c = client(fake) { readyConfig() }
        val result = c.complete("p")
        assertTrue(result.isFailure)
        assertEquals(2, fake.callCount.get())
        assertEquals(AiError.RATE_LIMIT_MESSAGE, AiError.messageOf(result.exceptionOrNull()!!))
    }

    // ---------- 未配置/未启用（无 Key → 引导） ----------

    @Test
    fun `未启用-不发请求-NotReady失败`() = runTest {
        val fake = FakeInterceptor(listOf(200 to chatOk))
        val c = client(fake) { readyConfig().copy(enabled = false) }
        val result = c.complete("p")
        assertTrue(result.isFailure)
        assertTrue(AiError.isNotReady(result.exceptionOrNull()!!))
        assertEquals(0, fake.callCount.get())
        assertEquals(AiError.NOT_READY_MESSAGE, AiError.messageOf(result.exceptionOrNull()!!))
    }

    @Test
    fun `缺apiKey-不发请求-NotReady失败`() = runTest {
        val fake = FakeInterceptor(listOf(200 to chatOk))
        val c = client(fake) { readyConfig().copy(apiKey = "") }
        assertTrue(c.complete("p").isFailure)
        assertEquals(0, fake.callCount.get())
    }

    // ---------- 网络 / HTTP 错误 ----------

    @Test
    fun `网络异常-失败-网络文案`() = runTest {
        val ok = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw java.io.IOException("connection refused") })
            .build()
        val c = TextLlmClient(ok, cfg = { readyConfig() })
        val result = c.complete("p")
        assertTrue(result.isFailure)
        assertEquals(AiError.NETWORK_MESSAGE, AiError.messageOf(result.exceptionOrNull()!!))
    }

    @Test
    fun `HTTP错误-失败-透出error-message`() = runTest {
        val fake = FakeInterceptor(listOf(401 to """{"error":{"message":"invalid api key"}}"""))
        val c = client(fake) { readyConfig() }
        val result = c.complete("p")
        assertTrue(result.isFailure)
        assertEquals("invalid api key", result.exceptionOrNull()?.message)
        assertEquals(1, fake.callCount.get())   // 非 429 不重试
    }

    @Test
    fun `响应体非JSON-失败-兜底文案`() = runTest {
        val fake = FakeInterceptor(listOf(200 to "not json at all"))
        val c = client(fake) { readyConfig() }
        val result = c.complete("p")
        assertTrue(result.isFailure)
        assertEquals(AiError.UNAVAILABLE_MESSAGE, AiError.messageOf(result.exceptionOrNull()!!))
    }

    // ---------- 超时（复用 05 默认客户端） ----------

    @Test
    fun `默认客户端-超时60s-与05一致`() {
        val client = com.misoup.app.recognition.visionllm.VisionLlmClient.defaultHttpClient()
        assertEquals(60_000L, client.connectTimeoutMillis.toLong())
        assertEquals(60_000L, client.readTimeoutMillis.toLong())
        assertEquals(60_000L, client.writeTimeoutMillis.toLong())
    }
}

/** 读取 RequestBody 内容（okhttp5 无 string() 扩展，用 Buffer） */
private fun readBody(body: okhttp3.RequestBody): String {
    val buffer = okio.Buffer()
    body.writeTo(buffer)
    return buffer.readUtf8()
}
