package com.misoup.app.ui.library

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 编辑 VM 测试（T07/T09）：保存落盘 + 倒排刷新 + 重命名 id 不变 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryEditViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        index = LibraryIndex()
        store = StoreService(tmp.newFolder("library"), index, FakeImageEncoder())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun createEntry(id: String = "uuid-edit-1"): String {
        val meta = makeMeta(
            id = id,
            subject = "数学",
            questionType = "解答题",
            errorReasons = listOf("概念不清"),
            tags = listOf("二次函数"),
            knowledgePoints = listOf("最值"),
            source = "月考",
            note = "旧备注"
        )
        store.create(meta, FakeImageAsset(100, 200), "## 题干\n旧题干\n\n## 答案\nA\n\n## 解析\nE").getOrThrow()
        return id
    }

    private fun vm() = LibraryEditViewModel(index, store)

    @Test
    fun `加载-三栏与表单字段回填`() = runTest {
        val id = createEntry()
        val vm = vm()
        vm.load(id)
        advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.loaded)
        assertEquals("旧题干", s.question)
        assertEquals("A", s.answer)
        assertEquals("E", s.explanation)
        assertEquals("数学", s.subject)
        assertEquals("解答题", s.questionType)
        assertEquals(setOf("概念不清"), s.errorReasons)
        assertEquals(listOf("二次函数"), s.tags)
        assertEquals(listOf("最值"), s.knowledgePoints)
        assertEquals("月考", s.source)
        assertEquals("旧备注", s.note)
        assertNotNull(s.renamePreview)
    }

    @Test
    fun `编辑题干-重命名预览实时更新`() = runTest {
        val id = createEntry()
        val vm = vm()
        vm.load(id)
        advanceUntilIdle()
        vm.onQuestionChange("新题干二次函数区间")
        val preview = vm.state.value.renamePreview
        assertTrue(preview!!.endsWith("新题干二次函数区间"))
    }

    @Test
    fun `保存- meta 与文本落盘且倒排刷新`() = runTest {
        val id = createEntry()
        // 先建立旧倒排
        index.indexText(id, "旧题干")
        assertEquals(listOf(id), index.query(com.misoup.app.data.index.Filter(), "旧题干").map { it.id })

        val vm = vm()
        vm.load(id)
        advanceUntilIdle()
        vm.onQuestionChange("全新的三角函数问题")
        vm.onAnswerChange("答案B")
        vm.onExplanationChange("解析B")
        vm.onSubjectChange("物理")
        vm.onQuestionTypeChange("选择题")
        vm.toggleErrorReason("计算失误")
        vm.onTagsTextChange("三角，高考")
        vm.onKnowledgeTextChange("恒等变换")
        vm.onSourceChange("期末")
        vm.onNoteChange("新备注")
        vm.save()
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.saved)
        assertTrue(s.message!!.startsWith("已保存"))

        // meta 落盘
        val meta = store.readMetaById(id).getOrThrow()
        assertEquals("物理", meta.subject)
        assertEquals("选择题", meta.questionType)
        assertTrue("计算失误" in meta.errorReasons)
        assertEquals(listOf("三角", "高考"), meta.tags)
        assertEquals(listOf("恒等变换"), meta.knowledgePoints)
        assertEquals("期末", meta.source)
        assertEquals("新备注", meta.note)
        // id 不变
        assertEquals(id, meta.id)

        // 文本落盘 + 分段解析
        val raw = store.readTextById(id).getOrThrow()
        val parsed = LibraryTextFormat.parse(raw)
        assertEquals("全新的三角函数问题", parsed.question)
        assertEquals("答案B", parsed.answer)
        assertEquals("解析B", parsed.explanation)

        // 倒排刷新：旧关键词不再命中，新题干与标签命中
        assertEquals(0, index.query(com.misoup.app.data.index.Filter(), "旧题干").size)
        assertEquals(listOf(id), index.query(com.misoup.app.data.index.Filter(), "三角函数").map { it.id })
        // T03：关键词搜索命中标签（三角）与标签叠加；知识点不在搜索范围
        assertEquals(listOf(id), index.query(com.misoup.app.data.index.Filter(), "三角").map { it.id })
        assertEquals(listOf(id), index.query(com.misoup.app.data.index.Filter(), "高考").map { it.id })
        assertEquals(0, index.query(com.misoup.app.data.index.Filter(), "恒等变换").size)
    }

    @Test
    fun `保存-表单未动也能保存成功`() = runTest {
        val id = createEntry()
        val vm = vm()
        vm.load(id)
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.saved)
        // 磁盘 meta 仍可解析且字段未丢
        val meta = store.readMetaById(id).getOrThrow()
        assertEquals("数学", meta.subject)
        assertEquals(listOf("二次函数"), meta.tags)
    }

    @Test
    fun `重命名-目录名变化而 id 不变-重启索引可重建`() = runTest {
        val id = createEntry()
        val oldDir = index.get(id)!!.dirName

        val vm = vm()
        vm.load(id)
        advanceUntilIdle()
        vm.onQuestionChange("重命名后的新题目")
        vm.renameDir()
        advanceUntilIdle()

        val newDir = index.get(id)!!.dirName
        assertNotEquals(oldDir, newDir)
        assertTrue(newDir.endsWith("重命名后的新题目"))
        assertEquals(id, index.get(id)!!.id)   // id 不变

        // 目录真实迁移
        assertTrue(File(File(tmp.root, "library/数学"), newDir).isDirectory)
        assertFalse(File(File(tmp.root, "library/数学"), oldDir).exists())

        // meta.id 不变 → 重启（重建索引）不丢关联
        val meta = store.readMetaById(id).getOrThrow()
        assertEquals(id, meta.id)
        index.rebuild(File(tmp.root, "library"))
        assertEquals(id, index.get(id)?.id)
        assertEquals(newDir, index.get(id)?.dirName)
    }

    @Test
    fun `标签分隔-支持中英文逗号顿号分号`() {
        assertEquals(
            listOf("a", "b", "c"),
            LibraryEditViewModel.splitList("a，b、c")
        )
        assertEquals(
            listOf("甲", "乙"),
            LibraryEditViewModel.splitList("甲, 乙;")
        )
        assertTrue(LibraryEditViewModel.splitList("  , ， ").isEmpty())
        assertEquals(listOf("x"), LibraryEditViewModel.splitList("x，x，x").take(1))
    }

    @Test
    fun `addTag-去重与空白过滤`() = runTest {
        val id = createEntry()
        val vm = vm()
        vm.load(id)
        advanceUntilIdle()
        vm.addTag("新标签")
        vm.addTag("新标签")   // 去重
        vm.addTag("   ")      // 空白忽略
        assertEquals(listOf("二次函数", "新标签"), vm.state.value.tags)
    }
}
