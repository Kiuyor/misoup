package com.misoup.app.sync.backup

import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.store.MetaJson
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 恢复服务单测（模块 09 T07）：备份 → 清库 → 恢复 hash 校验通过；
 * 同名不同 hash 保留两份（.imported-<ts>）；同名同 hash 跳过。
 */
class RestoreServiceTest {

    private lateinit var libRoot: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService
    private lateinit var staging: File
    private lateinit var service: RestoreService

    @Before
    fun setUp() {
        libRoot = createTempDir("misoup-lib")
        index = LibraryIndex()
        store = StoreService(libRoot, index, FakeImageEncoder())
        staging = createTempDir("misoup-staging")
        service = RestoreService(libRoot, index, staging)
    }

    @After
    fun tearDown() {
        libRoot.deleteRecursively()
        staging.deleteRecursively()
    }

    private fun entry(subject: String, dirName: String, id: String, text: String = "题干") {
        val meta = makeMeta(id = id, subject = subject)
        val dir = File(File(libRoot, subject), dirName)
        dir.mkdirs()
        File(dir, MisoupConfigConst.META_FILE_NAME).writeText(MetaJson.serialize(meta))
        File(dir, MisoupConfigConst.ORIGINAL_IMAGE_NAME).writeText("image-$dirName")
        File(dir, MisoupConfigConst.TEXT_FILE_NAME).writeText(text)
        index.upsert(meta, null, dirName = dirName)
    }

    private fun backupZip(): File {
        val zip = File(libRoot.parentFile, "backup.zip")
        BackupWriter(libRoot, index).write(FileSink(zip)).getOrThrow()
        return zip
    }

    private fun dir(subject: String, name: String) = File(File(libRoot, subject), name)

    @Test
    fun `备份-清库-恢复 - 数据完整 hash 校验通过`() {
        entry("数学", "20260816_A", "id-a", text = "题干A")
        entry("物理", "20260816_B", "id-b", text = "题干B")
        val zip = backupZip()

        // 清库（模拟用户清空）
        libRoot.listFiles()?.forEach { it.deleteRecursively() }
        index.rebuild(libRoot)
        assertEquals(0, index.size())

        // 恢复
        val report = service.restore(FileSource(zip)).getOrThrow()

        assertTrue(report.isSuccess)
        assertEquals(2, report.restored)
        assertEquals(0, report.skippedSame)
        // 数据与缩略图级文件完整
        assertEquals("题干A", File(dir("数学", "20260816_A"), "文本.md").readText())
        assertEquals("image-20260816_B", File(dir("物理", "20260816_B"), "原图.webp").readText())
        // 索引已并入
        assertEquals(2, index.size())
        zip.delete()
    }

    @Test
    fun `同名同 hash - 跳过不重复导入`() {
        entry("数学", "A", "id-a")
        val zip = backupZip()

        val report = service.restore(FileSource(zip)).getOrThrow()

        assertEquals(0, report.restored)
        assertEquals(1, report.skippedSame)
        assertEquals(1, index.size())
        assertFalse(dir("数学", "A.imported-").exists())
        zip.delete()
    }

    @Test
    fun `同名不同 hash - 保留两份 旧条目不动`() {
        entry("数学", "A", "id-a", text = "备份版本")
        val zip = backupZip()
        // 备份后本地又改了（hash 变化）→ 恢复时与备份内容不同
        File(dir("数学", "A"), MisoupConfigConst.TEXT_FILE_NAME).writeText("本地新版")

        val report = service.restore(FileSource(zip), now = 123456L).getOrThrow()

        assertEquals(1, report.importedAsConflict)
        assertEquals(0, report.restored)
        // 本地旧条目保持原名（本地新版不动）
        assertEquals("本地新版", File(dir("数学", "A"), "文本.md").readText())
        // 备份版本以 .imported-<ts> 落盘（两份都在）
        val imported = dir("数学", "A.imported-123456")
        assertTrue(imported.isDirectory)
        assertEquals("备份版本", File(imported, "文本.md").readText())
        zip.delete()
    }

    @Test
    fun `同 id 不同目录名不同 hash - 按 id 识别冲突保留两份`() {
        entry("数学", "old-name", "id-a", text = "本地")
        val zip = backupZip()
        // 本地重命名目录并修改（同 id，目录名不同，hash 不同）
        val old = dir("数学", "old-name")
        val renamed = File(libRoot, "数学/newname")
        old.renameTo(renamed)
        File(renamed, "文本.md").writeText("本地改过")
        index.rebuild(libRoot)

        val report = service.restore(FileSource(zip), now = 999L).getOrThrow()

        assertEquals(1, report.importedAsConflict)
        assertTrue(dir("数学", "old-name.imported-999").isDirectory)
        assertTrue(dir("数学", "newname").isDirectory)
        zip.delete()
    }

    @Test
    fun `zip 内容被篡改 - 恢复失败 不落任何条目`() {
        entry("数学", "A", "id-a")
        val zip = backupZip()
        // 篡改 zip 内部一个字节（DEFLATE 数据区 → 解压失败或内容不符 → 校验必失败）
        val bytes = zip.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0xFF).toByte()
        val badZip = File(libRoot.parentFile, "bad.zip")
        badZip.writeBytes(bytes)

        val r = service.restore(FileSource(badZip))
        assertTrue("篡改 zip 必须校验失败", r.isFailure)
        assertEquals(1, index.size())   // 库未被污染
        badZip.delete(); zip.delete()
    }

    @Test
    fun `manifest 条目 hash 不符 - 校验失败并列出损坏条目`() {
        entry("数学", "A", "id-a")
        val zip = backupZip()
        // 解包后篡改 staging 外内容不可行；改走：构造 hash 错误的 manifest（伪造备份）
        val fakeZip = File(libRoot.parentFile, "fake.zip")
        java.util.zip.ZipOutputStream(FileOutputStream(fakeZip)).use { zos ->
            val meta = makeMeta(id = "evil", subject = "数学")
            zos.putNextEntry(java.util.zip.ZipEntry("数学/evil/meta.json"))
            zos.write(MetaJson.serialize(meta).toByteArray())
            zos.closeEntry()
            val badManifest = BackupManifest(
                kind = BackupManifest.KIND_FULL,
                entries = listOf(ManifestEntry("evil", "数学", "evil", "0000deadbeef", 1))
            )
            zos.putNextEntry(java.util.zip.ZipEntry(BackupManifest.FILE_NAME))
            zos.write(BackupManifestCodec.serialize(badManifest).toByteArray())
            zos.closeEntry()
        }

        val r = service.restore(FileSource(fakeZip))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("evil"))
        assertFalse(dir("数学", "evil").exists())   // 校验失败不落库
        fakeZip.delete(); zip.delete()
    }

    @Test
    fun `空库恢复备份 - 校验通过可重复恢复`() {
        entry("数学", "A", "id-a")
        val zip = backupZip()
        // 第一次恢复（库非空，条目已存在 → 跳过）
        val r1 = service.restore(FileSource(zip)).getOrThrow()
        assertEquals(1, r1.skippedSame)
        // 清库后再次恢复
        libRoot.listFiles()?.forEach { it.deleteRecursively() }
        index.rebuild(libRoot)
        val r2 = service.restore(FileSource(zip)).getOrThrow()
        assertEquals(1, r2.restored)
        zip.delete()
    }
}
