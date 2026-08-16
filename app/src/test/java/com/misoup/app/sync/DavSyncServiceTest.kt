package com.misoup.app.sync

import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.store.MetaJson
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.sync.webdav.DavConfig
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * DavSyncService 集成单测（模块 09 T02 全量上传 / T03 增量双向 / T04 冲突 + HTTPS 强制）
 *
 * 网络层 = 内存 FakeDavClient（断网故障可注入）；本地库 = 临时目录 + LibraryIndex + StoreService。
 */
class DavSyncServiceTest {

    private lateinit var libraryRoot: File
    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService
    private lateinit var client: FakeDavClient
    private lateinit var service: DavSyncService

    private val httpsConfig = DavConfig(
        url = "https://dav.example.com/dav/",
        username = "user",
        password = "pass"
    )

    @Before
    fun setUp() {
        libraryRoot = createTempDir("misoup-lib")
        index = LibraryIndex()
        store = StoreService(libraryRoot, index, FakeImageEncoder())
        client = FakeDavClient()
        service = DavSyncService({ httpsConfig }, store, index, client, libraryRoot)
    }

    @After
    fun tearDown() {
        libraryRoot.deleteRecursively()
    }

    // ---------- 测试工具 ----------

    /** 本地落一个条目（meta.json + 原图.webp + 文本.md）并入索引 */
    private fun putLocalEntry(subject: String, dirName: String, text: String = "题干A", tag: String = "标签X") {
        val meta = makeMeta(subject = subject, tags = listOf(tag))
        val dir = File(File(libraryRoot, subject), dirName)
        dir.mkdirs()
        File(dir, MisoupConfigConst.META_FILE_NAME).writeText(MetaJson.serialize(meta))
        File(dir, MisoupConfigConst.ORIGINAL_IMAGE_NAME).writeText("image-$dirName")
        File(dir, MisoupConfigConst.TEXT_FILE_NAME).writeText(text)
        index.upsert(meta, null, dirName = dirName)
    }

    private fun localDir(subject: String, dirName: String) = File(File(libraryRoot, subject), dirName)

    /** 读取远端状态快照（Fake 内） */
    private fun remoteState(): SyncState? {
        val raw = client.fileContent(".sync-state.json") ?: return null
        return SyncStateCodec.parse(raw).getOrNull()
    }

    // ---------- T02 全量上传 ----------

    @Test
    fun `首次 syncFull - 全量上传镜像远程结构`() = runTest {
        putLocalEntry("数学", "20260816_A")
        putLocalEntry("物理", "20260816_B", text = "题干B")

        val report = service.syncFull()

        assertTrue(report.isSuccess)
        assertEquals(2, report.uploaded)
        assertEquals(0, report.downloaded)
        // 远程结构与本地镜像：<根>/<科目>/<条目目录>/meta.json + 原图.webp + 文本.md
        assertTrue(client.remoteEntryExists("数学", "20260816_A"))
        assertTrue(client.remoteEntryExists("物理", "20260816_B"))
        assertEquals("image-20260816_A", client.fileContent("数学/20260816_A/原图.webp"))
        assertEquals("题干B", client.fileContent("物理/20260816_B/文本.md"))
        // 状态快照已回写
        val state = remoteState()
        assertNotNull(state)
        assertEquals(2, state!!.entries.size)
        assertTrue(state.entries.containsKey("数学/20260816_A"))
    }

    @Test
    fun `再次 syncFull 无变更 - 全部跳过`() = runTest {
        putLocalEntry("数学", "A")
        service.syncFull()

        val report = service.syncFull()
        assertTrue(report.isSuccess)
        assertEquals(0, report.uploaded)
        assertEquals(0, report.downloaded)
        assertEquals(1, report.skipped)
    }

    @Test
    fun `syncFull 远端已有条目 - 镜像下载到本地`() = runTest {
        client.putEntry("英语", "remote-entry", mapOf(
            "meta.json" to MetaJson.serialize(makeMeta(subject = "英语")),
            "原图.webp" to "remote-image"
        ))

        val report = service.syncFull()

        assertTrue(report.isSuccess)
        assertEquals(1, report.downloaded)
        val dir = localDir("英语", "remote-entry")
        assertTrue(dir.isDirectory)
        assertEquals("remote-image", File(dir, "原图.webp").readText())
        // 下载条目已并入索引（本会话立即可见）
        assertEquals(1, index.size())
    }

    // ---------- T03 增量双向 ----------

