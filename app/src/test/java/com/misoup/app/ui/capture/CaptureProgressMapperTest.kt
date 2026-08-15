package com.misoup.app.ui.capture

import com.misoup.app.recognition.coordinator.BatchItem
import com.misoup.app.recognition.coordinator.CaptureSource
import com.misoup.app.recognition.coordinator.StorePhase
import com.misoup.app.recognition.queue.RecognitionPhase
import com.misoup.app.recognition.queue.RecognitionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T10 进度聚合（纯逻辑）：批次收录状态 + 识别队列状态 → 逐张徽标
 */
class CaptureProgressMapperTest {

    private fun item(
        index: Int,
        storePhase: StorePhase,
        metaId: String? = "id$index"
    ) = BatchItem(CaptureSource("content://a/$index"), storePhase, metaId)

    // ---------- 收录阶段 ----------

    @Test
    fun `收录中-徽标为收录中`() {
        val batch = listOf(item(0, StorePhase.STORING))
        val ui = CaptureProgressMapper.map(batch, emptyMap())
        assertEquals(CaptureProgressMapper.ItemState.STORING, ui[0].state)
        assertEquals("收录中…", ui[0].state.label)
    }

    @Test
    fun `收录失败-徽标为收录失败-不参与识别阶段`() {
        val batch = listOf(item(0, StorePhase.FAILED))
        // 即使队列里有该条目的失败状态，也以收录失败为准
        val ui = CaptureProgressMapper.map(
            batch,
            mapOf("id0" to RecognitionStatus(RecognitionPhase.DONE))
        )
        assertEquals(CaptureProgressMapper.ItemState.STORE_FAILED, ui[0].state)
        assertEquals("收录失败", ui[0].state.label)
    }

    // ---------- 识别阶段（已收录后） ----------

    @Test
    fun `已收录-队列pending-徽标识别中`() {
        val batch = listOf(item(0, StorePhase.STORED))
        val ui = CaptureProgressMapper.map(
            batch,
            mapOf("id0" to RecognitionStatus(RecognitionPhase.PENDING))
        )
        assertEquals(CaptureProgressMapper.ItemState.RECOGNIZING, ui[0].state)
        assertEquals("识别中…", ui[0].state.label)
    }

    @Test
    fun `已收录-队列done-徽标已收录`() {
        val batch = listOf(item(0, StorePhase.STORED))
        val ui = CaptureProgressMapper.map(
            batch,
            mapOf("id0" to RecognitionStatus(RecognitionPhase.DONE))
        )
        assertEquals(CaptureProgressMapper.ItemState.DONE, ui[0].state)
        assertEquals("已收录", ui[0].state.label)
    }

    @Test
    fun `已收录-队列failed-徽标识别失败-带错误信息与metaId`() {
        val batch = listOf(item(0, StorePhase.STORED))
        val ui = CaptureProgressMapper.map(
            batch,
            mapOf("id0" to RecognitionStatus(RecognitionPhase.FAILED, error = "网络不可用"))
        )
        assertEquals(CaptureProgressMapper.ItemState.RECOG_FAILED, ui[0].state)
        assertEquals("识别失败", ui[0].state.label)
        assertEquals("网络不可用", ui[0].error)
        assertEquals("id0", ui[0].metaId)
    }

    @Test
    fun `已收录-队列暂无记录-按识别中处理（入队窗口期）`() {
        val batch = listOf(item(0, StorePhase.STORED))
        val ui = CaptureProgressMapper.map(batch, emptyMap())
        assertEquals(CaptureProgressMapper.ItemState.RECOGNIZING, ui[0].state)
    }

    // ---------- 多张混合 + 顺序 ----------

    @Test
    fun `混合批次-状态与序号一一对应`() {
        val batch = listOf(
            item(0, StorePhase.STORED, metaId = "a"),
            item(1, StorePhase.FAILED),
            item(2, StorePhase.STORING),
            item(3, StorePhase.STORED, metaId = "d")
        )
        val ui = CaptureProgressMapper.map(
            batch,
            mapOf(
                "a" to RecognitionStatus(RecognitionPhase.PENDING),
                "d" to RecognitionStatus(RecognitionPhase.DONE)
            )
        )
        assertEquals(
            listOf(
                CaptureProgressMapper.ItemState.RECOGNIZING,
                CaptureProgressMapper.ItemState.STORE_FAILED,
                CaptureProgressMapper.ItemState.STORING,
                CaptureProgressMapper.ItemState.DONE
            ),
            ui.map { it.state }
        )
        assertEquals(listOf(0, 1, 2, 3), ui.map { it.index })
        assertEquals(listOf("content://a/0", "content://a/1", "content://a/2", "content://a/3"), ui.map { it.sourceUri })
    }

    @Test
    fun `空批次-返回空列表`() {
        assertEquals(emptyList<CaptureProgressMapper.ItemUi>(), CaptureProgressMapper.map(emptyList(), emptyMap()))
    }

    // ---------- 汇总条 ----------

    @Test
    fun `汇总条文案-已收录3of5`() {
        assertEquals("已收录 3/5", CaptureProgressMapper.summaryLabel(3, 5))
        assertEquals("已收录 0/5", CaptureProgressMapper.summaryLabel(0, 5))
    }
}
