package com.misoup.app.review.fsrs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** FsrsTime ISO-8601 ⇄ epoch 转换：正常 / 边界 / 失败路径 */
class FsrsTimeTest {

    @Test
    fun `带偏移解析-加八时区与Z`() {
        // 2026-08-18T00:00:00+08:00 = 2026-08-17T16:00:00Z（epoch 值经 Python datetime 校验）
        val ms = FsrsTime.toEpochMs("2026-08-18T00:00:00+08:00")
        assertEquals(1786982400000L, ms)
    }

    @Test
    fun `Z后缀解析`() {
        val ms = FsrsTime.toEpochMs("2026-08-18T00:00:00Z")
        assertEquals(1787011200000L, ms)
    }

    @Test
    fun `无偏移按UTC解析`() {
        val ms = FsrsTime.toEpochMs("2026-08-18T00:00:00")
        assertEquals(1787011200000L, ms)
    }

    @Test
    fun `序列化往返一致`() {
        // OffsetDateTime.toString 在整分钟时省略秒（ISO-8601 合法）
        val iso = FsrsTime.toIso(1787011200000L)
        assertEquals("2026-08-18T00:00Z", iso)
        assertEquals(1787011200000L, FsrsTime.toEpochMs(iso))
    }

    @Test
    fun `空值与非法输入返回null不抛异常`() {
        assertNull(FsrsTime.toEpochMs(null))
        assertNull(FsrsTime.toEpochMs(""))
        assertNull(FsrsTime.toEpochMs("  "))
        assertNull(FsrsTime.toEpochMs("不是日期"))
        assertNull(FsrsTime.toEpochMs("2026-13-99T99:99:99Z"))   // 越界字段
        assertNull(FsrsTime.toIso(-1L))                            // 负 epoch
    }

    @Test
    fun `isParsable判定`() {
        assertTrue(FsrsTime.isParsable("2026-08-18T00:00:00+08:00"))
        assertFalse(FsrsTime.isParsable("garbage"))
        assertFalse(FsrsTime.isParsable(null))
    }

    @Test
    fun `toLocalDate按UTC取日期`() {
        // 2026-08-18T16:00:00Z → UTC 日期 2026-08-18
        assertEquals(LocalDate.of(2026, 8, 18), FsrsTime.toLocalDate("2026-08-18T16:00:00Z"))
        // +08:00 的 2026-08-19T01:00 仍是 UTC 的 08-18
        assertEquals(LocalDate.of(2026, 8, 18), FsrsTime.toLocalDate("2026-08-19T01:00:00+08:00"))
        assertNull(FsrsTime.toLocalDate(null))
    }

    @Test
    fun `epoch边界-0与最大值`() {
        assertEquals("1970-01-01T00:00Z", FsrsTime.toIso(0L))
        assertEquals(0L, FsrsTime.toEpochMs("1970-01-01T00:00:00Z"))
        assertTrue(FsrsTime.toIso(Long.MAX_VALUE)?.isNotBlank() == true)   // 不溢出崩
    }
}
