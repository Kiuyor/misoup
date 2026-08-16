package com.misoup.app.splitting.candidates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxEditorTest {

    private val boxes = listOf(
        BoundingBox(0f, 0f, 100f, 100f),
        BoundingBox(150f, 0f, 100f, 100f),
        BoundingBox(300f, 0f, 100f, 100f),
    )

    @Test
    fun `select and move selected box`() {
        val e = BoxEditor(boxes)
        e.select(1)
        e.moveSelected(10f, 5f)
        assertEquals(BoundingBox(160f, 5f, 100f, 100f), e.boxes[1])
        assertEquals(1, e.selected)
    }

    @Test
    fun `move without selection is noop`() {
        val e = BoxEditor(boxes)
        e.moveSelected(10f, 10f)
        assertEquals(boxes, e.boxes)
    }

    @Test
    fun `add box appends and selects it`() {
        val e = BoxEditor(boxes)
        e.addBox(pageWidth = 500f, pageHeight = 400f)
        assertEquals(4, e.boxes.size)
        assertEquals(3, e.selected)
        // 新框位于页面中心
        val added = e.boxes[3]
        assertEquals(200f, added.left, 1e-6f)
        assertEquals(150f, added.top, 1e-6f)
    }

    @Test
    fun `delete selected removes it`() {
        val e = BoxEditor(boxes)
        e.select(1)
        e.deleteSelected()
        assertEquals(2, e.boxes.size)
        assertEquals(boxes[0], e.boxes[0])
        assertEquals(boxes[2], e.boxes[1])
        assertNull(e.selected)
    }

    @Test
    fun `delete without selection is noop`() {
        val e = BoxEditor(boxes)
        e.deleteSelected()
        assertEquals(boxes, e.boxes)
    }

    @Test
    fun `merge selected with overlapping box`() {
        // 0 与 1 有交集（0..100 x, 150..250 x → 无交集！改构造重叠框）
        val overlapping = listOf(
            BoundingBox(0f, 0f, 100f, 100f),
            BoundingBox(50f, 0f, 100f, 100f),   // 与 0 重叠
            BoundingBox(300f, 0f, 100f, 100f),
        )
        val e = BoxEditor(overlapping)
        e.select(0)
        val mergedIdx = e.mergeSelected()
        assertEquals(0, mergedIdx)
        // union(0,1) = 0..150 x 0..100 y
        assertEquals(BoundingBox(0f, 0f, 150f, 100f), e.boxes[0])
        assertEquals(2, e.boxes.size)
        assertEquals(0, e.selected)
    }

    @Test
    fun `merge without overlap is noop`() {
        val e = BoxEditor(boxes)
        e.select(0)
        assertNull(e.mergeSelected())
        assertEquals(boxes, e.boxes)
    }

    @Test
    fun `undoAll restores initial snapshot`() {
        val e = BoxEditor(boxes)
        e.select(0)
        e.moveSelected(999f, 999f)
        e.deleteSelected()
        e.addBox(500f, 400f)
        e.undoAll()
        assertEquals(boxes, e.boxes)
        assertNull(e.selected)
    }

    @Test
    fun `pickMergeTarget picks max intersection`() {
        val overlapping = listOf(
            BoundingBox(0f, 0f, 100f, 100f),
            BoundingBox(10f, 0f, 100f, 100f),   // 交集大
            BoundingBox(90f, 0f, 100f, 100f),   // 交集小
        )
        assertEquals(1, BoxEditor.pickMergeTarget(overlapping, 0))
    }

    @Test
    fun `pickMergeTarget null without overlap or invalid index`() {
        assertNull(BoxEditor.pickMergeTarget(boxes, 0))
        assertNull(BoxEditor.pickMergeTarget(boxes, 99))
    }

    @Test
    fun `select out of range clears selection`() {
        val e = BoxEditor(boxes)
        e.select(0)
        e.select(99)
        assertNull(e.selected)
    }

    @Test
    fun `editor copies initial list on construction`() {
        val source = mutableListOf(BoundingBox(0f, 0f, 10f, 10f))
        val e = BoxEditor(source)
        source[0] = BoundingBox(99f, 99f, 10f, 10f)
        assertEquals(BoundingBox(0f, 0f, 10f, 10f), e.boxes[0])
        assertTrue(e.boxes !== source)
    }

    @Test
    fun `pickAt returns topmost box containing point`() {
        val overlapping = listOf(
            BoundingBox(0f, 0f, 100f, 100f),
            BoundingBox(50f, 50f, 100f, 100f),   // 与 0 重叠，列表靠后 → 优先
        )
        val e = BoxEditor(overlapping)
        assertEquals(1, e.pickAt(60f, 60f))
        assertEquals(0, e.pickAt(10f, 10f))
    }

    @Test
    fun `pickAt null outside all boxes`() {
        val e = BoxEditor(boxes)
        assertNull(e.pickAt(999f, 999f))
        assertNull(e.pickAt(-1f, -1f))
    }
}
