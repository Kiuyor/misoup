package com.misoup.app.ai.mastery

import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.review.stats.Mastery

/**
 * 薄弱知识点聚合（模块 07 T06，复用模块 03 [Mastery]）：
 * - [topWeak]：按掌握度升序取 top N（薄弱在前），每项带关联错题 id 列表（点击直达）
 * - [relatedEntries]：知识点 → 关联错题（详情页列表）
 *
 * 排序裁决：掌握度升序 → 复习次数升序（复习少 = 更不确定，排前）→ 知识点名（稳定）。
 * 未复习知识点（[Mastery] 给 0.5 中性分）参与排序，但不会人为垫底。
 */
object WeakKpAggregator {

    /** 薄弱卡片默认条数（module/07 定稿：top5） */
    const val DEFAULT_LIMIT = 5

    /** 薄弱知识点卡片项 */
    data class WeakKp(
        val knowledgePoint: String,
        val score: Double,          // 0..1
        val totalReviews: Int,
        val level: Mastery.Level,
        /** 关联错题 id（点击直达详情） */
        val entryIds: List<String>
    )

    /** 按掌握度升序取 top N 薄弱知识点 */
    fun topWeak(metas: List<MisoupMeta>, limit: Int = DEFAULT_LIMIT): List<WeakKp> {
        if (limit <= 0) return emptyList()
        val byKp = LinkedHashMap<String, MutableList<MisoupMeta>>()
        metas.forEach { meta ->
            meta.knowledgePoints.forEach { kp ->
                byKp.getOrPut(kp) { mutableListOf() }.add(meta)
            }
        }
        return byKp.map { (kp, kpMetas) ->
            val m = Mastery.of(kpMetas, kp)
            WeakKp(
                knowledgePoint = kp,
                score = m.score,
                totalReviews = m.totalReviews,
                level = m.level,
                entryIds = kpMetas.map { it.id }
            )
        }.sortedWith(
            compareBy({ it.score }, { it.totalReviews }, { it.knowledgePoint })
        ).take(limit)
    }

    /** 知识点 → 关联错题（保持库内顺序） */
    fun relatedEntries(metas: List<MisoupMeta>, knowledgePoint: String): List<MisoupMeta> =
        metas.filter { knowledgePoint in it.knowledgePoints }
}
