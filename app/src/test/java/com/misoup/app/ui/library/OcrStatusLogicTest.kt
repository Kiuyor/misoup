package com.misoup.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T10 识别状态：pending/failed/done/repair 映射与重试回调 */
class OcrStatusLogicTest {

    @Test
    fun `状态字符串映射`() {
        assertEquals(OcrStatusUi.Pending, OcrStatusLogic.of("pending", needsRepair = false))
        assertEquals(OcrStatusUi.Failed, OcrStatusLogic.of("failed", needsRepair = false))
        assertEquals(OcrStatusUi.Done, OcrStatusLogic.of("done", needsRepair = false))
        assertEquals(OcrStatusUi.Repair, OcrStatusLogic.of("pending", needsRepair = true))
    }

    @Test
    fun `未知或空状态-按 Done 处理不打扰`() {
        assertEquals(OcrStatusUi.Done, OcrStatusLogic.of(null, needsRepair = false))
        assertEquals(OcrStatusUi.Done, OcrStatusLogic.of("", needsRepair = false))
        assertEquals(OcrStatusUi.Done, OcrStatusLogic.of("processing", needsRepair = false))
    }

    @Test
    fun `损坏条目优先显示待修复`() {
        assertEquals(OcrStatusUi.Repair, OcrStatusLogic.of("done", needsRepair = true))
        assertEquals(OcrStatusUi.Repair, OcrStatusLogic.of("failed", needsRepair = true))
    }

    @Test
    fun `文案全中文`() {
        assertEquals("识别中…", OcrStatusLogic.label(OcrStatusUi.Pending))
        assertEquals("识别失败", OcrStatusLogic.label(OcrStatusUi.Failed))
        assertEquals("待修复", OcrStatusLogic.label(OcrStatusUi.Repair))
        assertEquals("", OcrStatusLogic.label(OcrStatusUi.Done))
    }

    @Test
    fun `状态条显示判定-done 不显示`() {
        assertTrue(OcrStatusLogic.needsStatusBar(OcrStatusUi.Pending))
        assertTrue(OcrStatusLogic.needsStatusBar(OcrStatusUi.Failed))
        assertTrue(OcrStatusLogic.needsStatusBar(OcrStatusUi.Repair))
        assertFalse(OcrStatusLogic.needsStatusBar(OcrStatusUi.Done))
    }

    @Test
    fun `重试回调-只对 failed 触发且带正确 id`() {
        val retried = mutableListOf<String>()
        val handler = OcrRetryHandler { id -> retried += id }
        handler.retry("abc-123")
        assertEquals(listOf("abc-123"), retried)
    }
}
