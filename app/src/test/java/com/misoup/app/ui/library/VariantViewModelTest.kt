package com.misoup.app.ui.library

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.ai.AiError
import com.misoup.app.ai.FakeTextLlm
import com.misoup.app.ai.variants.VariantGenerator
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 变式出题 VM（模块 07 T03）：真实 StoreService + FakeTextLlm。
 * - 生成成功：保存变式/ 文件 + 列表刷新 + 弹窗关闭
 * - 未配置 → notReady 引导（不报错）；限流/断网 → error = 「AI 服务不可用」
 * - 数量 1-3 越界忽略；答案展开切换；重进页面加载已存变式
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VariantViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: StoreService

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        root = tmp.newFolder("library")
        store = StoreService(root, LibraryIndex(), FakeImageEncoder())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun createEntry(id: String = "uuid-1"): String {
        store.create(
            makeMeta(id = id, subject = "数学", knowledgePoints = listOf("二次函数最值")),
            FakeImageAsset(100, 200),
            "## 题干\n求最值\n\n## 答案\n9"
        ).getOrThrow()
        return id
    }

    private fun vm(
        id: String,
        fake: FakeTextLlm,
        nowMillis: () -> Long = { 1_755_324_323_000L }
    ): VariantViewModel {
        val generator = VariantGenerator(fake, store)
        return VariantViewModel(id, generator, store, nowMillis)
    }

    private val variantsJson =
        """[{"question":"变式一","answer":"A1","hint":"H1"},{"question":"变式二","answer":"A2"}]"""

    @Test
    fun `生成成功-保存文件-列表刷新-弹窗关闭`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success(variantsJson))
        val v = vm("uuid-1", fake)

        v.openDialog()
        v.setCount(3)
        v.generateAndSave()
        advanceUntilIdle()

        val s = v.state.value
        assertFalse(s.dialogVisible)
        assertFalse(s.generating)
        assertNull(s.error)
        assertEquals(2, s.saved.size)
        // 列表按文件名倒序（最新在前）：变式2.md 在前
        assertEquals("变式二", s.saved[0].variant.question)
        assertEquals("A2", s.saved[0].variant.answer)
        assertEquals("变式一", s.saved[1].variant.question)
        // 文件名格式：变式/yyyyMMdd_HHmmss_变式N.md
        assertTrue(s.saved[0].fileName.matches(Regex("\\d{8}_\\d{6}_变式\\d+\\.md")))
        // 落盘真实存在
        val entryDir = File(root, "数学").listFiles()!!.first { it.isDirectory && File(it, "变式").isDirectory }
        assertEquals(2, File(entryDir, "变式").listFiles()!!.size)
    }

    @Test
    fun `数量选择-越界忽略-1到3生效`() = runTest {
        createEntry()
        val v = vm("uuid-1", FakeTextLlm())
        assertEquals(2, v.state.value.count)
        v.setCount(0)
        assertEquals(2, v.state.value.count)
        v.setCount(5)
        assertEquals(2, v.state.value.count)
        v.setCount(3)
        assertEquals(3, v.state.value.count)
        v.setCount(1)
        assertEquals(1, v.state.value.count)
    }

    @Test
    fun `未配置-引导态-不报错-不发请求`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success(variantsJson), ready = false)
        val v = vm("uuid-1", fake)

        v.openDialog()
        v.generateAndSave()
        advanceUntilIdle()

        val s = v.state.value
        assertTrue(s.notReady)
        assertNull(s.error)
        assertTrue(fake.prompts.isEmpty())
        assertTrue(s.dialogVisible)   // 弹窗保留，展示引导
    }

    @Test
    fun `生成失败-限流-明确提示AI服务不可用`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.failure(IllegalStateException("HTTP 429")))
        val v = vm("uuid-1", fake)

        v.openDialog()
        v.generateAndSave()
        advanceUntilIdle()

        val s = v.state.value
        assertFalse(s.generating)
        assertEquals(AiError.RATE_LIMIT_MESSAGE, s.error)
        assertEquals(0, s.saved.size)
    }

    @Test
    fun `生成失败-模型输出坏JSON-兜底提示`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success("不是 JSON"))
        val v = vm("uuid-1", fake)

        v.openDialog()
        v.generateAndSave()
        advanceUntilIdle()

        assertEquals(AiError.UNAVAILABLE_MESSAGE, v.state.value.error)
        assertTrue(v.state.value.dialogVisible)   // 失败时弹窗保留便于重试
    }

    @Test
    fun `生成中-防重复点击`() = runTest {
        createEntry()
        // 永不返回的 fake：complete 挂起 → generating 保持 true，第二次调用被忽略
        val fake = object : FakeTextLlm() {
            override suspend fun complete(prompt: String): Result<String> {
                prompts.add(prompt)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val v = vm("uuid-1", fake)
        v.openDialog()
        v.generateAndSave()
        advanceUntilIdle()
        assertTrue(v.state.value.generating)
        v.generateAndSave()   // 防抖：不产生第二个请求
        assertEquals(1, fake.prompts.size)
    }

    @Test
    fun `答案展开-切换`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success(variantsJson))
        val v = vm("uuid-1", fake)
        v.openDialog()
        v.generateAndSave()
        advanceUntilIdle()

        val fileName = v.state.value.saved[0].fileName
        v.toggleExpand(fileName)
        assertTrue(fileName in v.state.value.expanded)
        v.toggleExpand(fileName)
        assertFalse(fileName in v.state.value.expanded)
    }

    @Test
    fun `重进详情-加载已存变式`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success(variantsJson))
        val v = vm("uuid-1", fake)
        v.openDialog()
        v.generateAndSave()
        advanceUntilIdle()
        assertEquals(2, v.state.value.saved.size)

        // 新 VM 实例（模拟重进页面）：init refresh 加载磁盘
        val v2 = vm("uuid-1", FakeTextLlm())
        advanceUntilIdle()
        assertEquals(2, v2.state.value.saved.size)
        assertTrue(v2.state.value.loaded)
    }
}
