package com.misoup.app.ui.library

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 详情 VM 测试（T05/T06/T08/T10）：真实 StoreService + 临时目录 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryDetailViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        index = LibraryIndex()
        store = StoreService(tmp.newFolder("library"), index, FakeImageEncoder())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun createEntry(
        id: String = "uuid-1",
        text: String = "## 题干\n求最值\n\n## 答案\n0\n\n## 解析\n配方"
    ): String {
        val meta = makeMeta(id = id, subject = "数学")
        store.create(meta, FakeImageAsset(100, 200), text).getOrThrow()
        return id
    }

    private fun vm(retry: OcrRetryHandler? = null) = LibraryDetailViewModel(index, store, retry)

    @Test
    fun `加载-索引条目与 meta 与分段文本齐备`() = runTest {
        val id = createEntry()
        val vm = vm()
        vm.load(id)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(id, s.id)
        assertNotNull(s.entry)
        assertEquals("数学", s.meta?.subject)
        assertEquals("求最值", s.text?.question)
        assertEquals("0", s.text?.answer)
        assertEquals("配方", s.text?.explanation)
        // 原图路径存在（create 写了原图.webp）
        assertNotNull(s.imagePath)
    }

    @Test
    fun `加载-不存在的 id-条目为空不崩溃`() = runTest {
        val vm = vm()
        vm.load("nope")
        advanceUntilIdle()
        val s = vm.state.value
        assertNull(s.entry)
        assertNull(s.meta)
    }

    @Test
    fun `遮罩开关-打开关闭与揭开状态`() = runTest {
        val id = createEntry()
        val vm = vm()
        vm.load(id)
        advanceUntilIdle()

        assertFalse(vm.state.value.mask.hidden)
        vm.toggleMask()
        assertTrue(vm.state.value.mask.hidden)
        assertTrue(vm.state.value.mask.maskVisible)

        vm.peek()
        assertTrue(vm.state.value.peeking)
        vm.setRevealProgress(1f)
        assertFalse(vm.state.value.mask.maskVisible)   // 完全揭开不挡内容
        vm.onRevealFinished()
        assertFalse(vm.state.value.peeking)

        vm.toggleMask()   // 关闭
        assertFalse(vm.state.value.mask.hidden)
    }

    @Test
    fun `删除-双确认后文件与索引清除`() = runTest {
        val id = createEntry()
        assertEquals(1, index.size())
        val vm = vm()
        vm.load(id)
        advanceUntilIdle()

        // 第一次确认
        vm.onDeleteEvent(DeleteEvent.Request)
        assertEquals(DeleteConfirm.FirstConfirm, vm.state.value.deleteConfirm)
        // 取消 → 回到 Idle，不删
        vm.onDeleteEvent(DeleteEvent.Cancel)
        assertEquals(DeleteConfirm.Idle, vm.state.value.deleteConfirm)
        assertEquals(1, index.size())

        // 完整双确认
        vm.onDeleteEvent(DeleteEvent.Request)
        vm.onDeleteEvent(DeleteEvent.Confirm)
        assertEquals(DeleteConfirm.SecondConfirm, vm.state.value.deleteConfirm)
        vm.onDeleteEvent(DeleteEvent.Confirm)
        advanceUntilIdle()
        assertEquals(DeleteConfirm.Done, vm.state.value.deleteConfirm)
        // 删除：目录与文件已清（科目目录保留，条目目录必须清除）
        assertEquals(0, index.size())
        val subjectDir = File(tmp.root, "library/数学")
        val entryDirs = subjectDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
        assertTrue("条目目录应被清除", entryDirs.isEmpty())
    }

    @Test
    fun `删除失败-进入 Error 可复位`() = runTest {
        val id = createEntry()
        val vm = vm()
        vm.load(id)
        advanceUntilIdle()
        // 让删除失败：先手动把索引删掉，store.delete 会抛"条目不存在"
        index.remove(id)
        vm.onDeleteEvent(DeleteEvent.Request)
        vm.onDeleteEvent(DeleteEvent.Confirm)
        vm.onDeleteEvent(DeleteEvent.Confirm)
        advanceUntilIdle()
        assertTrue(vm.state.value.deleteConfirm is DeleteConfirm.Error)
        vm.dismissDeleteError()
        assertEquals(DeleteConfirm.Idle, vm.state.value.deleteConfirm)
    }

    @Test
    fun `移动-科目变更且索引同步`() = runTest {
        val id = createEntry()
        val vm = vm()
        vm.load(id)
        advanceUntilIdle()

        vm.moveTo("英语")
        advanceUntilIdle()
        assertEquals("英语", index.get(id)?.subject)
        // 目录迁移：library/英语/<dirName>
        val dirName = index.get(id)?.dirName
        assertTrue(File(File(tmp.root, "library/英语"), dirName!!).isDirectory)
        // meta.json 内 subject 同步
        assertEquals("英语", store.readMetaById(id).getOrThrow().subject)
    }

    @Test
    fun `识别重试-回调带 id`() = runTest {
        val id = createEntry()
        val retried = mutableListOf<String>()
        val vm = vm(retry = OcrRetryHandler { retried += it })
        vm.load(id)
        advanceUntilIdle()
        vm.retryOcr()
        assertEquals(listOf(id), retried)
    }

    @Test
    fun `识别状态-按 ocr 状态映射`() = runTest {
        val id = createEntry()
        val vm = vm()
        vm.load(id)
        advanceUntilIdle()
        // create 默认 ocr.status = pending
        assertEquals(OcrStatusUi.Pending, vm.state.value.ocrStatus)
    }
}
