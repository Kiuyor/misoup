package com.misoup.app.ai.variants

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.ai.AiError
import com.misoup.app.ai.TextLlmClient
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.recognition.visionllm.LlmConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T02 变式生成（真实 StoreService + mock 网络层）：
 * - 成功：Prompt 含考点与题干+答案；解析出变式列表
 * - 数量越界 → failure（不发请求）
 * - 条目不存在 → failure；LLM 未配置 → NotReady；429 → 限流文案
 */
class VariantGeneratorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: StoreService

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        store = StoreService(root, LibraryIndex(), FakeImageEncoder())
    }

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

    private val json = Json { ignoreUnknownKeys = true }

    /** 构造 choices[0].message.content=<text> 的 chat 响应（content 自动 JSON 转义） */
    private fun chat(text: String): String = json.encodeToString(
        buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "message",
                                buildJsonObject { put("content", text) }
                            )
                        }
                    )
                }
            )
        }
    )

    private val variantsJson =
        """[{"question":"变式一：求最小值","answer":"1","hint":"配方"},{"question":"变式二：求最大值","answer":"9"}]"""

    private fun readyConfig() = LlmConfig(
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "sk-test",
        model = "deepseek-chat",
        enabled = true,
    )

    private fun generator(fake: FakeInterceptor, cfg: () -> LlmConfig = ::readyConfig): VariantGenerator {
        val llm = TextLlmClient(
            okHttp = OkHttpClient.Builder().addInterceptor(fake).build(),
            cfg = cfg
        )
        return VariantGenerator(llm, store)
    }

    private suspend fun createEntry(id: String = "uuid-1"): String {
        val text = "## 题干\n求 f(x)=x^2 最值\n\n## 答案\n9\n\n## 解析\n配方"
        store.create(makeMeta(id = id, subject = "数学", knowledgePoints = listOf("二次函数最值")), FakeImageAsset(10, 10), text).getOrThrow()
        return id
    }

    @Test
    fun `生成-成功-prompt含考点与原题-解析变式`() = runTest {
        createEntry()
        val fake = FakeInterceptor(listOf(200 to chat(variantsJson)))
        val g = generator(fake)

        val result = g.generateVariants("uuid-1", count = 2)
        assertTrue(result.isSuccess)
        val list = result.getOrThrow()
        assertEquals(2, list.size)
        assertEquals("变式一：求最小值", list[0].question)
        assertEquals("配方", list[0].hint)
        assertTrue(list[1].hint == null)

        // Prompt 内容：考点 + 题干+答案（不含解析）
        val body = readBody(fake.requests[0].body!!)
        assertTrue(body.contains("二次函数最值"))
        assertTrue(body.contains("求 f(x)=x^2 最值"))
        assertTrue(body.contains("答案"))
        assertTrue(!body.contains("配方"))
    }

    @Test
    fun `生成-默认count为2`() = runTest {
        createEntry()
        val fake = FakeInterceptor(listOf(200 to chat(variantsJson)))
        val g = generator(fake)
        val result = g.generateVariants("uuid-1")
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().size)
    }

    @Test
    fun `生成-数量越界-不发请求-failure`() = runTest {
        createEntry()
        val fake = FakeInterceptor(listOf(200 to chat(variantsJson)))
        val g = generator(fake)
        assertTrue(g.generateVariants("uuid-1", count = 0).isFailure)
        assertTrue(g.generateVariants("uuid-1", count = 4).isFailure)
        assertEquals(0, fake.callCount.get())
    }

    @Test
    fun `生成-条目不存在-failure`() = runTest {
        val fake = FakeInterceptor(listOf(200 to chat(variantsJson)))
        val g = generator(fake)
        val result = g.generateVariants("no-such-id")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("不存在"))
        assertEquals(0, fake.callCount.get())
    }

    @Test
    fun `生成-未配置-NotReady-不发请求`() = runTest {
        createEntry()
        val fake = FakeInterceptor(listOf(200 to chat(variantsJson)))
        val g = generator(fake) { LlmConfig() }
        val result = g.generateVariants("uuid-1")
        assertTrue(result.isFailure)
        assertTrue(AiError.isNotReady(result.exceptionOrNull()!!))
        assertEquals(0, fake.callCount.get())
    }

    @Test
    fun `生成-429-失败-限流文案`() = runTest {
        createEntry()
        val fake = FakeInterceptor(listOf(429 to """{"error":{"message":"限流"}}"""))
        val g = generator(fake)
        val result = g.generateVariants("uuid-1")
        assertTrue(result.isFailure)
        assertEquals(AiError.RATE_LIMIT_MESSAGE, AiError.messageOf(result.exceptionOrNull()!!))
    }

    @Test
    fun `生成-模型输出非JSON数组-失败`() = runTest {
        createEntry()
        val fake = FakeInterceptor(listOf(200 to chat("抱歉我看不清")))
        val g = generator(fake)
        val result = g.generateVariants("uuid-1")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("不是 JSON 数组"))
    }

    @Test
    fun `生成-落盘-文件名与内容`() = runTest {
        val id = createEntry()
        val fake = FakeInterceptor(listOf(200 to chat(variantsJson)))
        val g = generator(fake)
        val variants = g.generateVariants(id, count = 2).getOrThrow()

        // 保存：变式/yyyyMMdd_HHmmss_变式N.md（N 从 1 起；2025-08-16 14:05:23 +08:00）
        val now = 1_755_324_323_000L
        val files = variants.mapIndexed { i, v ->
            VariantFileName.generate(now, i + 1) to VariantMarkdown.render(v)
        }.toMap()
        assertTrue(store.saveVariantFiles(id, files).isSuccess)

        val names = store.listVariantFiles(id).getOrThrow()
        assertEquals(listOf("20250816_140523_变式2.md", "20250816_140523_变式1.md"), names)

        val content = store.readVariantFile(id, "20250816_140523_变式1.md").getOrThrow()
        assertTrue(content.contains("## 题干\n变式一：求最小值"))
        assertTrue(content.contains("## 答案\n1"))
        assertTrue(content.contains("## 提示\n配方"))

        // 变式目录在条目目录下：<root>/数学/<dirName>/变式/
        val entryDir = File(root, "数学").listFiles()!!.first { it.isDirectory && File(it, "变式").isDirectory }
        assertTrue(File(entryDir, "变式").listFiles()!!.any { it.name == "20250816_140523_变式2.md" })
    }
}

/** 读取 RequestBody 内容（okhttp5 无 string() 扩展，用 Buffer） */
private fun readBody(body: okhttp3.RequestBody): String {
    val buffer = okio.Buffer()
    body.writeTo(buffer)
    return buffer.readUtf8()
}
