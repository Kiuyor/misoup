package com.misoup.app.splitting.ui

import com.misoup.app.splitting.candidates.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPageViewModelTest {

    private fun page(): List<TextLine> = listOf(
        TextLine(y = 0f, height = 15f, x = 0f, width = 300f, text = "1. 题干甲"),
        TextLine(y = 40f, height = 15f, x = 0f, width = 300f, text = "2. 题干乙"),
        TextLine(y = 80f, height = 15f, x = 0f, width = 300f, text = "3. 题干丙"),
    )

    @Test
    fun `load produces three boxes with success message`() {
        val vm = SplitPageViewModel(page(), pageWidth = 300)
        assertEquals(3, vm.state.value.boxes.size)
        assertFalse(vm.state.value.unsplittable)
        assertTrue(vm.state.value.message.contains("3"))
    }

    @Test
    fun `no boundary page is unsplittable with manual crop hint`() {
        val lines = listOf(
            TextLine(y = 0f, height = 15f, x = 0f, width = 300f, text = "没有题号"),
            TextLine(y = 20f, height = 15f, x = 0f, width = 300f, text = "乱排版"),
        )
        val vm = SplitPageViewModel(lines, pageWidth = 300)
        assertTrue(vm.state.value.unsplittable)
        assertTrue(vm.state.value.boxes.isEmpty())
        assertEquals("未识别到题目分界，建议手动裁剪", vm.state.value.message)
    }

    @Test
    fun `empty lines is unsplittable not crashing`() {
        val vm = SplitPageViewModel(emptyList(), pageWidth = 300)
        assertTrue(vm.state.value.unsplittable)
    }

    @Test
    fun `llm disabled degrades to offline boxes`() {
        val vm = SplitPageViewModel(page(), pageWidth = 300, llmEnabled = false)
        assertTrue(vm.state.value.llmDegraded)
        assertEquals(3, vm.state.value.boxes.size)
    }

    @Test
    fun `llm bad json degrades to offline boxes`() {
        val vm = SplitPageViewModel(page(), pageWidth = 300, llmEnabled = true)
        vm.load(page(), pageWidth = 300, llmEnabled = true, llmJson = "坏 JSON{{{")
        assertTrue(vm.state.value.llmDegraded)
        assertEquals(3, vm.state.value.boxes.size)
    }

    @Test
    fun `llm merge suggestion applied when enabled`() {
        val vm = SplitPageViewModel(page(), pageWidth = 300, llmEnabled = true)
        val json = """{"operations":[{"op":"merge","indices":[0,1]}]}"""
        vm.load(page(), pageWidth = 300, llmEnabled = true, llmJson = json)
        assertFalse(vm.state.value.llmDegraded)
        assertEquals(2, vm.state.value.boxes.size)
    }

    @Test
    fun `gestures forward to editor state`() {
        val vm = SplitPageViewModel(page(), pageWidth = 300)
        vm.select(1)
        vm.moveSelected(10f, 5f)
        // 框 1 初始 (0, 40) → 移动 (10, 5) → (10, 45)
        assertEquals(10f, vm.state.value.boxes[1].left, 1e-6f)
        assertEquals(45f, vm.state.value.boxes[1].top, 1e-6f)
        assertEquals(1, vm.state.value.selected)
    }

    @Test
    fun `delete selected updates state`() {
        val vm = SplitPageViewModel(page(), pageWidth = 300)
        vm.select(1)
        vm.deleteSelected()
        assertEquals(2, vm.state.value.boxes.size)
        assertNull(vm.state.value.selected)
    }

    @Test
    fun `undoAllToSnapshot restores initial boxes`() {
        val vm = SplitPageViewModel(page(), pageWidth = 300)
        val initial = vm.state.value.boxes
        vm.select(0)
        vm.moveSelected(999f, 999f)
        vm.deleteSelected()
        vm.addBox(300f, 200f)
        assertEquals(3, vm.state.value.boxes.size) // 3-1+1
        vm.undoAllToSnapshot()
        assertEquals(initial, vm.state.value.boxes)
        assertNull(vm.state.value.selected)
    }

    @Test
    fun `import progress and summary flow through state`() {
        val vm = SplitPageViewModel(page(), pageWidth = 300)
        vm.onImportStart(total = 3)
        assertTrue(vm.state.value.importing)
        vm.onImportProgress(2, 3)
        assertEquals(2, vm.state.value.importDone)
        vm.onImportFinished(SplitSubmitSummary(ok = 2, failed = listOf(1)))
        assertFalse(vm.state.value.importing)
        assertTrue(vm.state.value.importSummary.contains("2"))
        assertTrue(vm.state.value.importSummary.contains("1"))
    }

    @Test
    fun `two column page raises hint`() {
        val lines = listOf(
            TextLine(y = 0f, height = 15f, x = 10f, width = 100f, text = "1. 左"),
            TextLine(y = 40f, height = 15f, x = 10f, width = 100f, text = "2. 左"),
            TextLine(y = 0f, height = 15f, x = 250f, width = 100f, text = "3. 右"),
            TextLine(y = 40f, height = 15f, x = 250f, width = 100f, text = "4. 右"),
        )
        val vm = SplitPageViewModel(lines, pageWidth = 400)
        assertTrue(vm.state.value.twoColumnHint)
    }
}
