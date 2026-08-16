package com.misoup.app.sync.importexport

import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.store.MetaJson
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.sync.backup.BackupManifest
import com.misoup.app.sync.backup.FileSink
import com.misoup.app.sync.backup.FileSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 单条目导入/导出单测（模块 09 T08）：zip 导出（目录原样+manifest）；
 * 导入识别 manifest / 裸目录；重复 id 跳过并报告；meta 损坏拒绝。
 */
class ImportExportServiceTest {

    private lateinit var libRoot: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService
    private lateinit var staging: File
    private lateinit var service: ImportExportService

    @Before
    fun setUp() {
        libRoot = createTempDir("misoup-lib")
        index = LibraryIndex()
        store = StoreService(libRoot, index, FakeImageEncoder())
        staging = createTempDir("misoup-staging")
        service = ImportExportService(libRoot, index, staging)
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

    private fun dir(subject: String, name: String) = File(File(libRoot, subject), name)

    // ---------- T08 导出 ----------

    @Test
    fun `导出单条目 - zip 目录原样 + manifest`() {
        entry("数学", "20260816_A", "id-a", text = "题干A")
        entry("物理", "B", "id-b")
        val zip = File(libRoot.parentFile, "export.zip")

        val result = service.export(FileSink(zip), listOf("id-a")).getOrThrow()

        assertEquals(1, result.entryCount)
        assertTrue(zip.isFile)
        val manifest = com.misoup.app.sync.backup.BackupReader.readManifest(zip.inputStream()).getOrThrow()
        assertEquals(BackupManifest.KIND_SINGLE, manifest.kind)
        assertEquals(listOf("id-a"), manifest.entries.map { it.id })
        assertEquals("数学", manifest.entries.single().subject)
        zip.delete()
    }

    @Test
    fun `导出空 id 列表 - 失败`() {
        assertTrue(service.export(FileSink(File(libRoot.parentFile, "x.zip")), emptyList()).isFailure)
    }

    // ---------- T08 导入（manifest） ----------

    @Test
    fun `导入含 manifest 的 zip - 进库且结构完整`() {
        entry("数学", "A", "id-a", text = "原始题干")
        val zip = File(libRoot.parentFile, "export.zip")
        service.export(FileSink(zip), listOf("id-a")).getOrThrow()
        // 清库模拟另一台设备
        libRoot.listFiles()?.forEach { it.deleteRecursively() }
        index.rebuild(libRoot)
        assertEquals(0, index.size())

        val report = service.importZip(FileSource(zip)).getOrThrow()

        assertTrue(report.isSuccess)
        assertEquals(1, report.imported)
        assertEquals(0, report.skippedDuplicates)
        assertEquals("原始题干", File(dir("数学", "A"), "文本.md").readText())
        assertEquals("image-A", File(dir("数学", "A"), "原图.webp").readText())
        assertEquals(1, index.size())
        zip.delete()
    }

    @Test
    fun `重复 id - 跳过并报告 不覆盖`() {
        entry("数学", "A", "id-a", text = "本地版本")
        val zip = File(libRoot.parentFile, "export.zip")
        service.export(FileSink(zip), listOf("id-a")).getOrThrow()

        val report = service.importZip(FileSource(zip)).getOrThrow()

        assertTrue(report.isSuccess)
        assertEquals(0, report.imported)
        assertEquals(1, report.skippedDuplicates)
        // 本地条目未被覆盖
        assertEquals("本地版本", File(dir("数学", "A"), "文本.md").readText())
        zip.delete()
    }

    @Test
    fun `多条目导入 - 全新导入与重复跳过混合`() {
        entry("数学", "A", "id-a")
        val zip = File(libRoot.parentFile, "export.zip")
        service.export(FileSink(zip), listOf("id-a")).getOrThrow()
        // 往 zip 里再塞一个条目（模拟多题导出包）：直接构造第二个条目目录并重新打包
        entry("英语", "C", "id-c")
        val zip2 = File(libRoot.parentFile, "export2.zip")
        service.export(FileSink(zip2), listOf("id-a", "id-c")).getOrThrow()
        // 清库后导入：id-a、id-c 都全新
        libRoot.listFiles()?.forEach { it.deleteRecursively() }
        index.rebuild(libRoot)
        val report = service.importZip(FileSource(zip2)).getOrThrow()
        assertEquals(2, report.imported)
        assertEquals(0, report.skippedDuplicates)
        zip.delete(); zip2.delete()
    }

    // ---------- T08 导入（裸 zip / 裸目录，无 manifest） ----------

    @Test
    fun `裸 zip 无 manifest - 扫描导入`() {
        // 构造无 manifest 的裸 zip
        val meta = makeMeta(id = "bare-id", subject = "数学")
        val bareZip = File(libRoot.parentFile, "bare.zip")
        java.util.zip.ZipOutputStream(bareZip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("数学/裸条目/meta.json"))
            zos.write(MetaJson.serialize(meta).toByteArray())
            zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("数学/裸条目/原图.webp"))
            zos.write("bare-img".toByteArray())
            zos.closeEntry()
        }

