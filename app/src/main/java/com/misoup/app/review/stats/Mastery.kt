package com.misoup.app.review.stats

import com.misoup.app.data.model.MisoupMeta

/**
 * 知识点掌握度（T10）
 *
 * 模块 03 定稿："mastery(kp) 聚合 + 红黄绿三档（错题数×正确率加权，<0.4 红 / <0.7 黄 / ≥0.7 绿）"。
 *
 * 实现口径（文档裁决）：
 * - 每知识点聚合其全部错题的复习记录：mastery = ΣGood+Easy / Σ复习次数（即按复习量加权的正确率），
 *   落在 0..1。
 * - 知识点下无任何复习记录 → 0.5（未知，中性；不参与任何红黄绿判断的激进/保守倾向）
 * - 未出现在任何 meta.knowledgePoints 中的知识点不产出（map 只含实际知识点）
 * - M4 深化 UI（按知识点看题目列表）不在本模块范围
 */
object Mastery {

    /** 掌握度档位（红黄绿） */
    enum class Level(val displayName: String, val color: Long) {
        RED("掌握不足", 0xFFE53935),      // < 0.4
        YELLOW("有待巩固", 0xFFFDD835),    // [0.4, 0.7)
        GREEN("掌握良好", 0xFF43A047);     // >= 0.7

        companion object {
            /** 档位阈值（模块 03 定稿：<0.4 红 / <0.7 黄 / ≥0.7 绿） */
            const val RED_THRESHOLD = 0.4
            const val YELLOW_THRESHOLD = 0.7
        }
    }

    /** 单个知识点的掌握度 */
    data class KpMastery(
        val knowledgePoint: String,
        val score: Double,          // 0..1
        val totalReviews: Int,      // 该知识点下复习总次数（0 = 未复习）
        val level: Level
    )

    /** 聚合：知识点 → 掌握度分数（0..1）；一条错题含多个知识点时各贡献一次 */
    fun calculate(metas: List<MisoupMeta>): Map<String, Double> {
        // 展平：kp → 含该知识点的全部错题
        val byKp = LinkedHashMap<String, MutableList<MisoupMeta>>()
        metas.forEach { meta ->
            meta.knowledgePoints.forEach { kp ->
                byKp.getOrPut(kp) { mutableListOf() }.add(meta)
            }
        }
        val scores = mutableMapOf<String, Double>()
        for ((kp, kpMetas) in byKp) {
            // 每个知识点下所有错题的所有复习记录
            val reviews = kpMetas.flatMap { it.reviews }
            val total = reviews.size
            val score = if (total == 0) {
                0.5   // 未复习 → 中性
            } else {
                reviews.count { it.rating == 2 || it.rating == 3 }.toDouble() / total
            }
            scores[kp] = score
        }
        return scores
    }

    /** 单知识点聚合（含档位与统计信息；测试与 UI 共用） */
    fun of(metas: List<MisoupMeta>, knowledgePoint: String): KpMastery {
        val kpMetas = metas.filter { knowledgePoint in it.knowledgePoints }
        val reviews = kpMetas.flatMap { it.reviews }
        val total = reviews.size
        val score = if (total == 0) 0.5
        else reviews.count { it.rating == 2 || it.rating == 3 }.toDouble() / total
        return KpMastery(knowledgePoint, score, total, levelOf(score))
    }

    /** 分数 → 红黄绿档位（边界：0.4 → 黄；0.7 → 绿） */
    fun levelOf(score: Double): Level = when {
        score < Level.RED_THRESHOLD -> Level.RED
        score < Level.YELLOW_THRESHOLD -> Level.YELLOW
        else -> Level.GREEN
    }
}
