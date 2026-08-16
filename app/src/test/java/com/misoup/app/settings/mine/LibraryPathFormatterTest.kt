package com.misoup.app.settings.mine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 库路径展示格式化（模块 10 T05） */
class LibraryPathFormatterTest {

    @Test
    fun `空路径 - 占位文案`() {
        assertEquals(LibraryPathFormatter.DEFAULT_PLACEHOLDER, LibraryPathFormatter.displayPath(null, false))
        assertEquals(LibraryPathFormatter.DEFAULT_PLACEHOLDER, LibraryPathFormatter.displayPath("", true))
        assertEquals(LibraryPathFormatter.DEFAULT_PLACEHOLDER, LibraryPathFormatter.displayPath("   ", false))
    }

    @Test
    fun `自定义目录 - 短路径原样展示`() {
        val path = "/sdcard/MisoupLibrary"
        assertEquals(path, LibraryPathFormatter.displayPath(path, true))
    }

    @Test
    fun `自定义目录 - 长路径截断保留尾部`() {
        val long = "/storage/emulated/0/Android/data/com.misoup.app/files/library/数学/20260816_120000_已知函数f(x)的单调区间问题"
        val out = LibraryPathFormatter.displayPath(long, true)
        assertTrue(out.length <= LibraryPathFormatter.MAX_LENGTH)
        assertTrue(out.startsWith("…"))
        assertFalse(out.contains("（默认）"))
    }

    @Test
    fun `默认目录 - 追加默认标记`() {
        val path = "/data/user/0/com.misoup.app/files/library"
        assertTrue(LibraryPathFormatter.displayPath(path, false).endsWith("（默认）"))
        assertTrue(LibraryPathFormatter.displayPath(path, false).contains(path))
    }

    @Test
    fun `长路径截断 - 保留尾部`() {
        val long = "/storage/emulated/0/Android/data/com.misoup.app/files/library/数学/20260816_120000_已知函数f(x)的单调区间问题"
        val shortened = LibraryPathFormatter.shorten(long)
        assertTrue(shortened.length <= LibraryPathFormatter.MAX_LENGTH)
        assertTrue(shortened.startsWith("…"))
        assertTrue(shortened.endsWith(long.takeLast(5)))
        assertTrue(long.length > shortened.length)
    }

    @Test
    fun `短路径不截断`() {
        val path = "/data/user/0/com.misoup.app/files/library"
        assertEquals(path, LibraryPathFormatter.shorten(path))
    }

    @Test
    fun `截断边界 - max 为 1 时防御`() {
        assertEquals("…", LibraryPathFormatter.shorten("ab", 1))
        assertEquals("a", LibraryPathFormatter.shorten("a", 1))
    }
}
