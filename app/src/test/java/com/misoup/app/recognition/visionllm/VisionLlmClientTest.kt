package com.misoup.app.recognition.visionllm

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T02 视觉客户端（mock 网络层：fake OkHttp 拦截器）：
 * - 成功：请求结构（URL/Auth/body）与结构化解析
 * - 429 → 退避 10s 重试 1 次 → 成功；429 两次 → 失败
 * - 非 JSON 输出 → 重试 1 次 → 成功；两次坏输出 → 失败
 * - 未配置/未启用 → 不发请求直接失败
 * - HTTP 错误 → 失败（带 error.message）
 * - ping：纯文本最小请求 → OK
 * - 默认客户端超时 60s
 */
class VisionLlmClientTest {

    private val jsonOk = """{"choices":[{"message":{"content":"{\"question\":\"求最值\",\"answer\":\"3\",\"explanation\":\"判别式\"}"}}]}"""
    private val jsonBad = """{"choices":[{"message":{"content":"抱歉，我看不清"}}]}"""

    /** 记录每次请求并按序号喂响应 */
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

    private fun client(fake: FakeInterceptor, cfg: () -> LlmConfig): VisionLlmClient<String> {
        val ok = OkHttpClient.Builder().addInterceptor(fake).build()
        return VisionLlmClient(ok, cfg, { "data:image/png;base64,$it" })
    }

    private fun readyConfig() = LlmConfig(
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "sk-test",
        model = "glm-4v-flash",
        enabled = true,
    )

    // ---------- 成功路径 ----------

    @Test
    fun `结构识别-成功-请求结构正确-解析正确`() = runTest {
        val fake = FakeInterceptor(listOf(200 to jsonOk))
        val c = client(fake) { readyConfig() }

        val result = c.structure("img-b64")
        assertTrue(result.isSuccess)
        val content = result.getOrThrow()
        assertEquals("求最值", content.question)
        assertEquals("3", content.answer)
        assertEquals("判别式", content.explanation)

        // 请求结构：URL = baseUrl + /chat/completions；Auth = Bearer；body 含图片 URI 与 Prompt
        assertEquals(1, fake.callCount.get())
        val req = fake.requests[0]
        assertEquals("https://open.bigmodel.cn/api/paas/v4/chat/completions", req.url.toString())
        assertEquals("Bearer sk-test", req.header("Authorization"))
        val body = readBody(req.body!!)   // 一次性读；此处仅为断言
        assertTrue(body.contains("data:image/png;base64,img-b64"))
        // JSON 编码会把 Prompt 中的换行/引号转义，原文 contains 不成立；断言关键特征片段
        assertTrue(body.contains("错题录入助手"))
        assertTrue(body.contains("\"model\":\"glm-4v-flash\""))
    }

    @Test
    fun `结构识别-图片编码器-注入生效`() = runTest {
        val fake = FakeInterceptor(listOf(200 to jsonOk))
        val ok = OkHttpClient.Builder().addInterceptor(fake).build()
        val client = VisionLlmClient<String>(ok, { readyConfig() }, { "encoded:${it.uppercase()}" })
        val result = client.structure("abc")
        assertTrue(result.isSuccess)
        assertTrue(readBody(fake.requests[0].body!!).contains("encoded:ABC"))
    }

    // ---------- 429 退避重试 ----------

    @Test
    fun `429-退避重试1次-第二次成功`() = runTest {
        val fake = FakeInterceptor(listOf(429 to """{"error":{"message":"限流"}}""", 200 to jsonOk))
        val c = client(fake) { readyConfig() }
        val result = c.structure("img")
        assertTrue(result.isSuccess)
        assertEquals(2, fake.callCount.get())
    }

