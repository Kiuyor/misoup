package com.misoup.app.data.store

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.makeMeta
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 模块 02 追加的 StoreService 能力（T07/T09）：
 * rename（id 不变）/ updateText（倒排+mtime）/ 读取访问器。
 */
class StoreServiceRenameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex
    private lateinit var service: StoreService

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        index = LibraryIndex()
        service = StoreService(root, index, FakeImageEncoder())
    }

    private val img = FakeImageAsset(100, 200)

    private suspend fun createEntry(id: String = "uuid-r-1", subject: String = "数学", text: String = "题干内容"): String {
        service.create(makeMeta(id = id, subject = subject), img, text).getOrThrow()
        return id
    }

    // ---------- rename（T09） ----------

    @Test
    fun `rename 成功-目录迁移-id 不变`() = runTest {
        val id = createEntry()
        val oldDir = index.get(id)!!.dirName
        val newDir = "20260816_120000_新名字"

        val result = service.rename(id, newDir)
        assertTrue(result.isSuccess)
        assertEquals(newDir, index.get(id)!!.dirName)
        assertEquals(id, index.get(id)!!.id)
        assertFalse(File(File(root, "数学"), oldDir).exists())
        assertTrue(File(File(root, "数学"), newDir).isDirectory)
        // meta.json 未被改动（id 仍是 UUID）
        val meta = service.readMetaById(id).getOrThrow()
        assertEquals(id, meta.id)
    }

    @Test
    fun `rename 同名-无操作成功`() = runTest {
        val id = createEntry()
        val dir = index.get(id)!!.dirName
        assertTrue(service.rename(id, dir).isSuccess)
        assertEquals(dir, index.get(id)!!.dirName)
    }

    @Test
    fun `rename 目标已存在-失败且原目录保留`() = runTest {
        val idA = createEntry("uuid-a", text = "题目甲")
        createEntry("uuid-b", text = "题目乙")
        val otherDir = index.get("uuid-b")!!.dirName
        val result = service.rename(idA, otherDir)
        assertTrue(result.isFailure)
        // 原目录未被破坏
        assertEquals(index.get(idA)!!.dirName, File(File(root, "数学"), index.get(idA)!!.dirName).name)
        assertTrue(File(File(root, "数学"), otherDir).isDirectory)
    }

    @Test
    fun `rename 空名-失败`() = runTest {
        val id = createEntry()
        assertTrue(service.rename(id, "  ").isFailure)
    }

    @Test
    fun `rename 不存在的条目-失败`() = runTest {
        assertTrue(service.rename("no-such-id", "新目录").isFailure)
    }

    // ---------- updateText（T07） ----------

    @Test
    fun `updateText-覆盖文本-倒排刷新-mtime 提升`() = runTest {
        val id = createEntry()
        index.indexText(id, "旧题干")
        val oldMtime = index.get(id)!!.mtime

        val result = service.updateText(id, "## 题干\n新题干内容")
        assertTrue(result.isSuccess)

        // 文件内容已替换
        val raw = service.readTextById(id).getOrThrow()
        assertTrue(raw.contains("新题干内容"))
        assertFalse(raw.contains("旧题干"))

        // 倒排：旧词失效，新词命中
        assertEquals(0, index.query(com.misoup.app.data.index.Filter(), "旧题干").size)
        assertEquals(listOf(id), index.query(com.misoup.app.data.index.Filter(), "新题干").map { it.id })
        // mtime 刷新（最近编辑排前）
        assertTrue(index.get(id)!!.mtime >= oldMtime)
    }

    @Test
    fun `updateText 空文本-清空倒排不崩溃`() = runTest {
        val id = createEntry()
        index.indexText(id, "可搜索词")
        assertTrue(service.updateText(id, "").isSuccess)
        assertEquals(0, index.query(com.misoup.app.data.index.Filter(), "可搜索词").size)
        assertEquals("", service.readTextById(id).getOrThrow())
    }

    @Test
    fun `updateText 不存在的条目-失败`() = runTest {
        assertTrue(service.updateText("no-such", "x").isFailure)
    }

    // ---------- 读取访问器 ----------

    @Test
    fun `readMetaById-读取成功与不存在失败`() = runTest {
        val id = createEntry()
        assertEquals(id, service.readMetaById(id).getOrThrow().id)
        assertTrue(service.readMetaById("no-such").isFailure)
    }

    @Test
    fun `readTextById-无文本文件返回空串`() = runTest {
        val id = "uuid-no-text"
        service.create(makeMeta(id = id), img, text = null).getOrThrow()
        assertEquals("", service.readTextById(id).getOrThrow())
    }

    @Test
    fun `imagePathById-原图存在返回路径-无原图返回 null`() = runTest {
        val id = createEntry()
        val path = service.imagePathById(id).getOrThrow()
        assertTrue(path != null && File(path).isFile)
        assertEquals(null, service.imagePathById("no-such").getOrThrow())
    }

    // ---------- 与 move 联动 ----------

    @Test
    fun `rename 后 move-仍以 id 关联`() = runTest {
        val id = createEntry()
        service.rename(id, "20260816_120000_新名字").getOrThrow()
        assertTrue(service.move(id, "英语").isSuccess)
        assertEquals("英语", index.get(id)!!.subject)
        assertEquals("20260816_120000_新名字", index.get(id)!!.dirName)
        assertTrue(File(File(root, "英语"), "20260816_120000_新名字").isDirectory)
    }
}
