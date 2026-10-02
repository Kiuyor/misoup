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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 重做卡片页 ViewModel 测试（T04/T05/T06 UI 逻辑）：
 * 队列加载/逐张推进/显示答案/框选定稿/提交失败不推进/完成态
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewCardViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService
    private val img = FakeImageAsset(100, 100)

    private val NOW = 1_000_000_000_000L
    private var clock = NOW

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        root = tmp.newFolder("library")
        index = LibraryIndex()
        store = StoreService(root, index, FakeImageEncoder())
        clock = NOW
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(): ReviewCardViewModel = ReviewCardViewModel(
        queueLoader = QueueLoader(store, index),
        submitter = ReviewSubmitter(store),
        store = store,
        maskStore = com.misoup.app.review.mask.MaskStore(root, index),
        // 内存版 FSRS 参数存储（JVM 单测无 Android；返回默认参数）
        fsrsSettings = object : com.misoup.app.settings.review.FsrsSettingsStore {
            override fun load() = com.misoup.app.review.fsrs.Fsrs.Params()
            override fun save(params: com.misoup.app.review.fsrs.Fsrs.Params) {}
        },
        nowProvider = { clock }
    )

    private suspend fun createCard(id: String, text: String, fsrs: FsrsState = FsrsState()) {
        store.create(makeMeta(id = id, subject = "数学", fsrs = fsrs), img, text).getOrThrow()
    }

    private fun dueFsrs(dueDaysAgo: Long): FsrsState = FsrsState(
        state = 2, due = FsrsTime.toIso(NOW - dueDaysAgo * 86_400_000L),
        stability = 3.0, difficulty = 5.0, reps = 2, lastReview = FsrsTime.toIso(NOW - 3 * 86_400_000L)
    )

    // ---------- 队列加载与逐张推进 ----------

    @Test
    fun `加载-队列非空时定位第一张并载入题干与遮罩默认整图`() = runTest {
        createCard("a", "## 题干\n第一题题干\n\n## 答案\nA\n\n## 解析\nE", dueFsrs(2))
        createCard("b", "## 题干\n第二题题干", dueFsrs(1))
        val v = vm()
        v.load()
        advanceUntilIdle()

        val s = v.uiState.value
        assertEquals(2, s.queue.size)
        assertEquals("a", s.current?.id)
        assertEquals("第一题题干", s.text.question)
        assertEquals("A", s.text.answer)
        assertTrue(!s.revealed)
        assertTrue(s.mask.effective().isFull())   // 默认整图遮罩
        assertEquals("1/2", s.queueProgress)
    }

    @Test
    fun `提交Good-推进到下一张并落盘复习记录`() = runTest {
        createCard("a", "题干A", dueFsrs(2))
        createCard("b", "题干B", dueFsrs(1))
        val v = vm()
        v.load()
        advanceUntilIdle()

        v.reveal()
        v.submitGrade(3)   // Good
        advanceUntilIdle()

        val s = v.uiState.value
        assertEquals(1, s.currentIndex)
        assertEquals("b", s.current?.id)
        assertEquals("题干B", s.text.question)
        // 落盘
        val meta = store.readMetaById("a").getOrThrow()
        assertEquals(1, meta.reviews.size)
        assertEquals(2, meta.reviews[0].rating)   // grade 3 → rating 2
    }

    @Test
    fun `全部提交完-进入完成态`() = runTest {
        createCard("a", "题干A", dueFsrs(1))
        val v = vm()
        v.load()
        advanceUntilIdle()

        v.reveal()
        v.submitGrade(4)   // Easy
        advanceUntilIdle()

        assertTrue(v.uiState.value.done)
        assertEquals(1, store.readMetaById("a").getOrThrow().reviews.size)
    }

    @Test
    fun `空队列-直接完成态`() = runTest {
        val v = vm()
        v.load()
        advanceUntilIdle()
        assertTrue(v.uiState.value.done)
        assertTrue(v.uiState.value.queue.isEmpty())
    }

    @Test
    fun `提交中重复点击-不重复提交`() = runTest {
        createCard("a", "题干A", dueFsrs(1))
        val v = vm()
        v.load()
        advanceUntilIdle()
        v.reveal()
        v.submitGrade(3)
        v.submitGrade(3)   // submitting=true 期间应被忽略
        advanceUntilIdle()
        assertEquals(1, store.readMetaById("a").getOrThrow().reviews.size)
    }

    @Test
    fun `提交失败-停在当前卡并报错`() = runTest {
        createCard("a", "题干A", dueFsrs(2))
        createCard("b", "题干B", dueFsrs(1))
        val v = vm()
        v.load()
        advanceUntilIdle()
        v.reveal()
        // 人为破坏 a 的 meta → 提交失败
        val entry = index.get("a")!!
        File(File(File(root, "数学"), entry.dirName), "meta.json").writeText("{bad")
        index.rebuild(root)
        v.submitGrade(3)
        advanceUntilIdle()

        val s = v.uiState.value
        assertEquals(0, s.currentIndex)               // 不推进
        assertEquals("a", s.current?.id)
        assertNotNull(s.error)
        assertTrue(!s.submitting)
    }

    // ---------- 显示答案（T04） ----------

    @Test
    fun `显示答案-置revealed且动画进度钳制`() = runTest {
        createCard("a", "题干A", dueFsrs(1))
        val v = vm()
        v.load()
        advanceUntilIdle()
        assertTrue(!v.uiState.value.revealed)
        v.reveal()
        assertTrue(v.uiState.value.revealed)
        v.onRevealProgress(1.5f)
        assertEquals(1f, v.uiState.value.revealProgress, 1e-6f)
        v.onRevealProgress(-1f)
        assertEquals(0f, v.uiState.value.revealProgress, 1e-6f)
    }

    // ---------- 手动框选（T05 UI 逻辑） ----------

    @Test
    fun `框选-进入更新定稿并持久化`() = runTest {
        createCard("a", "题干A", dueFsrs(1))
        val v = vm()
        v.load()
        advanceUntilIdle()

        v.enterSelectMode()
        assertTrue(v.uiState.value.mask.selecting)
        v.updateSelection(com.misoup.app.review.mask.MaskRect(0.2f, 0.1f, 0.8f, 0.6f))
        v.commitSelection()
        advanceUntilIdle()

        val s = v.uiState.value
        assertTrue(!s.mask.selecting)
        assertNotNull(s.mask.committed)
        assertEquals(0.8f, v.effectiveMask().right, 1e-6f)
        // 已持久化：重新加载仍是自定义遮罩
        val v2 = vm()
        v2.load()
        advanceUntilIdle()
        assertEquals(0.8f, v2.effectiveMask().right, 1e-6f)
    }

    @Test
    fun `框选-取消后回退默认整图`() = runTest {
        createCard("a", "题干A", dueFsrs(1))
        val v = vm()
        v.load()
        advanceUntilIdle()
        v.enterSelectMode()
        v.cancelSelection()
        assertTrue(v.effectiveMask().isFull())
    }

    @Test
    fun `框选-提交后切卡再返回-遮罩按卡独立`() = runTest {
        createCard("a", "题干A", dueFsrs(2))
        createCard("b", "题干B", dueFsrs(1))
        val v = vm()
        v.load()
        advanceUntilIdle()
        // 给 a 框选自定义区域
        v.enterSelectMode()
        v.updateSelection(com.misoup.app.review.mask.MaskRect(0.1f, 0.1f, 0.5f, 0.5f))
        v.commitSelection()
        // 切到 b：b 无自定义 → 整图
        v.reveal()
        v.submitGrade(3)
        advanceUntilIdle()
        assertEquals("b", v.uiState.value.current?.id)
        assertTrue(v.effectiveMask().isFull())
    }
}
