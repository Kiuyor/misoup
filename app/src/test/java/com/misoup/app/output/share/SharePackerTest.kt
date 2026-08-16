package com.misoup.app.output.share

import com.misoup.app.makeMeta
import com.misoup.app.output.ExportItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** 父任务书 T07：分享打包 — 清单结构、sanitize、zip 字节可解包 */
class SharePackerTest {

    // 打包要求图片文件真实存在（读字节），测试用临时文件
    private val tmpImg = File.createTempFile("share-img", ".webp")

    init {
        tmpImg.writeBytes(byteArrayOf(1, 2, 3, 4))
    }

    @After
    fun cleanup() {
        tmpImg.delete()
    }

    private fun item(id: String, original: String = "原图.webp", imagePath: String? = tmpImg.absolutePath) =
        ExportItem(
            meta = makeMeta(id = id, images = com.misoup.app.data.model.Images(original = original)),
            text = "## 题干\n题$id\n\n## 答案\n答$id",
            imagePath = imagePath
        )

    @Test
    fun `sanitize 替换非法字符`() {
        assertEquals("a_b_c", SharePacker.sanitize("a/b\\c"))
        assertEquals("x_y", SharePacker.sanitize("x:y"))
        assertEquals("未命名", SharePacker.sanitize("   "))
    }

    @Test
    fun `多题清单每题含 md 与图`() {
        val files = SharePacker.buildZipEntries(listOf(item("id123456"), item("id654321")))
        assertEquals(4, files.size)
        assertEquals("错题导出/01_id123456/题目.md", files[0].name)
        assertEquals("错题导出/01_id123456/原图.webp", files[1].name)
        assertEquals("错题导出/02_id654321/题目.md", files[2].name)
        assertTrue(files[0].data!!.isNotEmpty())
        assertEquals(tmpImg.absolutePath, files[1].path)
    }

    @Test
    fun `无图条目不生成图片条目`() {
        val files = SharePacker.buildZipEntries(listOf(item("id", imagePath = null)))
        assertEquals(1, files.size)
        assertEquals("错题导出/01_id/题目.md", files[0].name)
    }

    @Test
    fun `zip 写入后可解包且内容为 UTF-8 md`() {
        val files = SharePacker.buildZipEntries(listOf(item("id123456")))
        val out = ByteArrayOutputStream()
        SharePacker.writeZip(files, out)
        val zip = ZipInputStream(ByteArrayInputStream(out.toByteArray()))
        val entries = mutableMapOf<String, String>()
        var e = zip.nextEntry
        while (e != null) {
            entries[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            e = zip.nextEntry
        }
        assertEquals(2, entries.size)
        assertTrue(entries.getValue("错题导出/01_id123456/题目.md").contains("题id123456"))
    }

    @Test
    fun `单题分享 md 无文档头`() {
        val md = SharePacker.singleMarkdown(item("id123456"))
        assertTrue(md.startsWith("## 1."))
        assertTrue(!md.startsWith("# "))
    }

    @Test
    fun `真实图片文件按字节打包`() {
        val files = listOf(
            SharePacker.ShareFile("错题导出/01_a/图.webp", path = tmpImg.absolutePath)
        )
        val out = ByteArrayOutputStream()
        SharePacker.writeZip(files, out)
        val zip = ZipInputStream(ByteArrayInputStream(out.toByteArray()))
        zip.nextEntry
        val bytes = zip.readBytes()
        assertEquals(4, bytes.size)
        assertEquals(1, bytes[0].toInt())
    }
}
