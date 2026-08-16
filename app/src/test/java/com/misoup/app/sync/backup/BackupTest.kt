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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/** 测试工具：临时库 + 条目构造 */
private class TestLib {
    val root = createTempDir("misoup-lib")
    val index = LibraryIndex()
    val store = StoreService(root, index, FakeImageEncoder())

    fun entry(subject: String, dirName: String, id: String, text: String = "题干", extra: Map<String, String> = emptyMap()) {
        val meta = makeMeta(id = id, subject = subject)
        val dir = File(File(root, subject), dirName)
        dir.mkdirs()
        File(dir, MisoupConfigConst.META_FILE_NAME).writeText(MetaJson.serialize(meta))
        File(dir, MisoupConfigConst.ORIGINAL_IMAGE_NAME).writeText("image-$dirName")
        File(dir, MisoupConfigConst.TEXT_FILE_NAME).writeText(text)
        extra.forEach { (n, c) -> File(dir, n).apply { parentFile?.mkdirs(); writeText(c) } }
        index.upsert(meta, null, dirName = dirName)
    }

    fun cleanup() = root.deleteRecursively()
}

/** 条目 hash（模块 09 T06/T07/T08 校验基础） */
class EntryHasherTest {

    @Test
    fun `相同内容 - hash 一致且稳定`() {
        val a = createTempDir("h-a"); val b = createTempDir("h-b")
        File(a, "meta.json").writeText("x"); File(a, "原图.webp").writeText("img")
        File(b, "meta.json").writeText("x"); File(b, "原图.webp").writeText("img")
        assertEquals(EntryHasher.hashOf(a), EntryHasher.hashOf(b))
        assertEquals(64, EntryHasher.hashOf(a).length)   // SHA-256 hex
        a.deleteRecursively(); b.deleteRecursively()
    }

    @Test
    fun `内容不同 - hash 不同`() {
        val a = createTempDir("h-a"); val b = createTempDir("h-b")
        File(a, "meta.json").writeText("x")
        File(b, "meta.json").writeText("y")
        assertNotEquals(EntryHasher.hashOf(a), EntryHasher.hashOf(b))
        a.deleteRecursively(); b.deleteRecursively()
    }

    @Test
    fun `文件顺序无关 - hash 稳定`() {
        val a = createTempDir("h-a"); val b = createTempDir("h-b")
        File(a, "b.txt").writeText("1"); File(a, "a.txt").writeText("2")
        File(b, "a.txt").writeText("2"); File(b, "b.txt").writeText("1")
        assertEquals(EntryHasher.hashOf(a), EntryHasher.hashOf(b))
        a.deleteRecursively(); b.deleteRecursively()
    }

    @Test
    fun `隐藏文件与隐藏目录 - 不参与 hash`() {
        val a = createTempDir("h-a"); val b = createTempDir("h-b")
        File(a, "meta.json").writeText("x")
        File(a, ".hidden").writeText("secret")
        File(File(a, ".hdir"), "f").apply { parentFile.mkdirs(); writeText("s") }
        File(b, "meta.json").writeText("x")
        assertEquals(EntryHasher.hashOf(a), EntryHasher.hashOf(b))
        assertEquals(1, EntryHasher.collectFiles(a).size)
        a.deleteRecursively(); b.deleteRecursively()
    }

    @Test
    fun `递归子目录 - 纳入 hash`() {
        val dir = createTempDir("h-d")
        File(dir, "meta.json").writeText("x")
        File(File(dir, "变式"), "v1.md").apply { parentFile.mkdirs(); writeText("变式题") }
        assertEquals(2, EntryHasher.collectFiles(dir).size)
        assertTrue(EntryHasher.verify(dir, ManifestEntry("id", "数学", dir.name, EntryHasher.hashOf(dir), 2)))
        dir.deleteRecursively()
    }
}

/** manifest 序列化（模块 09 T06） */
class BackupManifestCodecTest {

