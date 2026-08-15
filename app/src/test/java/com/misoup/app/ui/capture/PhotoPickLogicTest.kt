package com.misoup.app.ui.capture

import com.misoup.app.recognition.coordinator.CaptureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T01 Photo Picker 纯逻辑：单张=单题/多选=批量 语义、取消处理、上限截断
 */
class PhotoPickLogicTest {

    @Test
    fun `maxItems-单张1-批量50`() {
        assertEquals(1, PhotoPickLogic.maxItems(single = true))
        assertEquals(50, PhotoPickLogic.maxItems(single = false))
        assertEquals(50, PhotoPickLogic.MAX_SELECT)
    }

    @Test
    fun `toSources-多张全收-顺序保持`() {
        val sources = PhotoPickLogic.toSources(
            listOf("content://a/1", "content://a/2", "content://a/3")
        )
        assertEquals(3, sources.size)
        assertEquals(
            listOf("content://a/1", "content://a/2", "content://a/3"),
            sources.map { it.uri }
        )
    }

    @Test
    fun `toSources-取消null与空列表-返回空-不触发采集`() {
        assertTrue(PhotoPickLogic.toSources(null).isEmpty())
        assertTrue(PhotoPickLogic.toSources(emptyList()).isEmpty())
    }

    @Test
    fun `toSingleSource-只取第一张（单张=单题语义）`() {
        val source = PhotoPickLogic.toSingleSource(listOf("content://a/1", "content://a/2"))
        assertEquals(CaptureSource("content://a/1"), source)
    }

    @Test
    fun `toSingleSource-取消返回null`() {
        assertNull(PhotoPickLogic.toSingleSource(null))
        assertNull(PhotoPickLogic.toSingleSource(emptyList()))
    }

    @Test
    fun `toBatchSources-超过50截断防御`() {
        val many = (1..60).map { "content://a/$it" }
        val sources = PhotoPickLogic.toBatchSources(many)
        assertEquals(50, sources.size)
        assertEquals("content://a/1", sources.first().uri)
        assertEquals("content://a/50", sources.last().uri)
    }

    @Test
    fun `批量-50张以内全收`() {
        val many = (1..50).map { "content://a/$it" }
        assertEquals(50, PhotoPickLogic.toBatchSources(many).size)
    }
}
