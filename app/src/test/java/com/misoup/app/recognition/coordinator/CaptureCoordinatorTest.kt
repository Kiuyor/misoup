package com.misoup.app.recognition.coordinator

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.Filter
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.store.ImageAsset
import com.misoup.app.data.store.ImageFormat
import com.misoup.app.data.store.StoreService
import com.misoup.app.pipeline.PreprocessResult
import com.misoup.app.recognition.Recognizer
import com.misoup.app.recognition.queue.RecognitionPhase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T08 CaptureCoordinator：逐张 加载→预处理→落库(pending)→入队、
 * 进度聚合（ok/failed）、Uri 失败降级不阻塞整批、meta 字段约定
 */
class CaptureCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService

    private val clock = { "2026-08-16T00:00:00Z" }

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        index = LibraryIndex()
        store = StoreService(root, index, FakeImageEncoder())
    }

    /** 构造协调器：T=String，loader/preprocess/assetFactory 全桩，识别器成功返回题干 */
    private fun TestScope.buildCoordinator(
        loader: CaptureImageLoader<String> = CaptureImageLoader { source ->
            if (source.uri.startsWith("bad:")) null else "bmp:${source.uri}"
        },
        preprocess: PreprocessRunner<String> = PreprocessRunner { input ->
            // 桩：矫正/画质/压缩原样返回，enhanced 标记来源（验证入队的是增强图）
            PreprocessResult(
                rectified = input,
                enhanced = "enhanced:${input.substringAfter("bmp:")}",
                webpBytes = ByteArray(0),
                usedFallback = listOf("rectify_fallback")
            )
        },
        recognizer: Recognizer<String> = Recognizer { Result.success("识别出的题干") }
    ): CaptureCoordinator<String> = CaptureCoordinator(
        store = store,
        index = index,
        recognizer = recognizer,
        loader = loader,
        preprocess = preprocess,
        scope = this,
        // 测试注入测试调度器：withContext 不切真实线程，保持 runTest 时序可控
        ioDispatcher = coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher,
        assetFactory = { _, format -> FakeImageAsset(10, 10, format) },
        clock = clock
    )

    // ---------- 进度聚合 ----------

    @Test
    fun `三张全部成功-进度逐步emit-ok递增-失败列表空`() = runTest {
        val coordinator = buildCoordinator()
        val sources = listOf(
            CaptureSource("content://a/1"),
            CaptureSource("content://a/2"),
            CaptureSource("content://a/3")
        )

        val progresses = coordinator.submitSources(sources).toList()

        // 每张完成后 emit 一次：ok 1→2→3
        assertEquals(3, progresses.size)
        assertEquals(CaptureProgress(1, emptyList()), progresses[0])
        assertEquals(CaptureProgress(2, emptyList()), progresses[1])
        assertEquals(CaptureProgress(3, emptyList()), progresses[2])

        // 批次状态：全部 STORED
        assertEquals(3, coordinator.batch.value.size)
        assertTrue(coordinator.batch.value.all { it.storePhase == StorePhase.STORED })

        // 落库 3 条：ocr=pending + 默认科目 + 原图文件名约定
        assertEquals(3, index.size())
        index.all().forEach { entry ->
            assertEquals(StorePhase.STORED, coordinator.batch.value.first { it.metaId == entry.id }.storePhase)
            val meta = store.readMetaById(entry.id).getOrThrow()
            assertEquals("pending", meta.ocr.status)
            assertEquals(CaptureDefaults.DEFAULT_SUBJECT, meta.subject)
            assertEquals(MisoupConfigConst.ORIGINAL_IMAGE_NAME, meta.images.original)
            assertEquals(MisoupConfigConst.TEXT_FILE_NAME, meta.textFile)
            assertNotNull(meta.id)
        }
        // 磁盘：root/未分类/<目录>/meta.json + 原图.webp
        val subjectDir = File(root, CaptureDefaults.DEFAULT_SUBJECT)
        assertEquals(3, subjectDir.listFiles()!!.count { it.isDirectory })
    
        coordinator.stop()
    }

    @Test
    fun `单张失败降级-进度ok与failed正确-不阻塞其余`() = runTest {
        val coordinator = buildCoordinator()
        val sources = listOf(
            CaptureSource("content://a/1"),
            CaptureSource("bad://broken"),        // loader 返回 null → 收录失败
            CaptureSource("content://a/3")
        )

        val progresses = coordinator.submitSources(sources).toList()

        // 第 1 张成功 → 第 2 张失败 → 第 3 张成功
        assertEquals(3, progresses.size)
        assertEquals(CaptureProgress(1, emptyList()), progresses[0])
        assertEquals(CaptureProgress(1, listOf("bad://broken")), progresses[1])
        assertEquals(CaptureProgress(2, listOf("bad://broken")), progresses[2])

        // 批次状态：第 2 张 FAILED
        val batch = coordinator.batch.value
        assertEquals(StorePhase.FAILED, batch[1].storePhase)
        assertEquals(StorePhase.STORED, batch[0].storePhase)
        assertEquals(StorePhase.STORED, batch[2].storePhase)

        // 仍落库 2 条（失败的没入队）
        assertEquals(2, index.size())
    
        coordinator.stop()
    }

    @Test
    fun `预处理抛异常-单张视为失败-不崩`() = runTest {
        val coordinator = buildCoordinator(
            preprocess = PreprocessRunner<String> { throw RuntimeException("预处理崩溃") }
        )
        val sources = listOf(CaptureSource("content://a/1"))

        val progresses = coordinator.submitSources(sources).toList()

        assertEquals(listOf(CaptureProgress(0, listOf("content://a/1"))), progresses)
        assertEquals(0, index.size())
        assertEquals(StorePhase.FAILED, coordinator.batch.value[0].storePhase)
    
        coordinator.stop()
    }

    // ---------- 识别入队 ----------

    @Test
    fun `落库后入队-队列识别成功-ocr流转done-文本为识别结果`() = runTest {
        var recognizedInputs = mutableListOf<String>()
        val coordinator = buildCoordinator(
            recognizer = Recognizer<String> { input ->
                recognizedInputs += input
                Result.success("题干：${input.substringAfter("enhanced:")}")
            }
        )

        coordinator.submitSources(listOf(CaptureSource("content://a/1"))).toList()

        // worker 消费 → 识别成功 → ocr done + 文本落盘 + 倒排
        advanceUntilIdle()

        // 入队的是预处理后的 enhanced 图（不是原图）
        assertEquals(listOf("enhanced:content://a/1"), recognizedInputs)
        val id = coordinator.batch.value.first().metaId!!
        val deadline = System.currentTimeMillis() + 5000
        while (coordinator.recognitionStatus.value[id]?.phase != RecognitionPhase.DONE) {
            if (System.currentTimeMillis() > deadline) fail("识别未完成")
            advanceUntilIdle()
            Thread.sleep(5)
        }
        val meta = store.readMetaById(id).getOrThrow()
        assertEquals("done", meta.ocr.status)
        assertEquals("题干：content://a/1", store.readTextById(id).getOrThrow())
        assertEquals(listOf(id), index.query(Filter(), "题干").map { it.id })
    
        coordinator.stop()
    }

    // ---------- retry 透传 ----------

    @Test
    fun `retry透传队列-失败条目重试后done`() = runTest {
        var attempts = 0
        val coordinator = buildCoordinator(
            recognizer = Recognizer<String> {
                attempts++
                if (attempts == 1) Result.failure(IllegalStateException("首次失败"))
                else Result.success("重试成功")
            }
        )

        coordinator.submitSources(listOf(CaptureSource("content://a/1"))).toList()
        val id = coordinator.batch.value.first().metaId!!
        advanceUntilIdle()
        val deadline = System.currentTimeMillis() + 5000
        while (coordinator.recognitionStatus.value[id]?.phase != RecognitionPhase.FAILED) {
            if (System.currentTimeMillis() > deadline) fail("首次识别未失败")
            advanceUntilIdle()
            Thread.sleep(5)
        }

        coordinator.retry(id)
        while (coordinator.recognitionStatus.value[id]?.phase != RecognitionPhase.DONE) {
            if (System.currentTimeMillis() > deadline) fail("重试未成功")
            advanceUntilIdle()
            Thread.sleep(5)
        }
        assertEquals("重试成功", store.readTextById(id).getOrThrow())
    
        coordinator.stop()
    }

    @Test
    fun `空批次-进度流直接完成-无落库`() = runTest {
        val coordinator = buildCoordinator()
        val progresses = coordinator.submitSources(emptyList()).toList()
        assertTrue(progresses.isEmpty())
        assertEquals(0, index.size())
    
        coordinator.stop()
    }
}
