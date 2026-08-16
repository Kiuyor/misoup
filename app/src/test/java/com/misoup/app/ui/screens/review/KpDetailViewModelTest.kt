package com.misoup.app.ui.screens.review

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.Review
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.review.stats.Mastery
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
 * 知识点详情 VM（模块 07 T06）：真实 StoreService + 临时目录。
 * - 加载：关联错题列表（含目录名/科目/摘要/复习次数）+ 掌握度头部
 * - 未知知识点 → 空列表不崩；掌握度按模块 03 聚合
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KpDetailViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService

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

    private suspend fun createEntry(
        id: String,
        subject: String = "数学",
        kps: List<String> = listOf("二次函数最值"),
        text: String = "## 题干\n求最值\n\n## 答案\n9",
        reviews: List<Review> = emptyList()
    ) {
        store.create(
            makeMeta(id = id, subject = subject, knowledgePoints = kps, reviews = reviews),
            FakeImageAsset(100, 200),
            text
        ).getOrThrow()
    }

    @Test
    fun `加载-关联错题与掌握度`() = runTest {
        createEntry("a", kps = listOf("二次函数最值"), reviews = listOf(Review("2026-08-16T10:00:00+08:00", 0)))
        createEntry("b", kps = listOf("二次函数最值"), reviews = listOf(Review("2026-08-16T10:00:00+08:00", 2)))
        createEntry("c", kps = listOf("三角函数"), reviews = listOf(Review("2026-08-16T10:00:00+08:00", 2)))

        val vm = KpDetailViewModel(store, index)
        vm.load("二次函数最值")
        advanceUntilIdle()

        val s = vm.state.value
        assertEquals("二次函数最值", s.kp)
        assertEquals(2, s.entries.size)
        assertEquals(listOf("a", "b"), s.entries.map { it.id })
        // 掌握度：1/2 对 → 0.5 黄
        assertEquals(0.5, s.mastery!!.score, 1e-9)
        assertEquals(Mastery.Level.YELLOW, s.mastery!!.level)
        assertEquals(2, s.mastery!!.totalReviews)
        // 行信息：科目 + 复习次数
        assertTrue(s.entries.all { it.subject == "数学" })
        assertEquals(listOf(1, 1), s.entries.map { it.reviewCount })
        assertTrue(s.entries.all { it.dirName.isNotBlank() })
    }

    @Test
    fun `未知知识点-空列表-掌握度中性`() = runTest {
        createEntry("a")
        val vm = KpDetailViewModel(store, index)
        vm.load("不存在的知识点")
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.entries.isEmpty())
        assertFalse(s.loading)
        assertNull(s.mastery)   // 未出现在任何 meta → 无该知识点聚合
    }

    @Test
    fun `摘要与目录名-来自索引`() = runTest {
        // 无标头纯文本：首行即摘要
        createEntry("a", text = "这是摘要第一行内容")
        // 生产路径 AppGraph.init 会 rebuild 索引填充摘要；测试模拟之
        index.rebuild(root, null)
        val vm = KpDetailViewModel(store, index)
        vm.load("二次函数最值")
        advanceUntilIdle()

        val e = vm.state.value.entries[0]
        assertTrue(e.summary.contains("这是摘要"))
        assertTrue(e.dirName.contains("这是摘要"))
    }
}
