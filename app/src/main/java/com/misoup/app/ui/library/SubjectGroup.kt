package com.misoup.app.ui.library

import com.misoup.app.data.index.IndexedEntry

/**
 * 科目分组（T01）
 *
 * 错题库列表按科目分组展示：组内按 mtime 时间倒序（最近编辑在前），
 * 组间按组内最新条目时间倒序（最新活跃的科目在前）。
 */
data class SubjectGroup(
    val subject: String,
    val entries: List<IndexedEntry>
)

/** 分组纯逻辑（可 JVM 单测） */
object LibraryGrouping {

    /**
     * 按科目分组。空输入返回空列表。
     * - 组内：mtime 降序（相等时保持原顺序稳定）
     * - 组间：按各组最新 mtime 降序
     * - 损坏条目（needsRepair）也参与分组（subject 恒有值）
     */
    fun groupBySubject(entries: List<IndexedEntry>): List<SubjectGroup> {
        if (entries.isEmpty()) return emptyList()
        val groups = entries.groupBy { it.subject }
            .map { (subject, list) ->
                SubjectGroup(subject, list.sortedByDescending { it.mtime })
            }
        // 组间按最新条目时间倒序；同时间按科目名稳定排序
        return groups.sortedWith(
            compareByDescending<SubjectGroup> { it.entries.first().mtime }
                .thenBy { it.subject }
        )
    }

    /** 当前结果中实际出现的科目列表（筛选栏 chips 数据源） */
    fun subjectsOf(entries: List<IndexedEntry>): List<String> =
        entries.map { it.subject }.distinct()
}
