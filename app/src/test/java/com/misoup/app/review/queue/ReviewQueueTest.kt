package com.misoup.app.review.queue

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.FsrsState
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.review.fsrs.FsrsTime
import com.misoup.app.review.scheduler.ReviewSubmitter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T03 今日队列：due<=now 过滤、due+stability 双键排序、进程杀后重建不丢记录
 */
class ReviewQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService
    private val img = FakeImageAsset(100, 100)

    private val DAY = 86_400_000L
    private val NOW = 1_000_000_000_000L

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        index = LibraryIndex()
        store = StoreService(root, index, FakeImageEncoder())
    }

    /** 造题：fsrs 状态可控 */
    private suspend fun createCard(id: String, subject: String = "数学", fsrs: FsrsState = FsrsState()) {
        store.create(makeMeta(id = id, subject = subject, fsrs = fsrs), img, "题干$id").getOrThrow()
    }

    private fun fsrs(state: Int, dueDays: Long?, stability: Double) = FsrsState(
        state = state,
        due = dueDays?.let { FsrsTime.toIso(NOW + it * DAY) },
        stability = stability,
        difficulty = 5.0,
        reps = if (state == 0) 0 else 3,
        lapses = 0,
        lastReview = FsrsTime.toIso(NOW - DAY)
    )

    // ---------- 纯函数：过滤与排序 ----------

    @Test
    fun `build-仅保留due小于等于now的题`() {
        val items = listOf(
            ReviewQueue.QueueItem("a", due = NOW - DAY),      // 逾期
            ReviewQueue.QueueItem("b", due = NOW),            // 恰好到期
            ReviewQueue.QueueItem("c", due = NOW + DAY),      // 未到期
            ReviewQueue.QueueItem("d", due = 0L),             // 新卡
            ReviewQueue.QueueItem("e", due = -5L)             // 非法负值 → 排除
        )
        val q = ReviewQueue.build(items, NOW)
        assertEquals(listOf("d", "a", "b"), q.map { it.id })
    }

    @Test
    fun `build-due升序同due按stability升序`() {
        val items = listOf(
            ReviewQueue.QueueItem("s10", due = 100L, stability = 10.0),
            ReviewQueue.QueueItem("s1", due = 100L, stability = 1.0),
            ReviewQueue.QueueItem("late", due = 200L, stability = 0.5),
            ReviewQueue.QueueItem("early", due = 50L, stability = 99.0),
            ReviewQueue.QueueItem("s5", due = 100L, stability = 5.0)
        )
        val q = ReviewQueue.build(items, now = 1_000L)
        // 先 due 升序（50 → 100 → 200）；100 组内 stability 升序（1 → 5 → 10）
        assertEquals(listOf("early", "s1", "s5", "s10", "late"), q.map { it.id })
    }

    @Test
    fun `build-空列表与空队列`() {
        assertTrue(ReviewQueue.build(emptyList(), NOW).isEmpty())
        val q = ReviewQueue.build(listOf(ReviewQueue.QueueItem("x", due = NOW + DAY)), NOW)
        assertTrue(q.isEmpty())
    }

    // ---------- fromMetas：新卡/解析失败兜底 ----------

    @Test
    fun `fromMetas-新卡due为空按立即到期`() {
        val meta = makeMeta(id = "new1", fsrs = FsrsState(state = 0, due = null))
        val items = ReviewQueue.fromMetas(listOf(meta))
        assertEquals(1, items.size)
        assertTrue(items[0].isNew)
        assertEquals(0L, items[0].due)
    }

    @Test
    fun `fromMetas-损坏due字符串按立即到期不丢题`() {
        // state=2（已复习过）但 due 损坏：due 解析失败 → 0（立即到期），isNew 仍按 state 判定
        val meta = makeMeta(id = "bad", fsrs = FsrsState(state = 2, due = "不是日期"))
        val items = ReviewQueue.fromMetas(listOf(meta))
        assertEquals(0L, items[0].due)
        assertTrue(!items[0].isNew)
    }

    @Test
    fun `fromMetas-新卡且due损坏按新卡处理`() {
        val meta = makeMeta(id = "bad2", fsrs = FsrsState(state = 0, due = "不是日期"))
        val item = ReviewQueue.fromMetas(listOf(meta)).single()
        assertEquals(0L, item.due)
        assertTrue(item.isNew)
    }

    @Test
    fun `fromMetas-正常到期题保留due与stability`() {
        val meta = makeMeta(
            id = "ok", fsrs = FsrsState(state = 2, due = FsrsTime.toIso(NOW), stability = 3.5)
        )
        val item = ReviewQueue.fromMetas(listOf(meta)).single()
        assertEquals(NOW, item.due)
        assertEquals(3.5, item.stability, 1e-9)
        assertTrue(!item.isNew)
    }

    // ---------- 验收：造 10 题复习 3 题，队列数量与 due 变化符合预期 ----------

    @Test
    fun `造10题复习3题-队列从10变7且新due在未来`() = runTest {
        repeat(10) { i -> createCard("card$i") }   // 全部新卡（due=null → 立即到期）
        val loader = QueueLoader(store, index)
        assertEquals(10, loader.loadToday(NOW).getOrThrow().size)

        val submitter = ReviewSubmitter(store)
        // 复习前 3 张（Good）
        val reviewed = loader.loadToday(NOW).getOrThrow().take(3)
        reviewed.forEach { item ->
            submitter.submit(item.id, 3, now = NOW).getOrThrow()
        }
        // 剩余 7 张；已复习的 3 张 due 在未来（不在今日队列）
        val remaining = loader.loadToday(NOW).getOrThrow()
        assertEquals(7, remaining.size)
        assertTrue(remaining.none { it.id in reviewed.map { r -> r.id } })
        // 已复习卡片 meta：due 在未来、reviews 有记录
        reviewed.forEach { item ->
            val meta = store.readMetaById(item.id).getOrThrow()
            assertTrue(FsrsTime.toEpochMs(meta.fsrs.due)!! > NOW)
            assertEquals(1, meta.reviews.size)
            assertEquals(2, meta.reviews[0].rating)   // grade 3 Good → rating 2
        }
    }

    // ---------- 验收：进程杀后重建队列不丢记录 ----------

    @Test
    fun `进程杀后重建-队列恢复且无重复复习记录`() = runTest {
        createCard("a"); createCard("b"); createCard("c")
        val submitter = ReviewSubmitter(store)
        submitter.submit("a", 3, now = NOW).getOrThrow()
        submitter.submit("b", 1, now = NOW).getOrThrow()   // Again → 状态变重学/学习中

        // 模拟进程被杀：丢弃内存索引与 StoreService，从磁盘全量重建
        val index2 = LibraryIndex()
        index2.rebuild(root)
        val store2 = StoreService(root, index2, FakeImageEncoder())
        val loader2 = QueueLoader(store2, index2)

        val remaining = loader2.loadToday(NOW).getOrThrow()
        assertEquals(listOf("c"), remaining.map { it.id })   // a、b 已排到未来

        // 重建后记录不丢、不重复
        val metaA = store2.readMetaById("a").getOrThrow()
        assertEquals(1, metaA.reviews.size)
        assertTrue(FsrsTime.toEpochMs(metaA.fsrs.lastReview) != null)
        // 再复习一次不会追加两条
        submitter.submit("c", 3, now = NOW).getOrThrow()
        val metaC = store2.readMetaById("c").getOrThrow()
        assertEquals(1, metaC.reviews.size)
    }

    // ---------- 失败路径 ----------

    @Test
    fun `损坏条目不阻塞队列加载`() = runTest {
        createCard("good1")
        // 人为制造损坏条目：meta.json 写坏
        val entry = index.get("good1")!!
        File(File(File(root, "数学"), entry.dirName), "meta.json").writeText("{corrupt")
        index.rebuild(root)

        val loader = QueueLoader(store, index)
        // 损坏条目被标记待修复，不参与队列；加载不抛异常
        assertTrue(index.get("repair:数学:${entry.dirName}")!!.needsRepair)
        assertEquals(0, loader.loadToday(NOW).getOrThrow().size)
    }
}
