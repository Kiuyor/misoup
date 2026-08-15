package com.misoup.app.ui.library

import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.makeMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 列表 VM 集成测试（T01-T04/T10）：真实 LibraryIndex + 防抖虚拟时间 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private val index = LibraryIndex()

    @Before
    fun setUp() {
        // viewModelScope 依赖 Main dispatcher；JVM 单测用测试调度器替换
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun addEntry(
        id: String,
        subject: String,
        questionType: String = "解答题",
        errorReasons: List<String> = listOf("概念不清"),
        tags: List<String> = listOf("二次函数"),
        text: String = "题干 $id",
        mtime: Long = 1000L
    ): MisoupMeta {
        val meta = makeMeta(id = id, subject = subject, questionType = questionType, errorReasons = errorReasons, tags = tags)
        index.upsert(meta, thumb = null, mtime = mtime)
        index.indexText(id, text)
        return meta
    }

    private fun vm(scope: kotlinx.coroutines.CoroutineScope, retry: OcrRetryHandler? = null) =
        LibraryViewModel(index, retry, DebounceScheduler(debounceMs = 300, scope = scope))

    @Test
    fun `初始加载-按科目分组-组内时间倒序`() = runTest {
        addEntry("a", "数学", mtime = 100)
        addEntry("b", "英语", mtime = 200)
        addEntry("c", "数学", mtime = 300)

        val vm = vm(this)
        val s = vm.uiState.value
        assertEquals(3, s.total)
        assertEquals(listOf("数学", "英语"), s.groups.map { it.subject })
        assertEquals(listOf("c", "a"), s.groups[0].entries.map { it.id })
        // 可选项同步
        assertEquals(listOf("数学", "英语"), s.availableSubjects)
        assertEquals(listOf("二次函数"), s.availableTags)
    }

    @Test
    fun `筛选叠加-科目与错因组合命中`() = runTest {
        addEntry("a", "数学", errorReasons = listOf("概念不清"), tags = listOf("月考"), mtime = 100)
        addEntry("b", "数学", errorReasons = listOf("计算失误"), tags = listOf("月考"), mtime = 200)
        addEntry("c", "英语", errorReasons = listOf("概念不清"), tags = listOf("月考"), mtime = 300)

        val vm = vm(this)
        vm.toggleFilter(FilterDim.SUBJECT, "数学")
        vm.toggleFilter(FilterDim.ERROR_REASON, "概念不清")
        val s = vm.uiState.value
        assertEquals(listOf("a"), s.groups.flatMap { it.entries }.map { it.id })

        vm.clearFilters()
        assertEquals(3, vm.uiState.value.total)
    }

    @Test
    fun `标签筛选-跨科目叠加`() = runTest {
        addEntry("a", "数学", tags = listOf("月考"), mtime = 100)
        addEntry("b", "英语", tags = listOf("周测"), mtime = 200)
        val vm = vm(this)
        vm.toggleFilter(FilterDim.TAG, "月考")
        assertEquals(listOf("a"), vm.uiState.value.groups.flatMap { it.entries }.map { it.id })
    }

    @Test
    fun `搜索防抖-输入即时-查询 300ms 后生效`() = runTest {
        addEntry("a", "数学", text = "二次函数区间最值问题", mtime = 100)
        addEntry("b", "数学", text = "三角函数图像变换", mtime = 200)

        val vm = vm(this)
        vm.onSearchInput("最值")
        // 输入框即时更新，但查询未生效
        assertEquals("最值", vm.uiState.value.keywordInput)
        assertEquals("", vm.uiState.value.keyword)
        assertEquals(2, vm.uiState.value.total)

        advanceTimeBy(299)
        runCurrent()
        assertEquals(2, vm.uiState.value.total)   // 未到 300ms

        advanceTimeBy(1)
        runCurrent()
        val s = vm.uiState.value
        assertEquals("最值", s.keyword)
        assertEquals(listOf("a"), s.groups.flatMap { it.entries }.map { it.id })
    }

    @Test
    fun `连续输入-只按最终关键词搜索`() = runTest {
        addEntry("a", "数学", text = "二次函数", mtime = 100)
        addEntry("b", "数学", text = "一次函数", mtime = 200)
        val vm = vm(this)
        vm.onSearchInput("二")
        advanceTimeBy(100)
        vm.onSearchInput("二次")
        advanceTimeBy(100)
        vm.onSearchInput("一次函数")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("b"), vm.uiState.value.groups.flatMap { it.entries }.map { it.id })
    }

    @Test
    fun `清空搜索-恢复全量`() = runTest {
        addEntry("a", "数学", text = "二次函数", mtime = 100)
        val vm = vm(this)
        vm.onSearchInput("不存在")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(0, vm.uiState.value.total)
        vm.clearSearch()
        assertEquals("", vm.uiState.value.keywordInput)
        assertEquals(1, vm.uiState.value.total)
    }

    @Test
    fun `视图切换-列表与瀑布流互切`() = runTest {
        addEntry("a", "数学")
        val vm = vm(this)
        assertEquals(LibraryViewModel.ViewMode.LIST, vm.uiState.value.viewMode)
        vm.toggleViewMode()
        assertEquals(LibraryViewModel.ViewMode.STAGGERED, vm.uiState.value.viewMode)
        vm.toggleViewMode()
        assertEquals(LibraryViewModel.ViewMode.LIST, vm.uiState.value.viewMode)
    }

    @Test
    fun `待修复筛选-仅待修复与仅正常`() = runTest {
        addEntry("a", "数学", mtime = 100)
        index.upsertRepair(dirName = "坏目录", subject = "数学", mtime = 200, repairJson = "{}")
        val vm = vm(this)
        assertEquals(2, vm.uiState.value.total)
        vm.cycleRepairFilter()   // → 仅待修复
        assertEquals(1, vm.uiState.value.total)
        assertTrue(vm.uiState.value.groups.flatMap { it.entries }.all { it.needsRepair })
        vm.cycleRepairFilter()   // → 仅正常
        assertEquals(1, vm.uiState.value.total)
        assertTrue(vm.uiState.value.groups.flatMap { it.entries }.none { it.needsRepair })
    }

    @Test
    fun `重试识别-回调处理器带正确 id`() = runTest {
        addEntry("a", "数学")
        val retried = mutableListOf<String>()
        val vm = vm(this, retry = OcrRetryHandler { id -> retried += id })
        vm.retryOcr("a")
        assertEquals(listOf("a"), retried)
    }

    @Test
    fun `refresh-重建索引后重新查询`() = runTest {
        addEntry("a", "数学", mtime = 100)
        val vm = vm(this)
        assertEquals(1, vm.uiState.value.total)
        addEntry("b", "英语", mtime = 200)
        vm.refresh()
        assertEquals(2, vm.uiState.value.total)
    }
}
