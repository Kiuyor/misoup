package com.misoup.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 远端 PROPFIND 结果 → 条目指纹（模块 09 T03 差集输入侧） */
class RemoteScannerTest {

    @Test
    fun `递归列取结果 - 按条目聚合为 max-mtime + 总size`() {
        val listing = listOf(
            DavFileInfo("数学/A/meta.json", false, 1000L, 50L),
            DavFileInfo("数学/A/原图.webp", false, 2000L, 100L),
            DavFileInfo("数学/A/文本.md", false, 1500L, 30L),
            DavFileInfo("物理/B/meta.json", false, 3000L, 40L)
        )
        val fps = RemoteScanner.entryFingerprints(listing)
        assertEquals(2, fps.size)
        assertEquals(2000L, fps["数学/A"]!!.mtime)   // 目录内最大文件 mtime
        assertEquals(180L, fps["数学/A"]!!.size)      // 全部文件 size 之和
        assertEquals(3000L, fps["物理/B"]!!.mtime)
    }

    @Test
    fun `缺 meta 文件的目录 - 不算条目`() {
        val listing = listOf(
            DavFileInfo("数学/A/原图.webp", false, 1000L, 10L),   // 无 meta.json
            DavFileInfo("数学/A.conflict-1/文本.md", false, 900L, 5L)
        )
        assertTrue(RemoteScanner.entryFingerprints(listing).isEmpty())
    }

    @Test
    fun `根级状态文件与两层以下杂项 - 忽略`() {
        val listing = listOf(
            DavFileInfo(".sync-state.json", false, 500L, 200L),
            DavFileInfo("数学/A/meta.json", false, 1000L, 50L),
            DavFileInfo("数学/A/子目录/extra.bin", false, 1200L, 7L)
        )
        val fps = RemoteScanner.entryFingerprints(listing)
        // 子目录深层文件也聚合进条目（目录结构原样镜像，含变式子目录等）
        assertEquals(1, fps.size)
        assertEquals(57L, fps["数学/A"]!!.size)
    }

    @Test
    fun `空列表 - 空指纹`() {
        assertTrue(RemoteScanner.entryFingerprints(emptyList()).isEmpty())
    }
}

/** 同步状态序列化往返（模块 09 T03） */
class SyncStateCodecTest {

    @Test
    fun `序列化解析往返 - 数据一致`() {
        val state = SyncState(
            lastSyncAt = 123456L,
            entries = mapOf(
                "数学/A" to SyncStateEntry(1000L, 200L),
                "物理/B" to SyncStateEntry(2000L, 300L)
            )
        )
        val text = SyncStateCodec.serialize(state)
        val parsed = SyncStateCodec.parse(text).getOrThrow()
        assertEquals(state, parsed)
    }

    @Test
    fun `解析失败 - failure（按首次同步处理）`() {
        assertTrue(SyncStateCodec.parse("not-json{{{").isFailure)
        assertTrue(SyncStateCodec.parse("").isFailure)
    }

    @Test
    fun `未知字段忽略 - 向前兼容`() {
        val parsed = SyncStateCodec.parse(
            """{"schemaVersion":1,"lastSyncAt":1,"futureField":true,"entries":{"数学/A":{"mtime":5,"size":6,"x":1}}}"""
        ).getOrThrow()
        assertEquals(1L, parsed.lastSyncAt)
        assertEquals(5L, parsed.entries["数学/A"]!!.mtime)
        assertEquals(6L, parsed.entries["数学/A"]!!.size)
    }
}
