package com.misoup.app.review.queue

import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.store.StoreService
import com.misoup.app.review.fsrs.FsrsTime

/**
 * 今日待复习队列（T03）
 *
 * 队列来源：`due <= now` 的错题（模块 03 定稿："队列来源 = index.query { fsrs.due <= now }"）。
 * 排序：due 升序；同 due 按 stability 升序（先复习最不稳定的）。
 *
 * 数据流：磁盘 meta.json（唯一事实源）→ [fromMetas] → [build]。
 * 进程杀后重进：index.rebuild 重建内存索引 + 逐条读回 meta，reviews[]/fsrs 均已在
 * StoreService.update 时持久化，队列从磁盘重建，不丢记录、不重复。
 *
 * 文档裁决：
 * - fsrs.due 为 null（新卡从未排期）视为立即到期（due=0L），排在最前——错题库的
 *   新卡需要首次复习，纳入"今日待复习"。
 * - meta 损坏（待修复条目）不纳入队列：无法取得有效 fsrs 状态，跳过不阻塞整队。
 */
object ReviewQueue {

    /** 队列项（纯数据，不依赖 Android） */
    data class QueueItem(
        val id: String,
        val dirName: String = "",
        val subject: String = "",
        val due: Long,             // epoch ms；新卡 → 0L（排最前）
        val stability: Double = 0.0,
        val isNew: Boolean = false
    )

    /**
     * 构建今日队列（纯函数、幂等）：due <= now，due 升序 + stability 升序。
     * 非法项（due < 0）防御性排除。
     */
    fun build(items: List<QueueItem>, now: Long): List<QueueItem> =
        items.asSequence()
            .filter { it.due in 0L..now }
            .sortedWith(compareBy({ it.due }, { it.stability }, { it.id }))
            .toList()

    /** 今日待复习数量（首页角标 / 复习 tab 横幅用） */
    fun todayCount(items: List<QueueItem>, now: Long): Int = build(items, now).size

    /**
     * meta 列表 → 队列项（纯函数）。due 字符串解析失败 → due=0（立即到期），不丢题。
     * 新卡判定：fsrs.state == 0 或 fsrs.due 为空。
     */
    fun fromMetas(metas: List<MisoupMeta>): List<QueueItem> = metas.map { meta ->
        val dueIso = meta.fsrs.due
        val isNew = meta.fsrs.state == 0 || dueIso.isNullOrBlank()
        QueueItem(
            id = meta.id,
            subject = meta.subject,
            due = FsrsTime.toEpochMs(dueIso) ?: 0L,
            stability = meta.fsrs.stability,
            isNew = isNew
        )
    }
}

/**
 * 磁盘加载器：索引全量遍历 → 逐条读 meta（损坏跳过）→ 构建队列。
 * 供 ViewModel/UI 使用；纯逻辑（排序/过滤）在 [ReviewQueue] 内可单测。
 */
class QueueLoader(
    private val store: StoreService,
    private val index: LibraryIndex
) {
    /**
     * 加载今日队列。
     * - 成功：队列列表（可能为空）
     * - 失败：仅当索引不可用等结构性错误；单条 meta 读失败跳过（不阻塞整队）
     */
    suspend fun loadToday(now: Long): Result<List<ReviewQueue.QueueItem>> = runCatching {
        val metas = index.all()
            .filter { !it.needsRepair }            // 损坏条目跳过
            .mapNotNull { store.readMetaById(it.id).getOrNull() }
        ReviewQueue.build(ReviewQueue.fromMetas(metas), now)
    }

    /** 今日待复习数量（角标/横幅轻量读取） */
    suspend fun todayCount(now: Long): Int = loadToday(now).getOrDefault(emptyList()).size
}
