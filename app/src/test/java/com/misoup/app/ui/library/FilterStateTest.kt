package com.misoup.app.ui.library

import com.misoup.app.data.index.Filter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T02 筛选叠加：多选 chips 组合 → Filter；维度间 AND、维度内 OR */
class FilterStateTest {

    @Test
    fun `初始状态-无筛选-转 Filter 全空`() {
        val state = FilterState()
        assertFalse(state.isActive)
        assertEquals(0, state.selectedCount)
        val filter = state.toFilter()
        assertTrue(filter.subjects.isEmpty())
        assertTrue(filter.questionTypes.isEmpty())
        assertTrue(filter.errorReasons.isEmpty())
        assertTrue(filter.tags.isEmpty())
        assertNull(filter.needsRepair)
    }

    @Test
    fun `toggle 添加与移除`() {
        var state = FilterState()
        state = state.toggle(FilterDim.SUBJECT, "数学")
        assertTrue(state.isSelected(FilterDim.SUBJECT, "数学"))
        assertEquals(setOf("数学"), state.subjects)
        state = state.toggle(FilterDim.SUBJECT, "数学")
        assertFalse(state.isSelected(FilterDim.SUBJECT, "数学"))
        assertTrue(state.subjects.isEmpty())
    }

    @Test
    fun `同维度多选-叠加为 OR`() {
        val state = FilterState()
            .toggle(FilterDim.SUBJECT, "数学")
            .toggle(FilterDim.SUBJECT, "英语")
        assertEquals(setOf("数学", "英语"), state.subjects)
        val filter = state.toFilter()
        assertEquals(listOf("数学", "英语"), filter.subjects)
    }

    @Test
    fun `跨维度多选-叠加为 AND-维度内多值`() {
        val state = FilterState()
            .toggle(FilterDim.SUBJECT, "数学")
            .toggle(FilterDim.QUESTION_TYPE, "选择题")
            .toggle(FilterDim.ERROR_REASON, "概念不清")
            .toggle(FilterDim.ERROR_REASON, "计算失误")
            .toggle(FilterDim.TAG, "二次函数")
        val filter = state.toFilter()
        assertEquals(listOf("数学"), filter.subjects)
        assertEquals(listOf("选择题"), filter.questionTypes)
        assertEquals(listOf("概念不清", "计算失误"), filter.errorReasons)
        assertEquals(listOf("二次函数"), filter.tags)
        assertTrue(state.isActive)
        assertEquals(5, state.selectedCount)
    }

    @Test
    fun `needsRepair 三态循环`() {
        var state = FilterState()
        assertNull(state.needsRepair)
        state = state.cycleRepairFilter()
        assertEquals(true, state.needsRepair)
        state = state.cycleRepairFilter()
        assertEquals(false, state.needsRepair)
        state = state.cycleRepairFilter()
        assertNull(state.needsRepair)
        // 与其它维度叠加时透传
        val withOthers = FilterState(needsRepair = true).toggle(FilterDim.SUBJECT, "数学")
        assertEquals(true, withOthers.toFilter().needsRepair)
    }

    @Test
    fun `clear 重置全部维度`() {
        val state = FilterState(needsRepair = true)
            .toggle(FilterDim.SUBJECT, "数学")
            .toggle(FilterDim.TAG, "a")
            .clear()
        assertFalse(state.isActive)
        assertTrue(state.subjects.isEmpty())
        assertNull(state.needsRepair)
        assertEquals(Filter(), state.toFilter())
    }

    @Test
    fun `toFilter 语义-空集合不过滤该维度`() {
        val filter = FilterState().toggle(FilterDim.TAG, "月考").toFilter()
        assertTrue(filter.subjects.isEmpty())       // 未选科目 → 不过滤科目
        assertTrue(filter.questionTypes.isEmpty())
        assertTrue(filter.errorReasons.isEmpty())
        assertEquals(listOf("月考"), filter.tags)
        assertNull(filter.needsRepair)
    }
}
