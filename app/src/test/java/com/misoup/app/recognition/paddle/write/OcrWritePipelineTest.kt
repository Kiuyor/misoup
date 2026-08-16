package com.misoup.app.recognition.paddle.write

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.recognition.paddle.model.PaddleModels
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** T06 写入管线：文本.md 临时段（整段题干）+ meta.ocr{engine:paddle,status}，失败→failed */
class OcrWritePipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = "2026-08-16T00:00:00Z"
    private val img = FakeImageAsset(100, 100)

    // ---------- OcrTextFormat ----------

    @Test
    fun `文本格式化-整段题干标头`() {
        assertEquals("## 题干\n已知二次函数 y=x²", OcrTextFormat.content("已知二次函数 y=x²"))
        assertEquals("## 题干\na\nb", OcrTextFormat.content("a\nb"))
        assertEquals("", OcrTextFormat.content(""))
        assertEquals("", OcrTextFormat.content("   "))
    }

    // ---------- OcrStateLogic ----------

    @Test
    fun `成功状态-engine paddle done model标签 at时间`() {
        val s = OcrStateLogic.onSuccess(now)
        assertEquals("paddle", s.engine)
        assertEquals("done", s.status)
        assertEquals(PaddleModels.MODEL_TAG, s.model)
        assertEquals(now, s.at)
    }

    @Test
    fun `失败状态-failed可重试`() {
        val s = OcrStateLogic.onFailure(now)
        assertEquals("failed", s.status)
        assertEquals(now, s.at)
        assertTrue(OcrStateLogic.retryable("模型缺失"))
        assertTrue(OcrStateLogic.retryable(null))
        assertFalse(OcrStateLogic.retryable("字典配置永久错误"))   // 永久配置类失败
    }

    // ---------- 写回管线（真实 StoreService + mock 引擎） ----------

    private suspend fun createCard(store: StoreService): String {
        val id = java.util.UUID.randomUUID().toString()
        store.create(makeMeta(id = id), img, text = null).getOrThrow()
        return id
    }

    @Test
    fun `识别成功-文本md整段题干+ocr done+model`() = runTest {
        val root = tmp.newFolder("library")
        val index = LibraryIndex()
        val store = StoreService(root, index, FakeImageEncoder())
        val pipeline = OcrWritePipeline(store)

        val id = createCard(store)
        pipeline.writeSuccess(id, "求抛物线顶点坐标", now).getOrThrow()

        // 文本.md：整段题干（未做结构拆分）
        val entryDir = File(File(root, "数学"), index.get(id)!!.dirName)
        assertEquals("## 题干\n求抛物线顶点坐标", File(entryDir, "文本.md").readText())

        // meta.ocr：engine=paddle, status=done, model=版本标签, at=时间戳
        val meta = store.readMetaById(id).getOrThrow()
        assertEquals("paddle", meta.ocr.engine)
        assertEquals("done", meta.ocr.status)
        assertEquals(PaddleModels.MODEL_TAG, meta.ocr.model)
        assertEquals(now, meta.ocr.at)
    }

    @Test
    fun `识别成功-空文本不写文件-状态仍done`() = runTest {
        val root = tmp.newFolder("library")
        val store = StoreService(root, LibraryIndex(), FakeImageEncoder())
        val pipeline = OcrWritePipeline(store)

        val id = createCard(store)
        pipeline.writeSuccess(id, "  ", now).getOrThrow()
        assertEquals("", store.readTextById(id).getOrThrow())
        assertEquals("done", store.readMetaById(id).getOrThrow().ocr.status)
    }

    @Test
    fun `识别失败-仅标记failed-文本不动-可重试`() = runTest {
        val root = tmp.newFolder("library")
        val index = LibraryIndex()
        val store = StoreService(root, index, FakeImageEncoder())
        val pipeline = OcrWritePipeline(store)

        val id = createCard(store)
        // 先成功一次写入文本
        pipeline.writeSuccess(id, "题干", now).getOrThrow()
        // 再失败（重试场景）
        pipeline.writeFailed(id, now).getOrThrow()

        val meta = store.readMetaById(id).getOrThrow()
        assertEquals("failed", meta.ocr.status)
        assertEquals(now, meta.ocr.at)
        assertEquals("## 题干\n题干", store.readTextById(id).getOrThrow())   // 文本保留
    }
}