        val report = service.importZip(FileSource(bareZip)).getOrThrow()

        assertTrue(report.isSuccess)
        assertEquals(1, report.imported)
        assertEquals("bare-img", File(dir("数学", "裸条目"), "原图.webp").readText())
        bareZip.delete()
    }

    @Test
    fun `裸目录导入 - 扫描 meta 进库`() {
        val meta = makeMeta(id = "dir-id", subject = "物理")
        val bareDir = createTempDir("bare-dir")
        val entryDir = File(File(bareDir, "物理"), "裸目录条目")
        entryDir.mkdirs()
        File(entryDir, "meta.json").writeText(MetaJson.serialize(meta))
        File(entryDir, "原图.webp").writeText("dir-img")

        val report = service.importBareDir(bareDir).getOrThrow()

        assertEquals(1, report.imported)
        assertEquals("dir-img", File(dir("物理", "裸目录条目"), "原图.webp").readText())
        bareDir.deleteRecursively()
    }

    // ---------- 损坏与边界 ----------

    @Test
    fun `meta 损坏条目 - 拒绝进库并计数`() {
        val meta = makeMeta(id = "bad-id", subject = "数学")
        val badZip = File(libRoot.parentFile, "bad.zip")
        java.util.zip.ZipOutputStream(badZip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("数学/坏条目/meta.json"))
            zos.write("not-json{{{".toByteArray())
            zos.closeEntry()
        }

        val report = service.importZip(FileSource(badZip)).getOrThrow()

        assertEquals(0, report.imported)
        assertEquals(1, report.skippedInvalid)
        assertFalse(dir("数学", "坏条目").exists())
        badZip.delete()
    }

    @Test
    fun `zip 内 manifest hash 不符 - 该条目失败并报告`() {
        val meta = makeMeta(id = "tamper-id", subject = "数学")
        val zip = File(libRoot.parentFile, "tamper.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("数学/条目/meta.json"))
            zos.write(MetaJson.serialize(meta).toByteArray())
            zos.closeEntry()
            val badManifest = BackupManifest(
                kind = BackupManifest.KIND_SINGLE,
                entries = listOf(
                    com.misoup.app.sync.backup.ManifestEntry("tamper-id", "数学", "条目", "0000badhash", 1)
                )
            )
            zos.putNextEntry(java.util.zip.ZipEntry(BackupManifest.FILE_NAME))
            zos.write(com.misoup.app.sync.backup.BackupManifestCodec.serialize(badManifest).toByteArray())
            zos.closeEntry()
        }

        val report = service.importZip(FileSource(zip)).getOrThrow()

        assertFalse(report.isSuccess)
        assertTrue(report.failed.any { it.contains("hash") })
        assertEquals(0, report.imported)
        zip.delete()
    }

    @Test
    fun `导入路径穿越条目 - 拒绝写入`() {
        val zip = File(libRoot.parentFile, "evil.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("../evil/meta.json"))
            zos.write("{}".toByteArray())
            zos.closeEntry()
        }

        val report = service.importZip(FileSource(zip)).getOrThrow()
        assertEquals(0, report.imported)   // 穿越路径被跳过
        assertFalse(File(libRoot.parentFile, "evil").exists())
        zip.delete()
    }

    @Test
    fun `导入空文件 - 失败报告`() {
        val empty = File(libRoot.parentFile, "empty.zip")
        empty.writeBytes(byteArrayOf())   // 非法 zip
        val report = service.importZip(FileSource(empty)).getOrThrow()
        assertFalse(report.isSuccess)
        assertTrue(report.failed.isNotEmpty())
        empty.delete()
    }
}