    @Test
    fun `往返一致`() {
        val m = BackupManifest(
            kind = BackupManifest.KIND_FULL,
            createdAt = "2026-08-16T10:00:00Z",
            entries = listOf(
                ManifestEntry("id1", "数学", "A", "abc123", 3),
                ManifestEntry("id2", "物理", "B", "def456", 2)
            )
        )
        assertEquals(m, BackupManifestCodec.parse(BackupManifestCodec.serialize(m)).getOrThrow())
    }

    @Test
    fun `未知字段忽略 解析失败返回 failure`() {
        assertTrue(BackupManifestCodec.parse("""{"schemaVersion":1,"future":1,"entries":[]}""").isSuccess)
        assertTrue(BackupManifestCodec.parse("not-json").isFailure)
    }
}

/** 备份 zip 生成与读取（模块 09 T06 整库备份） */
class BackupWriterTest {

    private lateinit var lib: TestLib

    @Before fun setUp() { lib = TestLib() }
    @After fun tearDown() { lib.cleanup() }

    @Test
    fun `整库备份 - zip 含 manifest 与全部条目 结构与本地镜像`() {
        lib.entry("数学", "20260816_A", "id-a", text = "题干A")
        lib.entry("物理", "20260816_B", "id-b", text = "题干B", extra = mapOf("变式/v1.md" to "变式题"))
        val zip = File(lib.root.parentFile, "backup.zip")
        val writer = BackupWriter(lib.root, lib.index)

        val result = writer.write(FileSink(zip)).getOrThrow()

        assertEquals(2, result.entryCount)
        assertTrue(zip.isFile)
        // 读回 manifest
        val manifest = BackupReader.readManifest(FileInputStream(zip)).getOrThrow()
        assertEquals(BackupManifest.KIND_FULL, manifest.kind)
        assertEquals(2, manifest.entries.size)
        assertEquals(setOf("id-a", "id-b"), manifest.entries.map { it.id }.toSet())
        // 解包到空目录 → hash 校验通过（结构完整）
        val target = createTempDir("misoup-restore")
        val extracted = BackupReader.extract(FileInputStream(zip), target)
        assertEquals(2, extracted.size)
        manifest.entries.forEach { me ->
            val dir = extracted["${me.subject}/${me.dirName}"]
            assertTrue("条目 ${me.id} 解包存在", dir != null)
            assertTrue(EntryHasher.verify(dir!!, me))
        }
        target.deleteRecursively()
        zip.delete()
    }

    @Test
    fun `空库备份 - 失败`() {
        val zip = File(lib.root.parentFile, "empty.zip")
        val r = BackupWriter(lib.root, lib.index).write(FileSink(zip))
        assertTrue(r.isFailure)
        assertFalse(zip.exists())
    }

    @Test
    fun `单条目导出 - manifest kind=single-export`() {
        lib.entry("数学", "A", "id-a")
        lib.entry("数学", "B", "id-b")
        val zip = File(lib.root.parentFile, "single.zip")

        val result = BackupWriter(lib.root, lib.index).write(FileSink(zip), ids = listOf("id-a"), kind = BackupManifest.KIND_SINGLE).getOrThrow()

        assertEquals(1, result.entryCount)
        val manifest = BackupReader.readManifest(FileInputStream(zip)).getOrThrow()
        assertEquals(BackupManifest.KIND_SINGLE, manifest.kind)
        assertEquals(listOf("id-a"), manifest.entries.map { it.id })
        zip.delete()
    }

    @Test
    fun `备份含损坏条目 id - 自动跳过`() {
        lib.entry("数学", "A", "id-a")
        val zip = File(lib.root.parentFile, "x.zip")
        // 请求不存在的 id + 存在的 id：不存在者跳过，不失败
        val result = BackupWriter(lib.root, lib.index).write(FileSink(zip), ids = listOf("id-a", "ghost")).getOrThrow()
        assertEquals(1, result.entryCount)
        zip.delete()
    }
}
