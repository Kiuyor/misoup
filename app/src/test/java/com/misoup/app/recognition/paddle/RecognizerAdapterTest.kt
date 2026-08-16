package com.misoup.app.recognition.paddle

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.recognition.queue.RecognitionPhase
import com.misoup.app.recognition.queue.RecognitionQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T07 识别器适配核心：成功 → 文本.md 内容（## 题干 标头）、失败 → Result.failure、
 * 超 5s 慢设备标记；并通过 RecognitionQueue 验证接入队列的完整链路。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognizerAdapterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fakeEngine(text: String, latencyMs: Long = 100L): (String) -> OcrResult = { input ->
        OcrResult(
            lines = listOf(OcrLine("$text:$input", Box(0f, 0f, 10f, 10f))),
            text = "$text:$input",
            latencyMs = latencyMs,
        )
    }

    // ---------- 成功/失败 ----------

    @Test
    fun `识别成功-返回格式化文本md内容`() = runTest {
        val adapter = RecognizerAdapter<String>(engine = fakeEngine("题干"))
        val result = adapter.recognize("img1")
        assertTrue(result.isSuccess)
        assertEquals("## 题干\n题干:img1", result.getOrThrow())
    }

    @Test
    fun `识别失败-异常转为Result失败-原因透传`() = runTest {
        val adapter = RecognizerAdapter<String>(engine = { throw IllegalStateException("模型缺失: det") })
        val result = adapter.recognize("img")
        assertTrue(result.isFailure)
        assertEquals("模型缺失: det", result.exceptionOrNull()?.message)
    }

    // ---------- 慢设备标记 ----------

    @Test
    fun `超阈值-触发慢设备回调`() = runTest {
        val slow = mutableListOf<Long>()
        val adapter = RecognizerAdapter<String>(
            engine = fakeEngine("x", latencyMs = 1500L),
            slowThresholdMs = 1000L,
            onSlow = { slow += it },
        )
        adapter.recognize("img")
        assertEquals(listOf(1500L), slow)
    }

    @Test
    fun `未超阈值-不触发回调`() = runTest {
        val slow = mutableListOf<Long>()
        val adapter = RecognizerAdapter<String>(
            engine = fakeEngine("x", latencyMs = 500L),
            slowThresholdMs = 1000L,
            onSlow = { slow += it },
        )
        adapter.recognize("img")
        assertTrue(slow.isEmpty())
    }

    @Test
    fun `默认阈值-5秒`() {
        assertEquals(5_000L, SLOW_DEVICE_THRESHOLD_MS)
    }

    // ---------- 队列接入（T07 端到端） ----------

    @Test
    fun `经识别队列-文本md写入带标头-ocr回写done`() = runTest {
        val root = tmp.newFolder("library")
        val index = LibraryIndex()
        val store = StoreService(root, index, FakeImageEncoder())
        val clock = { "2026-08-16T00:00:00Z" }
        val img = FakeImageAsset(100, 100)

        // 引擎 → 适配器 → 队列（模拟 PaddleOcrRecognizer 的装配）
        val adapter = RecognizerAdapter<String>(engine = fakeEngine("已知抛物线 y=x²", latencyMs = 800L))
        val queue = RecognitionQueue(store, index, adapter, this, clock)

        val id = java.util.UUID.randomUUID().toString()
        store.create(makeMeta(id = id), img, text = null).getOrThrow()
        queue.enqueue(RecognitionQueue.JobSpec(id, "enhanced:1"))

        awaitPhase(queue, id, RecognitionPhase.DONE)

        val entryDir = File(File(root, "数学"), index.get(id)!!.dirName)
        assertEquals("## 题干\n已知抛物线 y=x²:enhanced:1", File(entryDir, "文本.md").readText())
        val meta = store.readMetaById(id).getOrThrow()
        assertEquals("done", meta.ocr.status)
        assertEquals("2026-08-16T00:00:00Z", meta.ocr.at)
        queue.stop()
    }

    private fun TestScope.awaitPhase(queue: RecognitionQueue<String>, id: String, phase: RecognitionPhase) {
        val deadline = System.currentTimeMillis() + 5000
        while (queue.status.value[id]?.phase != phase) {
            if (System.currentTimeMillis() > deadline) fail("状态未流转到 $phase：${queue.status.value}")
            advanceUntilIdle()
            Thread.sleep(5)
        }
    }
}
