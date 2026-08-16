package com.misoup.app.data.store

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.makeMeta
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 模块 07 变式落盘（StoreService 追加方法）：
 * - 保存：写 <条目目录>/变式/<文件名>，内容正确
 * - 列表：名称倒序（最新在前）；无变式目录 → 空
 * - 读取：往返一致；文件不存在 → failure
 * - 失败分支：空列表 / 非 .md 名 / 同名覆盖 / 条目不存在
 */
class StoreServiceVariantTest {

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

    private suspend fun createEntry(id: String = "uuid-1"): String {
        val result = service.create(
            makeMeta(id = id, subject = "数学"),
            FakeImageAsset(100, 200),
            "## 题干\nQ"
        )
        assertTrue(result.isSuccess)
        return result.getOrThrow()
    }

    private fun entryDir(dirName: String): File = File(File(root, "数学"), dirName)

    @Test
    fun `保存-写入变式目录-内容正确`() = runTest {
        val dirName = createEntry()
        val result = service.saveVariantFiles(
            "uuid-1",
            mapOf("20260816_140523_变式1.md" to "## 题干\n变1", "20260816_140523_变式2.md" to "## 题干\n变2")
        )
        assertTrue(result.isSuccess)

        val variantDir = File(entryDir(dirName), MisoupConfigConst.VARIANT_DIR_NAME)
        assertTrue(variantDir.isDirectory)
        assertEquals("## 题干\n变1", File(variantDir, "20260816_140523_变式1.md").readText())
        assertEquals("## 题干\n变2", File(variantDir, "20260816_140523_变式2.md").readText())
        // 无临时文件残留
        assertTrue(variantDir.listFiles()!!.none { it.name.startsWith(".v-") })
    }

    @Test
    fun `列表-名称倒序-最新在前`() = runTest {
        createEntry()
        service.saveVariantFiles(
            "uuid-1",
            mapOf(
                "20260815_100000_变式1.md" to "a",
                "20260816_140523_变式1.md" to "b",
                "20260816_140523_变式2.md" to "c"
            )
        ).getOrThrow()
        assertEquals(
            listOf("20260816_140523_变式2.md", "20260816_140523_变式1.md", "20260815_100000_变式1.md"),
            service.listVariantFiles("uuid-1").getOrThrow()
        )
    }

    @Test
    fun `列表-无变式目录-空列表`() = runTest {
        createEntry()
        assertEquals(emptyList<String>(), service.listVariantFiles("uuid-1").getOrThrow())
    }

    @Test
    fun `读取-往返一致`() = runTest {
        createEntry()
        service.saveVariantFiles("uuid-1", mapOf("20260816_140523_变式1.md" to "## 题干\nX")).getOrThrow()
        assertEquals("## 题干\nX", service.readVariantFile("uuid-1", "20260816_140523_变式1.md").getOrThrow())
    }

    @Test
    fun `读取-文件不存在-failure`() = runTest {
        createEntry()
        val result = service.readVariantFile("uuid-1", "20260816_140523_变式1.md")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("不存在"))
    }

    @Test
    fun `保存-空列表-failure`() = runTest {
        createEntry()
        assertTrue(service.saveVariantFiles("uuid-1", emptyMap()).isFailure)
    }

    @Test
    fun `保存-非md文件名-failure-不落盘`() = runTest {
        val dirName = createEntry()
        val result = service.saveVariantFiles("uuid-1", mapOf("20260816_140523_变式1.txt" to "x"))
        assertTrue(result.isFailure)
        val variantDir = File(entryDir(dirName), MisoupConfigConst.VARIANT_DIR_NAME)
        assertTrue(variantDir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun `保存-同名覆盖-failure`() = runTest {
        createEntry()
        service.saveVariantFiles("uuid-1", mapOf("20260816_140523_变式1.md" to "第一版")).getOrThrow()
        val result = service.saveVariantFiles("uuid-1", mapOf("20260816_140523_变式1.md" to "第二版"))
        assertTrue(result.isFailure)
        assertEquals("第一版", service.readVariantFile("uuid-1", "20260816_140523_变式1.md").getOrThrow())
    }

    @Test
    fun `保存-条目不存在-failure`() = runTest {
        val result = service.saveVariantFiles("no-such", mapOf("20260816_140523_变式1.md" to "x"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("不存在"))
    }

    @Test
    fun `列表读取-条目不存在-failure`() = runTest {
        assertTrue(service.listVariantFiles("no-such").isFailure)
        assertTrue(service.readVariantFile("no-such", "x.md").isFailure)
    }
}
