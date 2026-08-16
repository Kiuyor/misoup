package com.misoup.app.settings.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 缓存清理（模块 10 T05） */
class CacheCleanerLogicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `清理 - 统计文件数与字节数`() {
        val cache = tmp.newFolder("thumbs")
        File(cache, "a.webp").writeBytes(ByteArray(100))
        File(cache, "b.webp").writeBytes(ByteArray(50))
        val sub = File(cache, "nested")
        sub.mkdirs()
        File(sub, "c.bin").writeBytes(ByteArray(25))

        val r = CacheCleanerLogic.clean(listOf(cache))

        assertEquals(1, r.dirsCleaned)
        assertEquals(3, r.filesDeleted)
        assertEquals(175L, r.bytesFreed)
        // 目录重建（保持句柄有效）
        assertTrue(cache.isDirectory)
        assertEquals(0, cache.listFiles()?.size ?: -1)
    }

    @Test
    fun `清理不存在的目录 - 跳过不计`() {
        val r = CacheCleanerLogic.clean(listOf(File(tmp.root, "no-such-dir")))
        assertEquals(0, r.dirsCleaned)
        assertEquals(0, r.filesDeleted)
        assertEquals(0L, r.bytesFreed)
    }

    @Test
    fun `多个目录 - 逐个统计`() {
        val a = tmp.newFolder("thumbs")
        File(a, "x").writeBytes(ByteArray(10))
        val b = tmp.newFolder("ocr")
        File(b, "y").writeBytes(ByteArray(20))
        val r = CacheCleanerLogic.clean(listOf(a, b))
        assertEquals(2, r.dirsCleaned)
        assertEquals(2, r.filesDeleted)
        assertEquals(30L, r.bytesFreed)
    }

    @Test
    fun `formatBytes - B KB MB 边界`() {
        assertEquals("0 B", CacheCleanerLogic.formatBytes(0))
        assertEquals("512 B", CacheCleanerLogic.formatBytes(512))
        assertEquals("1 KB", CacheCleanerLogic.formatBytes(1024))
        assertEquals("100 KB", CacheCleanerLogic.formatBytes(102400))
        assertEquals("1.5 MB", CacheCleanerLogic.formatBytes(1572864))
        assertEquals("0 B", CacheCleanerLogic.formatBytes(-5))
    }

    @Test
    fun `清理后不影响运行 - 目录可继续写`() {
        val cache = tmp.newFolder("thumbs")
        File(cache, "a").writeBytes(ByteArray(10))
        CacheCleanerLogic.clean(listOf(cache))
        File(cache, "new.webp").writeBytes(ByteArray(5))
        assertEquals(1, cache.listFiles()?.size)
    }
}
