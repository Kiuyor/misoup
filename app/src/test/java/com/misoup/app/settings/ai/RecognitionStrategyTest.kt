package com.misoup.app.settings.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 识别策略三档（模块 10 T02） */
class RecognitionStrategyTest {

    @Test
    fun `三档齐全 - 视觉优先 离线优先 仅离线`() {
        assertEquals(3, RecognitionStrategy.entries.size)
        assertTrue(RecognitionStrategy.entries.map { it.id }.containsAll(
            listOf("vision_first", "offline_first", "offline_only")
        ))
    }

    @Test
    fun `默认策略 - 视觉优先`() {
        assertEquals(RecognitionStrategy.VISION_FIRST, RecognitionStrategy.DEFAULT)
    }

    @Test
    fun `byId 反查 - 未知 id 返回 null`() {
        assertEquals(RecognitionStrategy.OFFLINE_FIRST, RecognitionStrategy.byId("offline_first"))
        assertEquals(RecognitionStrategy.OFFLINE_ONLY, RecognitionStrategy.byId("offline_only"))
        assertNull(RecognitionStrategy.byId("unknown"))
        assertNull(RecognitionStrategy.byId(null))
    }

    @Test
    fun `隐私语义 - 离线优先与仅离线不发图到云端`() {
        assertTrue(RecognitionStrategy.OFFLINE_FIRST.description.contains("不发图"))
        assertTrue(RecognitionStrategy.OFFLINE_ONLY.description.contains("完全离线"))
    }

    @Test
    fun `内存策略存储 - 读写与默认`() {
        val store = MemStrategyStore()
        assertEquals(RecognitionStrategy.DEFAULT, store.load())   // 无记录 → 默认
        store.save(RecognitionStrategy.OFFLINE_ONLY)
        assertEquals(RecognitionStrategy.OFFLINE_ONLY, store.load())
    }

    /** 内存实现（生产 = PrefsStrategySettingsStore） */
    private class MemStrategyStore : StrategySettingsStore {
        private var current: RecognitionStrategy? = null
        override fun load(): RecognitionStrategy = current ?: RecognitionStrategy.DEFAULT
        override fun save(strategy: RecognitionStrategy) { current = strategy }
    }
}