    @Test
    fun `429-两次-重试后仍失败`() = runTest {
        val fake = FakeInterceptor(listOf(429 to """{"error":{"message":"限流"}}"""))
        val c = client(fake) { readyConfig() }
        val result = c.structure("img")
        assertTrue(result.isFailure)
        assertEquals(2, fake.callCount.get())
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("限流"))
    }

    // ---------- 非 JSON 重试 ----------

    @Test
    fun `非JSON输出-重试1次-第二次成功`() = runTest {
        val fake = FakeInterceptor(listOf(200 to jsonBad, 200 to jsonOk))
        val c = client(fake) { readyConfig() }
        val result = c.structure("img")
        assertTrue(result.isSuccess)
        assertEquals("求最值", result.getOrThrow().question)
        assertEquals(2, fake.callCount.get())
    }

    @Test
    fun `非JSON输出-两次-失败`() = runTest {
        val fake = FakeInterceptor(listOf(200 to jsonBad))
        val c = client(fake) { readyConfig() }
        val result = c.structure("img")
        assertTrue(result.isFailure)
        assertEquals(2, fake.callCount.get())
    }

    @Test
    fun `结构缺失字段-填空字符串-仍成功`() = runTest {
        val partial = """{"choices":[{"message":{"content":"{\"question\":\"只有题干\"}"}}]}"""
        val fake = FakeInterceptor(listOf(200 to partial))
        val c = client(fake) { readyConfig() }
        val result = c.structure("img")
        assertTrue(result.isSuccess)
        assertEquals("只有题干", result.getOrThrow().question)
        assertEquals("", result.getOrThrow().answer)
        assertEquals("", result.getOrThrow().explanation)
    }

    // ---------- 未配置/未启用（无 Key 自动走离线） ----------

    @Test
    fun `未启用-不发请求-直接失败`() = runTest {
        val fake = FakeInterceptor(listOf(200 to jsonOk))
        val c = client(fake) { readyConfig().copy(enabled = false) }
        val result = c.structure("img")
        assertTrue(result.isFailure)
        assertEquals(0, fake.callCount.get())
    }

    @Test
    fun `缺apiKey-不发请求-直接失败`() = runTest {
        val fake = FakeInterceptor(listOf(200 to jsonOk))
        val c = client(fake) { readyConfig().copy(apiKey = "") }
        val result = c.structure("img")
        assertTrue(result.isFailure)
        assertEquals(0, fake.callCount.get())
    }

    @Test
    fun `缺model-不发请求-直接失败`() = runTest {
        val fake = FakeInterceptor(listOf(200 to jsonOk))
        val c = client(fake) { readyConfig().copy(model = "") }
        val result = c.structure("img")
        assertTrue(result.isFailure)
        assertEquals(0, fake.callCount.get())
    }

    // ---------- HTTP 错误 ----------

    @Test
    fun `HTTP错误-失败-带error-message`() = runTest {
        val fake = FakeInterceptor(listOf(401 to """{"error":{"message":"invalid api key"}}"""))
        val c = client(fake) { readyConfig() }
        val result = c.structure("img")
        assertTrue(result.isFailure)
        assertEquals("invalid api key", result.exceptionOrNull()?.message)
        assertEquals(1, fake.callCount.get())   // 非 429 不重试
    }

    @Test
    fun `网络异常-失败不重试`() = runTest {
        val ok = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw java.io.IOException("socket closed") })
            .build()
        val c = VisionLlmClient<String>(ok, { readyConfig() }, { "data" })
        val result = c.structure("img")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("socket closed"))
    }

    // ---------- ping（T04 连通测试） ----------

    @Test
    fun `ping-成功-纯文本请求-无图片`() = runTest {
        val fake = FakeInterceptor(listOf(200 to """{"choices":[{"message":{"content":"pong"}}]}"""))
        val c = client(fake) { readyConfig() }
        val result = c.ping()
        assertTrue(result.isSuccess)
        assertEquals("OK", result.getOrThrow())
        val body = readBody(fake.requests[0].body!!)
        assertTrue(body.contains("\"content\":\"ping\""))
        assertTrue(!body.contains("data:image"))
    }

    @Test
    fun `ping-失败-透出原因`() = runTest {
        val fake = FakeInterceptor(listOf(500 to """{"error":{"message":"服务器繁忙"}}"""))
        val c = client(fake) { readyConfig() }
        val result = c.ping()
        assertTrue(result.isFailure)
        assertEquals("服务器繁忙", result.exceptionOrNull()?.message)
    }

    @Test
    fun `ping-未配置-失败`() = runTest {
        val fake = FakeInterceptor(listOf(200 to """{"choices":[]}"""))
        val c = client(fake) { LlmConfig() }
        assertTrue(c.ping().isFailure)
        assertEquals(0, fake.callCount.get())
    }

    // ---------- 超时 60s（文档定稿） ----------

    @Test
    fun `默认客户端-超时全部60s`() {
        val client = VisionLlmClient.defaultHttpClient()
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

