package com.misoup.app.review.scheduler

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.FsrsState
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.review.fsrs.Fsrs
import com.misoup.app.review.fsrs.FsrsTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T06 复习提交：Fsrs.review → StoreService.update（reviews[] + fsrs 字段），
 * grade 1-4 → rating 0-3 映射；正常 / 边界 / 失败路径
 */
class ReviewSubmitterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService
    private lateinit var submitter: ReviewSubmitter
    private val img = FakeImageAsset(100, 100)

    private val NOW = 1_000_000_000_000L

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        index = LibraryIndex()
        store = StoreService(root, index, FakeImageEncoder())
        submitter = ReviewSubmitter(store)
    }

    private suspend fun createCard(id: String, fsrs: FsrsState = FsrsState(), reviews: List<com.misoup.app.data.model.Review> = emptyList()): String {
        val meta = makeMeta(id = id, subject = "数学", fsrs = fsrs, reviews = reviews)
        store.create(meta, img, "题干$id").getOrThrow()
        return id
    }

    // ---------- rating 映射（模块 03 定稿：grade 1-4 → rating 0-3） ----------

    @Test
    fun `rating映射-grade1到4对应rating0到3`() = runTest {
        createCard("m")
        val results = (1..4).map { grade ->
            submitter.submit("m", grade, now = NOW).getOrThrow()
        }
        assertEquals(listOf(0, 1, 2, 3), results.map { it.rating })
        // meta.reviews 依次追加，顺序与提交一致
        val meta = store.readMetaById("m").getOrThrow()
        assertEquals(listOf(0, 1, 2, 3), meta.reviews.map { it.rating })
    }

    // ---------- 正常路径 ----------

    @Test
    fun `新卡Good提交-fsrs字段与reviews落盘`() = runTest {
        createCard("n")
        val r = submitter.submit("n", 3, now = NOW).getOrThrow()

        assertEquals(2, r.stateAfter)                    // 新卡 Good → 已掌握
        assertEquals(2, r.rating)                        // grade 3 → rating 2
        assertTrue(r.intervalDays > 0)
        assertTrue(r.newDueEpochMs > NOW)

        val meta = store.readMetaById("n").getOrThrow()
        assertEquals(2, meta.fsrs.state)
        assertEquals(1, meta.reviews.size)
        assertEquals(1, meta.fsrs.reps)
        assertEquals(0, meta.fsrs.lapses)
        assertEquals(3.173, meta.fsrs.stability, 1e-6)   // w[2] = 3.173
        assertEquals(7.1949, meta.fsrs.difficulty, 1e-6) // w[4] - w[5]*(3-3)
        assertEquals(FsrsTime.toIso(NOW), meta.fsrs.lastReview)
        assertNotNull(meta.fsrs.due)
        assertEquals(2, meta.reviews[0].stateAfter)
        assertEquals(FsrsTime.toIso(NOW), meta.reviews[0].date)
    }

    @Test
    fun `连续复习-与Fsrs纯函数结果一致且reviews累积`() = runTest {
        createCard("seq")
        // 与 FsrsTest.play 相同的 4 连 Good 序列
        var card = Fsrs.Card()
        var now = 0L
        repeat(4) {
            card = Fsrs.review(card, Fsrs.GRADE_GOOD, now = now)
            now = card.due
        }
        // 用 submitter 逐步提交（每次 now 取上次 due）
        var t = 0L
        var due = 0L
        repeat(4) { i ->
            val r = submitter.submit("seq", 3, now = t).getOrThrow()
            due = r.newDueEpochMs
            t = due
            val meta = store.readMetaById("seq").getOrThrow()
            assertEquals(i + 1, meta.reviews.size)          // 累积不覆盖
        }
        val meta = store.readMetaById("seq").getOrThrow()
        assertEquals(4, meta.reviews.size)
        assertEquals(4, meta.fsrs.reps)
        // 与纯函数最终 due 一致（同一算法）
        assertEquals(card.due, due)
        assertEquals(card.stability, meta.fsrs.stability, 1e-9)
    }

    @Test
    fun `已掌握卡Again-lapses加1且due大幅缩短`() = runTest {
        createCard("l")
        submitter.submit("l", 3, now = NOW).getOrThrow()
        val before = store.readMetaById("l").getOrThrow()
        val r = submitter.submit("l", 1, now = NOW + Fsrs.DAY_MS).getOrThrow()   // 次日复习再 Again

        assertEquals(3, r.stateAfter)                       // 已掌握 → 重学
        val meta = store.readMetaById("l").getOrThrow()
        assertEquals(1, meta.fsrs.lapses)
        assertTrue(meta.fsrs.stability < before.fsrs.stability)   // 遗忘稳定性下降
        // Again 间隔 = s×0.25 < 上一 Good 间隔
        assertTrue(r.intervalDays < before.fsrs.stability)
    }

    // ---------- 边界与失败路径 ----------

    @Test
    fun `条目不存在-返回失败`() = runTest {
        val r = submitter.submit("no-such", 3, now = NOW)
        assertTrue(r.isFailure)
    }

    @Test
    fun `meta损坏-提交失败不崩溃`() = runTest {
        createCard("corrupt")
        val entry = index.get("corrupt")!!
        File(File(File(root, "数学"), entry.dirName), "meta.json").writeText("{bad")
        index.rebuild(root)
        val r = submitter.submit("corrupt", 3, now = NOW)
        assertTrue(r.isFailure)
    }

    @Test
    fun `非法grade-保守按Again提交`() = runTest {
        createCard("badg")
        val r = submitter.submit("badg", 99, now = NOW).getOrThrow()
        assertEquals(0, r.rating)                            // 99 → Again → rating 0
        assertEquals(1, r.stateAfter)                        // 新卡 Again → 学习中
    }

    @Test
    fun `新卡due为空的复习-首次提交正常`() = runTest {
        // makeMeta 默认 FsrsState()：state=0, due=null
        createCard("fresh")
        val r = submitter.submit("fresh", 4, now = NOW).getOrThrow()
        assertEquals(2, r.stateAfter)
        val meta = store.readMetaById("fresh").getOrThrow()
        assertTrue(FsrsTime.toEpochMs(meta.fsrs.due)!! > NOW)   // Easy → 远未来
    }

    @Test
    fun `旧记录兼容-已带reviews的卡提交后追加不覆盖`() = runTest {
        createCard("old", reviews = listOf(com.misoup.app.data.model.Review(date = "2026-08-01T00:00:00Z", rating = 2)))
        submitter.submit("old", 2, now = NOW).getOrThrow()
        val meta = store.readMetaById("old").getOrThrow()
        assertEquals(2, meta.reviews.size)
        assertEquals(1, meta.reviews[1].rating)   // grade 2 → rating 1
    }
}