    @Test
    fun `增量 - 本地修改后上传`() = runTest {
        putLocalEntry("数学", "A")
        service.syncFull()
        // 本地修改（改文本 → 文件变化）
        File(localDir("数学", "A"), MisoupConfigConst.TEXT_FILE_NAME).writeText("修改后题干")

        val report = service.syncIncremental()

        assertTrue(report.isSuccess)
        assertEquals(1, report.uploaded)
        assertEquals("修改后题干", client.fileContent("数学/A/文本.md"))
    }

    @Test
    fun `增量 - 远端修改后下载`() = runTest {
        putLocalEntry("数学", "A")
        service.syncFull()
        // 另一设备改远端（文本更新）
        client.putFile("数学/A/文本.md", "远端新题干")

        val report = service.syncIncremental()

        assertTrue(report.isSuccess)
        assertEquals(1, report.downloaded)
        assertEquals("远端新题干", File(localDir("数学", "A"), "文本.md").readText())
    }

    @Test
    fun `增量 - 无变化跳过`() = runTest {
        putLocalEntry("数学", "A")
        service.syncFull()

        val report = service.syncIncremental()

        assertTrue(report.isSuccess)
        assertEquals(0, report.uploaded)
        assertEquals(0, report.downloaded)
        assertEquals(1, report.skipped)
    }

    @Test
    fun `增量 - 本地删除传播到远端`() = runTest {
        putLocalEntry("数学", "A")
        service.syncFull()
        // 本地删除条目目录（模拟 StoreService.delete 后的状态）
        localDir("数学", "A").deleteRecursively()
        index.remove(index.all().single().id)

        val report = service.syncIncremental()

        assertTrue(report.isSuccess)
        assertEquals(1, report.deleted)
        assertFalse(client.remoteEntryExists("数学", "A"))
    }

    @Test
    fun `增量 - 远端删除传播到本地且索引更新`() = runTest {
        putLocalEntry("数学", "A")
        service.syncFull()
        // 另一设备删除远端条目
        client.delete("${FakeDavClient.ROOT}/数学/A")

        val report = service.syncIncremental()

        assertTrue(report.isSuccess)
        assertEquals(1, report.deleted)
        assertFalse(localDir("数学", "A").exists())
        assertEquals(0, index.size())
    }

    @Test
    fun `增量 - 远端删除但本地已改 - 本地复活上传`() = runTest {
        putLocalEntry("数学", "A")
        service.syncFull()
        client.delete("${FakeDavClient.ROOT}/数学/A")
        File(localDir("数学", "A"), MisoupConfigConst.TEXT_FILE_NAME).writeText("本地已改")

        val report = service.syncIncremental()

        assertTrue(report.isSuccess)
        assertEquals(1, report.uploaded)
        assertTrue(client.remoteEntryExists("数学", "A"))
    }

    // ---------- T04 冲突处理 ----------

    @Test
    fun `冲突 - 本地较新保留本地 远端旧文件改名 conflict`() = runTest {
        putLocalEntry("数学", "A")
        service.syncFull()
        // 双端同时修改
        File(localDir("数学", "A"), MisoupConfigConst.TEXT_FILE_NAME).writeText("本地新版")
        client.putFile("数学/A/文本.md", "远端旧版")
        // 保证本地 mtime 更大：改写后本地文件自然更新；远端用 fake 时钟（单调递增，仍可能更大）
        // 直接构造：把远端条目 mtime 调旧
        val remoteKey = "${FakeDavClient.ROOT}/数学/A/文本.md"
        val old = client.files[remoteKey]!!
        client.files[remoteKey] = FakeDavClient.RemoteFile(old.bytes, old.mtime - 100_000)

        val report = service.syncIncremental()

        assertTrue(report.isSuccess)
        assertEquals(1, report.conflicts)
        // 远端旧条目被改名 .conflict-<ts>（条目级：目录内全部文件一起改名），正式条目为本地新版
        val conflictDirs = client.files.keys
            .map { it.removePrefix("${FakeDavClient.ROOT}/").substringBeforeLast('/') }
            .filter { it.contains(".conflict-") }
            .distinct()
        assertEquals(1, conflictDirs.size)
        assertTrue(conflictDirs.single().startsWith("数学/A.conflict-"))
        val conflictEntryFiles = client.files.keys.filter { it.contains("数学/A.conflict-") }
        assertEquals(3, conflictEntryFiles.size)   // meta/原图/文本 三件套都保留
        val conflictTextPath = client.files.keys
            .first { it.contains("数学/A.conflict-") && it.endsWith("/文本.md") }
            .removePrefix("${FakeDavClient.ROOT}/")
        assertEquals("远端旧版", client.fileContent(conflictTextPath))
        assertEquals("本地新版", client.fileContent("数学/A/文本.md"))
    }

