package com.misoup.app.recognition.queue

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.Filter
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.recognition.Recognizer
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * T09 识别队列：状态流转 pending→done/failed、成功后文本+倒排+ocr 回写、
 * 失败标记 + 重试、单线程串行（防模型并发）
 */
class RecognitionQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService

    private val img = FakeImageAsset(100, 100)
    private val clock = { "2026-08-16T00:00:00Z" }

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        index = LibraryIndex()
        store = StoreService(root, index, FakeImageEncoder())
    }

    /** 造题落库（ocr=pending），返回 meta.id */
    private suspend fun createCard(id: String = java.util.UUID.randomUUID().toString()): String {
        store.create(makeMeta(id = id), img, text = null).getOrThrow()
        return id
    }

    /** 等待状态流转到目标阶段（worker 在 this 上消费，轮询真实时间兜底） */
    private fun TestScope.awaitPhase(
        queue: RecognitionQueue<String>,
        id: String,
        phase: RecognitionPhase
    ) {
        val deadline = System.currentTimeMillis() + 5000
        while (queue.status.value[id]?.phase != phase) {
            if (System.currentTimeMillis() > deadline) {
                fail("状态未流转到 $phase：${queue.status.value}")
            }
            advanceUntilIdle()
            Thread.sleep(5)
        }
    }

    // ---------- 状态流转：pending → done ----------

    @Test
    fun `识别成功-状态流转 pending到done-文本落盘+倒排+ocr回写`() = runTest {
        val recognizer = Recognizer<String> { Result.success("二次函数在区间[1,3]上的最值") }
        val queue = RecognitionQueue(store, index, recognizer, this, clock)

        val id = createCard()
        queue.enqueue(RecognitionQueue.JobSpec(id, "enhanced:1"))
        // 入队即 pending（enqueue 自动启动 worker）
        assertEquals(RecognitionPhase.PENDING, queue.status.value[id]?.phase)
        awaitPhase(queue, id, RecognitionPhase.DONE)

        assertEquals(RecognitionPhase.DONE, queue.status.value[id]?.phase)

        // 文本.md 落盘
        val entryDir = File(File(root, "数学"), index.get(id)!!.dirName)
        assertEquals("二次函数在区间[1,3]上的最值", File(entryDir, "文本.md").readText())

        // 倒排刷新：关键词可搜到
        assertEquals(listOf(id), index.query(Filter(), "最值").map { it.id })

        // meta.ocr.status = done + at 时间戳
        val meta = store.readMetaById(id).getOrThrow()
        assertEquals("done", meta.ocr.status)
        assertEquals("2026-08-16T00:00:00Z", meta.ocr.at)

        // 成功后失败缓存已清除（重试无效果）
        queue.retry(id)
        awaitPhase(queue, id, RecognitionPhase.DONE)
        assertEquals("二次函数在区间[1,3]上的最值", store.readTextById(id).getOrThrow())
        queue.stop()
    }

    // ---------- 状态流转：pending → failed ----------

    @Test
    fun `识别失败-状态流转 pending到failed-仅标记不丢数据`() = runTest {
        val recognizer = Recognizer<String> {
            Result.failure(IllegalStateException("网络不可用"))
        }
        val queue = RecognitionQueue(store, index, recognizer, this, clock)

        val id = createCard()
        queue.enqueue(RecognitionQueue.JobSpec(id, "enhanced:1"))
        awaitPhase(queue, id, RecognitionPhase.FAILED)

        // 失败原因透传（进度页/极客排障）
        assertEquals("网络不可用", queue.status.value[id]?.error)

        // 录入数据不动：meta 仍在、ocr=failed、无文本文件
        val meta = store.readMetaById(id).getOrThrow()
        assertEquals("failed", meta.ocr.status)
        assertEquals("", store.readTextById(id).getOrThrow())
        // 倒排无文本 token（标签仍可检索，属正常；断言文本关键词不可搜）
        assertEquals(emptyList<String>(), index.query(Filter(), "题干").map { it.id })
        queue.stop()
    }

    // ---------- 重试 ----------

    @Test
    fun `重试-失败后重新入队-状态回到pending-成功后done`() = runTest {
        var attempts = AtomicInteger(0)
        val recognizer = Recognizer<String> { input ->
            if (attempts.incrementAndGet() == 1) {
                Result.failure(IllegalStateException("首次失败"))
            } else {
                Result.success("重试成功文本")
            }
        }
        val queue = RecognitionQueue(store, index, recognizer, this, clock)

        val id = createCard()
        queue.enqueue(RecognitionQueue.JobSpec(id, "enhanced:1"))
        awaitPhase(queue, id, RecognitionPhase.FAILED)

        // 重试 → 状态回 pending → 最终 done
        queue.retry(id)
        assertEquals(RecognitionPhase.PENDING, queue.status.value[id]?.phase)
        awaitPhase(queue, id, RecognitionPhase.DONE)

        // 文本与 ocr 均已回写
        assertEquals("重试成功文本", store.readTextById(id).getOrThrow())
        assertEquals("done", store.readMetaById(id).getOrThrow().ocr.status)
        queue.stop()
    }

    @Test
    fun `重试-无失败缓存的条目-静默无操作不崩`() = runTest {
        val queue = RecognitionQueue(store, index, Recognizer<String> { Result.success("x") }, this, clock)
        queue.retry("不存在的id")   // 无缓存 → 无操作
        advanceUntilIdle()
        assertTrue(queue.status.value.isEmpty())
        queue.stop()
    }

    // ---------- 串行性 ----------

    @Test
    fun `单线程worker-多个任务串行消费-并发数不超1`() = runTest {
        val active = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val recognizer = Recognizer<String> { input ->
            val now = active.incrementAndGet()
            maxConcurrent.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            Thread.sleep(20)   // 拉长处理窗口，暴露并发问题
            active.decrementAndGet()
            Result.success("文本$input")
        }
        val queue = RecognitionQueue(store, index, recognizer, this, clock)

        val ids = (1..5).map { createCard() }
        ids.forEach { queue.enqueue(RecognitionQueue.JobSpec(it, "bmp$it")) }
        ids.forEach { awaitPhase(queue, it, RecognitionPhase.DONE) }

        assertEquals(1, maxConcurrent.get())
        ids.forEach { id ->
            assertNotNull(queue.status.value[id])
            assertEquals("文本bmp$id", store.readTextById(id).getOrThrow())
        }
        queue.stop()
    }

    // ---------- worker 韧性 ----------

    @Test
    fun `识别器抛异常-单条转failed-不杀worker-后续任务正常`() = runTest {
        val calls = AtomicInteger(0)
        val recognizer = Recognizer<String> {
            if (calls.incrementAndGet() == 1) throw RuntimeException("识别器崩溃")
            Result.success("第二题成功")
        }
        val queue = RecognitionQueue(store, index, recognizer, this, clock)

        val id1 = createCard()
        val id2 = createCard()
        queue.enqueue(RecognitionQueue.JobSpec(id1, "bmp1"))
        queue.enqueue(RecognitionQueue.JobSpec(id2, "bmp2"))

        awaitPhase(queue, id1, RecognitionPhase.FAILED)
        awaitPhase(queue, id2, RecognitionPhase.DONE)

        assertEquals("第二题成功", store.readTextById(id2).getOrThrow())
        assertEquals("failed", store.readMetaById(id1).getOrThrow().ocr.status)
        queue.stop()
    }

    @Test
    fun `回写异常-不杀worker-后续任务正常`() = runTest {
        val recognizer = Recognizer<String> { Result.success("文本") }
        val queue = RecognitionQueue(store, index, recognizer, this, clock)

        // 第一题：落库后立即删除（回写 updateText 抛异常）
        val deadId = createCard()
        store.delete(deadId).getOrThrow()
        val liveId = createCard()
        queue.enqueue(RecognitionQueue.JobSpec(deadId, "bmp1"))
        queue.enqueue(RecognitionQueue.JobSpec(liveId, "bmp2"))

        // 死题回写失败（状态停留 pending，worker 不崩），活题正常 done
        awaitPhase(queue, liveId, RecognitionPhase.DONE)
        assertEquals("文本", store.readTextById(liveId).getOrThrow())
        queue.stop()
    }

    @Test
    fun `start幂等-重复调用不重建worker`() = runTest {
        val calls = AtomicInteger(0)
        val recognizer = Recognizer<String> {
            calls.incrementAndGet()
            Result.success("ok")
        }
        val queue = RecognitionQueue(store, index, recognizer, this, clock)
        queue.start()
        queue.start()
        queue.start()

        val id = createCard()
        queue.enqueue(RecognitionQueue.JobSpec(id, "bmp"))
        awaitPhase(queue, id, RecognitionPhase.DONE)
        advanceUntilIdle()
        assertEquals(1, calls.get())
        queue.stop()
    }
}
