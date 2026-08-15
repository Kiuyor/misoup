package com.misoup.app.data.store

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.makeMeta
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T07 StoreService 事务：create/update/delete/move
 * 覆盖：正常路径 / 边界 / 失败路径 / 并发串行 / 临时目录清理 / 索引一致
 */
class StoreServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex
    private lateinit var encoder: FakeImageEncoder
    private lateinit var service: StoreService

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        index = LibraryIndex()
        encoder = FakeImageEncoder()
        service = StoreService(root, index, encoder)
    }

    private val img = FakeImageAsset(4096, 2048)

    // ---------- create ----------

    @Test
    fun `create 落库-目录树+meta+原图+文本+索引`() = runTest {
        val meta = makeMeta(subject = "数学", questionType = "解答题")
        val result = service.create(meta, img, "二次函数在区间[1,3]上的最值问题")

        assertTrue(result.isSuccess)
        val dirName = result.getOrThrow()

        // 目录名格式：yyyyMMdd_HHmmss_摘要
        assertTrue(dirName.matches(Regex("\\d{8}_\\d{6}_.+")))
        // 目录树：root/数学/<dirName>
        val entryDir = File(File(root, "数学"), dirName)
        assertTrue(entryDir.isDirectory)
        assertTrue(File(entryDir, "meta.json").isFile)
        assertTrue(File(entryDir, "原图.webp").isFile)
        assertTrue(File(entryDir, "文本.md").isFile)
        // 编码器确实收到原图
        assertEquals(1, encoder.originalCalls)

        // meta.json 可解析且与入参一致
        val onDisk = MetaJson.parse(File(entryDir, "meta.json").readText()).getOrThrow()
        assertEquals(meta, onDisk)
        assertEquals(meta.id, onDisk.id)

        // 索引已更新
        val entry = index.get(meta.id)!!
        assertEquals(dirName, entry.dirName)
        assertEquals("数学", entry.subject)
        // 文本已入倒排
        assertEquals(listOf(meta.id), index.query(com.misoup.app.data.index.Filter(), "最值").map { it.id })
    }

    @Test
    fun `create 无文本-不写文本md且目录名用note`() = runTest {
        val meta = makeMeta(subject = "英语", note = "时态辨析")
        val result = service.create(meta, img, text = null)
        assertTrue(result.isSuccess)

        val entryDir = File(File(root, "英语"), result.getOrThrow())
        assertFalse(File(entryDir, "文本.md").exists())
        assertTrue(result.getOrThrow().endsWith("_时态辨析"))
    }

    @Test
    fun `create 空id失败-库内无残留`() = runTest {
        val meta = makeMeta(id = "  ").copy(id = "  ")
        val result = service.create(meta, img, "题干")
        assertTrue(result.isFailure)
        assertEquals(0, index.size())
        assertTrue(root.listFiles()?.isEmpty() == true)
    }

    @Test
    fun `create 空科目失败`() = runTest {
        val result = service.create(makeMeta(subject = ""), img, "题干")
        assertTrue(result.isFailure)
        assertEquals(0, index.size())
    }

    @Test
    fun `create 重复 id 幂等保护`() = runTest {
        val meta = makeMeta(id = "dup-id", subject = "数学")
        assertTrue(service.create(meta, img, "题干A").isSuccess)
        val second = service.create(meta, img, "题干B")
        assertTrue(second.isFailure)
        // 只有一份
        assertEquals(1, index.size())
        val dirs = File(root, "数学").listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
        assertEquals(1, dirs?.size)
    }

    @Test
    fun `create 编码失败-临时目录清理无残留`() = runTest {
        encoder.failOriginal = true
        val result = service.create(makeMeta(subject = "数学"), img, "题干")
        assertTrue(result.isFailure)
        assertEquals(0, index.size())
        // 无残留：无条目目录、无 .tmp
        val leftovers = root.listFiles()?.toList().orEmpty()
        assertTrue("残留: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    @Test
    fun `create 并发 20 条-全部成功且串行一致`() = runTest {
        val jobs = (1..20).map { i ->
            async {
                service.create(
                    makeMeta(id = "c$i", subject = if (i % 2 == 0) "数学" else "物理"),
                    img,
                    "并发第${i}题"
                )
            }
        }
        val results = jobs.awaitAll()
        assertTrue("失败: ${results.filter { it.isFailure }.map { it.exceptionOrNull()?.message }}",
            results.all { it.isSuccess })
        assertEquals(20, index.size())
        // 磁盘 20 个条目目录（Mutex 串行，无 .tmp 残留）
        val diskCount = root.listFiles()!!
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sumOf { s -> s.listFiles()!!.count { it.isDirectory && !it.name.startsWith(".") } }
        assertEquals(20, diskCount)
        // 无临时目录残留
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".tmp") })
    }

    // ---------- update ----------

    @Test
    fun `update 修改字段-磁盘与索引同步`() = runTest {
        val meta = makeMeta(subject = "数学", tags = listOf("旧标签"))
        val dirName = service.create(meta, img, "题干").getOrThrow()
        val entryDir = File(File(root, "数学"), dirName)

        val result = service.update(meta.id) { it.copy(note = "新备注", tags = listOf("新标签")) }
        assertTrue(result.isSuccess)

        val onDisk = MetaJson.parse(File(entryDir, "meta.json").readText()).getOrThrow()
        assertEquals("新备注", onDisk.note)
        assertEquals(listOf("新标签"), onDisk.tags)
        assertEquals(meta.id, onDisk.id)          // id 不可变
        // 索引条目同步
        assertEquals(listOf("新标签"), index.get(meta.id)!!.tags)
    }

    @Test
    fun `update 不存在 id 失败`() = runTest {
        val result = service.update("no-such") { it }
        assertTrue(result.isFailure)
    }

    @Test
    fun `update 修改 id 被拒绝`() = runTest {
        val meta = makeMeta(subject = "数学")
        service.create(meta, img, "题干").getOrThrow()
        val result = service.update(meta.id) { it.copy(id = "other-id") }
        assertTrue(result.isFailure)
        // 磁盘未被污染
        val entry = index.get(meta.id)!!
        val onDisk = MetaJson.parse(
            File(File(File(root, "数学"), entry.dirName), "meta.json").readText()
        ).getOrThrow()
        assertEquals(meta.id, onDisk.id)
    }

    @Test
    fun `update 磁盘 meta 损坏-报错不崩溃`() = runTest {
        val meta = makeMeta(subject = "数学")
        val dirName = service.create(meta, img, "题干").getOrThrow()
        // 人为损坏 meta.json
        File(File(File(root, "数学"), dirName), "meta.json").writeText("{corrupt")

        val result = service.update(meta.id) { it.copy(note = "x") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `update 后文本倒排可更新`() = runTest {
        val meta = makeMeta(subject = "数学")
        service.create(meta, img, "原始题干内容").getOrThrow()
        // 识别完成后更新文本
        service.update(meta.id) { it.copy(ocr = it.ocr.copy(status = "done")) }
        index.indexText(meta.id, "识别后的新题干")

        assertEquals(listOf(meta.id), index.query(com.misoup.app.data.index.Filter(), "新题干").map { it.id })
    }

    // ---------- delete ----------

    @Test
    fun `delete 移除目录+索引+倒排`() = runTest {
        val meta = makeMeta(subject = "数学")
        val dirName = service.create(meta, img, "待删除题干").getOrThrow()

        val result = service.delete(meta.id)
        assertTrue(result.isSuccess)

        assertFalse(File(File(root, "数学"), dirName).exists())
        assertNull(index.get(meta.id))
        assertEquals(0, index.size())
        assertTrue(index.query(com.misoup.app.data.index.Filter(), "待删除").isEmpty())
    }

    @Test
    fun `delete 不存在 id 失败`() = runTest {
        assertTrue(service.delete("no-such").isFailure)
    }

    @Test
    fun `delete 后重新 create 同 id 可成功`() = runTest {
        val meta = makeMeta(id = "reuse", subject = "数学")
        service.create(meta, img, "题干").getOrThrow()
        service.delete(meta.id).getOrThrow()
        // id 已释放
        assertTrue(service.create(meta, img, "题干").isSuccess)
        assertEquals(1, index.size())
    }

    // ---------- move ----------

    @Test
    fun `move 换科目-目录与meta与索引同步`() = runTest {
        val meta = makeMeta(subject = "数学", questionType = "解答题")
        val dirName = service.create(meta, img, "题干").getOrThrow()

        val result = service.move(meta.id, "物理")
        assertTrue(result.isSuccess)

        // 目录已移动
        assertFalse(File(File(root, "数学"), dirName).exists())
        val movedDir = File(File(root, "物理"), dirName)
        assertTrue(movedDir.isDirectory)
        // meta.subject 已同步
        val onDisk = MetaJson.parse(File(movedDir, "meta.json").readText()).getOrThrow()
        assertEquals("物理", onDisk.subject)
        // 索引已同步
        assertEquals("物理", index.get(meta.id)!!.subject)
    }

    @Test
    fun `move 到自定义科目名`() = runTest {
        val meta = makeMeta(subject = "数学")
        val dirName = service.create(meta, img, "题干").getOrThrow()
        assertTrue(service.move(meta.id, "信息技术").isSuccess)
        assertTrue(File(File(root, "信息技术"), dirName).isDirectory)
    }

    @Test
    fun `move 同科目-无操作成功`() = runTest {
        val meta = makeMeta(subject = "数学")
        val dirName = service.create(meta, img, "题干").getOrThrow()
        assertTrue(service.move(meta.id, "数学").isSuccess)
        assertTrue(File(File(root, "数学"), dirName).isDirectory)
        assertEquals(1, index.size())
    }

    @Test
    fun `move 目标科目已有同名目录-失败且原位置不变`() = runTest {
        val meta = makeMeta(subject = "数学")
        val dirName = service.create(meta, img, "题干").getOrThrow()
        // 物理下预置同名目录
        File(File(root, "物理"), dirName).mkdirs()

        val result = service.move(meta.id, "物理")
        assertTrue(result.isFailure)
        // 原位置仍在
        assertTrue(File(File(root, "数学"), dirName).isDirectory)
        assertEquals("数学", index.get(meta.id)!!.subject)
    }

    @Test
    fun `move 不存在 id 失败`() = runTest {
        assertTrue(service.move("no-such", "物理").isFailure)
    }

    @Test
    fun `move 空科目失败`() = runTest {
        val meta = makeMeta(subject = "数学")
        service.create(meta, img, "题干").getOrThrow()
        assertTrue(service.move(meta.id, "  ").isFailure)
    }

    // ---------- 与索引重建协同 ----------

    @Test
    fun `create 后用新索引 rebuild 可恢复全部条目`() = runTest {
        service.create(makeMeta(subject = "数学"), img, "题干一").getOrThrow()
        service.create(makeMeta(subject = "物理"), img, "题干二").getOrThrow()

        val freshIndex = LibraryIndex()
        freshIndex.rebuild(root)
        assertEquals(2, freshIndex.size())
        assertNotNull(freshIndex.get(freshIndex.all().first { it.subject == "数学" }.id))
    }

    @Test
    fun `create 带缩略图服务-缩略图生成且索引记录路径`() = runTest {
        val cache = tmp.newFolder("cache")
        val thumbService = ThumbService(cache, encoder)
        val svc = StoreService(root, index, encoder, thumbService)

        val meta = makeMeta(subject = "数学")
        svc.create(meta, img, "题干").getOrThrow()

        val thumb = File(File(cache, "thumbs"), "${meta.id}.webp")
        assertTrue(thumb.isFile)
        assertEquals(thumb.absolutePath, index.get(meta.id)!!.thumbPath)
    }

    @Test
    fun `delete 带缩略图服务-缩略图一并删除`() = runTest {
        val cache = tmp.newFolder("cache")
        val thumbService = ThumbService(cache, encoder)
        val svc = StoreService(root, index, encoder, thumbService)

        val meta = makeMeta(subject = "数学")
        svc.create(meta, img, "题干").getOrThrow()
        assertTrue(File(File(cache, "thumbs"), "${meta.id}.webp").isFile)

        svc.delete(meta.id).getOrThrow()
        assertFalse(File(File(cache, "thumbs"), "${meta.id}.webp").exists())
    }
}
