package com.misoup.app.ui.screens.review

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.FsrsState
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.review.fsrs.FsrsTime
import com.misoup.app.review.queue.QueueLoader
import com.misoup.app.review.scheduler.ReviewSubmitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 复习 tab 总览 ViewModel 测试（T07/T09）：
 * 今日待复习数量 / 统计卡片数据 / 复习后刷新口径一致
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService
    private val img = FakeImageAsset(100, 100)

    private val NOW = 1_000_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        root = tmp.newFolder("library")
        index = LibraryIndex()
        store = StoreService(root, index, FakeImageEncoder())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = ReviewViewModel(QueueLoader(store, index), store, index)

    private suspend fun createCard(id: String, fsrs: FsrsState = FsrsState(), kps: List<String> = listOf("二次函数")) {
        store.create(makeMeta(id = id, subject = "数学", knowledgePoints = kps, fsrs = fsrs), img, "题干$id").getOrThrow()
    }

    @Test
    fun `今日待复习数量-与队列口径一致`() = runTest {
        createCard("new1")                                        // 新卡 → 待复习
        createCard("due1", FsrsState(state = 2, due = FsrsTime.toIso(NOW - 86_400_000L), stability = 2.0))  // 逾期
        createCard("future", FsrsState(state = 2, due = FsrsTime.toIso(NOW + 86_400_000L), stability = 2.0)) // 未来
        val v = vm()
        v.refresh(NOW)
        advanceUntilIdle()
        val s = v.uiState.value
        assertEquals(2, s.todayCount)
        assertEquals(2, s.queue.size)
        assertNotNull(s.stats)
        assertEquals(3, s.stats!!.total)
    }

    @Test
    fun `复习提交后刷新-数量与due变化一致`() = runTest {
        createCard("a")
        createCard("b")
        val v = vm()
        v.refresh(NOW)
        advanceUntilIdle()
        assertEquals(2, v.uiState.value.todayCount)

        // 复习一张 Good → due 移到未来
        ReviewSubmitter(store).submit("a", 3, now = NOW).getOrThrow()
        v.refresh(NOW)
        advanceUntilIdle()
        assertEquals(1, v.uiState.value.todayCount)
        assertEquals("b", v.uiState.value.queue.single().id)
        // 正确率：1 条 Good → 100%
        assertEquals(1.0, v.uiState.value.stats!!.accuracy, 1e-9)
    }

    @Test
    fun `空库-横幅为0统计为空`() = runTest {
        val v = vm()
        v.refresh(NOW)
        advanceUntilIdle()
        val s = v.uiState.value
        assertEquals(0, s.todayCount)
        assertEquals(0, s.stats!!.total)
        assertTrue(s.queue.isEmpty())
        assertTrue(s.weakKps.isEmpty())
    }

    @Test
    fun `薄弱知识点-掌握度升序top5（T06）`() = runTest {
        // 二次函数：2 次复习 1 对 → 0.5；三角函数：2 次全对 → 1.0；数列：未复习 → 0.5
        val r = com.misoup.app.data.model.Review("2026-08-16T10:00:00+08:00", 0)
        val g = com.misoup.app.data.model.Review("2026-08-16T10:00:00+08:00", 2)
        store.create(
            makeMeta(id = "a", subject = "数学", knowledgePoints = listOf("二次函数"), reviews = listOf(r, g)),
            img, "题干a"
        ).getOrThrow()
        store.create(
            makeMeta(id = "b", subject = "数学", knowledgePoints = listOf("三角函数"), reviews = listOf(g, g)),
            img, "题干b"
        ).getOrThrow()
        store.create(
            makeMeta(id = "c", subject = "数学", knowledgePoints = listOf("数列")),
            img, "题干c"
        ).getOrThrow()

        val v = vm()
        v.refresh(NOW)
        advanceUntilIdle()

        val weak = v.uiState.value.weakKps
        // 升序：0.5(数列, 复习0) → 0.5(二次函数, 复习2) → 1.0(三角函数)；复习少者同分排前
        assertEquals(listOf("数列", "二次函数", "三角函数"), weak.map { it.knowledgePoint })
        assertEquals(listOf("c"), weak[0].entryIds)
        assertEquals(listOf("a"), weak[1].entryIds)
        assertEquals(listOf("b"), weak[2].entryIds)
    }
}
