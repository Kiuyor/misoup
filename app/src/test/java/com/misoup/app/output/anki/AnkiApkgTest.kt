package com.misoup.app.output.anki

import com.misoup.app.makeMeta
import com.misoup.app.output.ExportItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 父任务书 T08 / tasks T08：Anki apkg 占位 — V1.5 排期不阻塞，失败信息明确 */
class AnkiApkgTest {

    private val item = ExportItem(
        meta = makeMeta(id = "anki-test"),
        text = "## 题干\n1+1=?",
        imagePath = null
    )

    @Test
    fun `占位版本号与文件常量`() {
        assertEquals("V1.5", AnkiApkg.PLANNED_VERSION)
        assertEquals("collection.anki2", AnkiApkg.COLLECTION_FILE)
        assertEquals("media", AnkiApkg.MEDIA_DIR)
    }

    @Test
    fun `export 返回明确失败且不产出文件`() {
        val outDir = File.createTempFile("anki-out", "").apply { delete(); mkdirs() }
        try {
            val result = AnkiApkg.export(listOf(item), "数学", outDir)
            assertTrue(result.isFailure)
            val msg = result.exceptionOrNull()!!.message!!
            assertTrue(msg.contains("V1.5"))
            // 不产生半成品
            assertEquals(0, outDir.listFiles()?.size ?: 0)
        } finally {
            outDir.deleteRecursively()
        }
    }
}
