package com.misoup.app.ui.library

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.ai.AiError
import com.misoup.app.ai.FakeTextLlm
import com.misoup.app.ai.qanda.QaMessage
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

/**
 * 答疑对话 VM（模块 07 T04/T05）：真实 StoreService + FakeTextLlm。
 * - 打开：加载错题上下文（考点+文本.md）
 * - 发送成功：消息流 user+assistant；Prompt 含前缀/上下文/提问
 * - 快捷诉求：重讲/举例/换一种解法 → 命中跟随指令
 * - 未配置 → notReady 引导；限流/断网 → error = 「AI 服务不可用」
 * - 关闭：会话清空（仅内存）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QaViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: StoreService

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        store = StoreService(tmp.newFolder("library"), LibraryIndex(), FakeImageEncoder())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun createEntry(
        id: String = "uuid-1",
        text: String = "## 题干\n求 f(x)=x^2 最值\n\n## 答案\n9\n\n## 解析\n配方"
    ) {
        store.create(
            makeMeta(id = id, subject = "数学", knowledgePoints = listOf("二次函数最值")),
            FakeImageAsset(100, 200),
            text
        ).getOrThrow()
    }

    private fun vm(id: String, fake: FakeTextLlm) = QaViewModel(id, fake, store)

    @Test
    fun `打开-加载错题上下文`() = runTest {
        createEntry()
        val v = vm("uuid-1", FakeTextLlm())
        v.open()
        advanceUntilIdle()
        val s = v.state.value
        assertTrue(s.sheetVisible)
        assertTrue(s.contextLoaded)
    }

    @Test
    fun `发送成功-消息流与Prompt`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success("先配方再讨论对称轴"))
        val v = vm("uuid-1", fake)
        v.open()
        advanceUntilIdle()

        v.send("为什么要配方？")
        advanceUntilIdle()

        val s = v.state.value
        assertFalse(s.sending)
        assertNull(s.error)
        assertEquals(2, s.messages.size)
        assertEquals(QaMessage.Role.USER, s.messages[0].role)
        assertEquals("为什么要配方？", s.messages[0].text)
        assertEquals(QaMessage.Role.ASSISTANT, s.messages[1].role)
        assertEquals("先配方再讨论对称轴", s.messages[1].text)

        // Prompt：文档定稿前缀 + 考点/错题内容 + 用户问题
        val prompt = fake.prompts[0]
        assertTrue(prompt.startsWith("你是耐心的高中理科老师。"))
        assertTrue(prompt.contains("考点: 二次函数最值"))
        assertTrue(prompt.contains("求 f(x)=x^2 最值"))
        assertTrue(prompt.contains("用户问题: 为什么要配方？"))
    }

    @Test
    fun `多轮对话-历史上下文连贯`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success("好，我们换个角度"))
        val v = vm("uuid-1", fake)
        v.open()
        advanceUntilIdle()

        v.send("第一问")
        advanceUntilIdle()
        v.send("第二问")
        advanceUntilIdle()

        // 第二轮 Prompt 带第一轮历史（用户: 第一问 / 老师: 好，我们换个角度）
        val secondPrompt = fake.prompts[1]
        assertTrue(secondPrompt.contains("历史对话:"))
        assertTrue(secondPrompt.contains("用户: 第一问"))
        assertTrue(secondPrompt.contains("老师: 好，我们换个角度"))
        assertEquals(4, v.state.value.messages.size)
    }

    @Test
    fun `快捷诉求-换一种解法-命中指令`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success("用判别式法……"))
        val v = vm("uuid-1", fake)
        v.open()
        advanceUntilIdle()

        v.quickAction(QaViewModel.QuickAction.ALT_METHOD)
        advanceUntilIdle()

        val prompt = fake.prompts[0]
        assertTrue(prompt.contains("用户问题: 请换一种解法"))
        assertTrue(prompt.contains("换一种解法（不同思路）"))
    }

    @Test
    fun `快捷诉求-重讲与举例`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success("ok"))
        val v = vm("uuid-1", fake)
        v.open()
        advanceUntilIdle()

        v.quickAction(QaViewModel.QuickAction.RETELL)
        advanceUntilIdle()
        assertTrue(fake.prompts[0].contains("重新讲解一遍"))

        v.quickAction(QaViewModel.QuickAction.EXAMPLE)
        advanceUntilIdle()
        assertTrue(fake.prompts[1].contains("举一个具体的例子"))
    }

    @Test
    fun `未配置-引导态-不报错`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success("x"), ready = false)
        val v = vm("uuid-1", fake)
        v.open()
        advanceUntilIdle()

        v.send("怎么解")
        advanceUntilIdle()

        val s = v.state.value
        assertTrue(s.notReady)
        assertNull(s.error)
        assertTrue(fake.prompts.isEmpty())
        assertTrue(s.messages.isEmpty())
    }

    @Test
    fun `发送失败-网络异常-明确提示`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.failure(java.io.IOException("socket closed")))
        val v = vm("uuid-1", fake)
        v.open()
        advanceUntilIdle()

        v.send("怎么解")
        advanceUntilIdle()

        val s = v.state.value
        assertEquals(AiError.NETWORK_MESSAGE, s.error)
        // 用户消息保留，老师未回复；失败提示明确不静默
        assertEquals(1, s.messages.size)
        assertEquals(QaMessage.Role.USER, s.messages[0].role)
    }

    @Test
    fun `发送失败-限流429-明确提示AI服务不可用`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.failure(IllegalStateException("HTTP 429")))
        val v = vm("uuid-1", fake)
        v.open()
        advanceUntilIdle()

        v.send("怎么解")
        advanceUntilIdle()

        val s = v.state.value
        assertEquals(AiError.RATE_LIMIT_MESSAGE, s.error)
        assertFalse(s.sending)
        assertEquals(1, s.messages.size)   // 用户提问保留，可重试
    }

    @Test
    fun `发送-空白或发送中-忽略`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success("ok"))
        val v = vm("uuid-1", fake)
        v.open()
        advanceUntilIdle()

        v.send("  ")
        advanceUntilIdle()
        assertTrue(fake.prompts.isEmpty())

        v.send("问题")
        advanceUntilIdle()
        assertEquals(1, fake.prompts.size)
    }

    @Test
    fun `关闭-会话清空-仅内存`() = runTest {
        createEntry()
        val fake = FakeTextLlm(result = Result.success("ok"))
        val v = vm("uuid-1", fake)
        v.open()
        advanceUntilIdle()
        v.send("问题")
        advanceUntilIdle()
        assertEquals(2, v.state.value.messages.size)

        v.close()
        val s = v.state.value
        assertFalse(s.sheetVisible)
        assertTrue(s.messages.isEmpty())
    }
}