    @Test
    fun `冲突 - 远端较新保留远端 本地旧目录改名 conflict 且数据都在`() = runTest {
        putLocalEntry("数学", "A", text = "本地旧版")
        service.syncFull()
        // 双端同时修改：本地先改，远端后改（远端 mtime 必须严格更大，确定性构造）
        File(localDir("数学", "A"), MisoupConfigConst.TEXT_FILE_NAME).writeText("本地旧版-改")
        val localTextMtime = File(localDir("数学", "A"), MisoupConfigConst.TEXT_FILE_NAME).lastModified()
        client.putFile("数学/A/文本.md", "远端新版")
        val remoteKey = "${FakeDavClient.ROOT}/数学/A/文本.md"
        client.files[remoteKey] = FakeDavClient.RemoteFile(
            client.files[remoteKey]!!.bytes,
            localTextMtime + 1000   // 远端严格更新
        )

        val report = service.syncIncremental()

        assertTrue(report.isSuccess)
        assertEquals(1, report.conflicts)
        // 正式条目 = 远端新版
        assertEquals("远端新版", File(localDir("数学", "A"), "文本.md").readText())
        // 本地旧数据改名保留（不删任何一方）
        val conflictDir = libraryRoot.listFiles()!!
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .firstOrNull { it.name.contains(".conflict-") }
        assertNotNull(conflictDir)
        assertTrue(conflictDir!!.isDirectory)
        assertTrue(File(conflictDir, "文本.md").readText().contains("本地旧版"))
    }

    // ---------- T04 HTTPS 强制 ----------

    @Test
    fun `http 配置 - 拒绝同步并给出明确警告 零网络请求`() = runTest {
        val httpService = DavSyncService(
            { DavConfig(url = "http://192.168.1.10/dav/", username = "u", password = "p") },
            store, index, client, libraryRoot
        )

        val report = httpService.syncIncremental()

        assertFalse(report.isSuccess)
        assertEquals(1, report.errors.size)
        assertTrue(report.errors.single().contains("HTTPS"))
        assertTrue(client.uploadCalls.isEmpty())
        assertTrue(client.downloadCalls.isEmpty())
    }

    @Test
    fun `未配置 - 拒绝同步`() = runTest {
        val svc = DavSyncService({ DavConfig() }, store, index, client, libraryRoot)
        val report = svc.syncIncremental()
        assertFalse(report.isSuccess)
        assertTrue(report.errors.single().contains("配置"))
    }

    // ---------- 断网容错（验收：断网报错不崩） ----------

    @Test
    fun `断网列取失败 - 明确报错不崩溃`() = runTest {
        putLocalEntry("数学", "A")
        client.failList = true

        val report = service.syncIncremental()

        assertFalse(report.isSuccess)
        assertEquals(1, report.errors.size)
        assertTrue(report.errors.single().contains("列取远端失败"))
    }

    @Test
    fun `断网上传失败 - 该条目记入错误 其余继续`() = runTest {
        putLocalEntry("数学", "A")
        putLocalEntry("物理", "B")
        client.failUpload = true

        val report = service.syncFull()

        assertFalse(report.isSuccess)
        assertTrue(report.errors.any { it.contains("上传失败") })
        // 本地条目仍在，应用其余功能正常
        assertTrue(localDir("数学", "A").isDirectory)
    }

    // ---------- 状态快照 ----------

    @Test
    fun `同步后状态快照 - 内容与指纹一致 可再同步`() = runTest {
        putLocalEntry("数学", "A")
        service.syncFull()
        val state = remoteState()!!
        assertEquals(1, state.entries.size)
        assertTrue(state.lastSyncAt > 0)

        // 用快照状态直接构造差集：双端未变 → 跳过
        val plan = SyncDiff.compute(
            local = LocalScanner.entryFingerprints(index, libraryRoot),
            remote = RemoteScanner.entryFingerprints(client.listRecursive(FakeDavClient.ROOT)),
            state = state.entries.mapValues { (_, e) -> SyncDiff.StateInfo(e.mtime, e.size) }
        )
        assertTrue(plan.uploads.isEmpty())
        assertTrue(plan.downloads.isEmpty())
    }

    @Test
    fun `远端无同步状态 - 首次增量按无快照保守处理`() = runTest {
        putLocalEntry("数学", "A")
        // 不先 syncFull，直接增量：双端无 state → 本地条目上传
        val report = service.syncIncremental()
        assertTrue(report.isSuccess)
        assertEquals(1, report.uploaded)
    }
}
