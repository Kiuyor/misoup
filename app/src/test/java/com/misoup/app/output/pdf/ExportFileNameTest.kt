package com.misoup.app.output.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 导出文件名生成（SAF 默认名/分享名共用） */
class ExportFileNameTest {

    @Test
    fun `格式为 错题汤_科目_时间戳_扩展名`() {
        val now = LocalDateTime.of(2026, 8, 16, 14, 30, 5)
        assertEquals("错题汤_数学_20260816_143005.md", ExportFileName.suggest("数学", "md", now))
        assertEquals("错题汤_物理_20260816_143005.csv", ExportFileName.suggest("物理", "csv", now))
        assertEquals("错题汤_化学_20260816_143005.pdf", ExportFileName.suggest("化学", "pdf", now))
    }

    @Test
    fun `科目含非法字符被净化`() {
        val now = LocalDateTime.of(2026, 8, 16, 14, 30, 5)
        assertEquals("错题汤_数_学_20260816_143005.md", ExportFileName.suggest("数/学", "md", now))
    }

    @Test
    fun `空白科目回退未分类`() {
        val now = LocalDateTime.of(2026, 8, 16, 14, 30, 5)
        assertEquals("错题汤_未分类_20260816_143005.md", ExportFileName.suggest("  ", "md", now))
    }

    @Test
    fun `文件名非空且含点扩展名`() {
        val name = ExportFileName.suggest("数学", "md")
        assertTrue(name.endsWith(".md"))
        assertTrue(name.isNotBlank())
    }
}
