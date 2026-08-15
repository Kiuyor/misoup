package com.misoup.app.ui.library

import com.misoup.app.data.index.Filter

/** 筛选维度（T02） */
enum class FilterDim { SUBJECT, QUESTION_TYPE, ERROR_REASON, TAG }

/**
 * 多选筛选状态（纯逻辑，可 JVM 单测）
 *
 * 四个维度各自多选，维度之间 AND 叠加；空集合 = 不过滤该维度。
 * [needsRepair]：null=全部，true=仅待修复，false=仅正常。
 */
data class FilterState(
    val subjects: Set<String> = emptySet(),
    val questionTypes: Set<String> = emptySet(),
    val errorReasons: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val needsRepair: Boolean? = null
) {

    /** 切换某个维度值：已选则移除（单选→取消），未选则加入 */
    fun toggle(dim: FilterDim, value: String): FilterState = when (dim) {
        FilterDim.SUBJECT -> copy(subjects = toggleIn(subjects, value))
        FilterDim.QUESTION_TYPE -> copy(questionTypes = toggleIn(questionTypes, value))
        FilterDim.ERROR_REASON -> copy(errorReasons = toggleIn(errorReasons, value))
        FilterDim.TAG -> copy(tags = toggleIn(tags, value))
    }

    /** 切换"仅待修复"：null→true→false→null 循环 */
    fun cycleRepairFilter(): FilterState = copy(needsRepair = when (needsRepair) {
        null -> true
        true -> false
        false -> null
    })

    /** 是否选中某值 */
    fun isSelected(dim: FilterDim, value: String): Boolean = when (dim) {
        FilterDim.SUBJECT -> value in subjects
        FilterDim.QUESTION_TYPE -> value in questionTypes
        FilterDim.ERROR_REASON -> value in errorReasons
        FilterDim.TAG -> value in tags
    }

    /** 是否还有任何筛选条件生效 */
    val isActive: Boolean
        get() = subjects.isNotEmpty() || questionTypes.isNotEmpty() ||
            errorReasons.isNotEmpty() || tags.isNotEmpty() || needsRepair != null

    /** 已选数量（角标展示用） */
    val selectedCount: Int
        get() = subjects.size + questionTypes.size + errorReasons.size + tags.size +
            (if (needsRepair != null) 1 else 0)

    fun clear(): FilterState = FilterState()

    /** 转数据层 Filter（空集合 = 不过滤；needsRepair 透传三态） */
    fun toFilter(): Filter = Filter(
        subjects = subjects.toList(),
        questionTypes = questionTypes.toList(),
        errorReasons = errorReasons.toList(),
        tags = tags.toList(),
        needsRepair = needsRepair
    )

    companion object {
        private fun toggleIn(set: Set<String>, value: String): Set<String> =
            if (value in set) set - value else set + value
    }
}
