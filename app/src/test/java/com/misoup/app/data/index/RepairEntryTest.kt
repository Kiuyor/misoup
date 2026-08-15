package com.misoup.app.data.index

import com.misoup.app.data.store.MetaJson
import com.misoup.app.makeMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T10 损坏 meta 容错：扫描遇解析失败 → "待修复"条目（保留原始 JSON），不崩溃不丢文件
 */
class RepairEntryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        index = LibraryIndex()
    }

    private fun writeEntry(subject: String, dirName: String, metaJson: String) {
        File(File(root, subject), dirName).apply { mkdirs() }
        File(File(File(root, subject), dirName), "meta.json").writeText(metaJson)
    }

    @Test
    fun `损坏 meta 标为待修复并保留原始JSON`() {
        val corrupt = """{"id": "broken-1", "subject": "数学", "questionType": """  // 截断 JSON
        writeEntry("数学", "损坏条目", corrupt)

        index.rebuild(root)

        val repairs = index.query(Filter(needsRepair = true), null)
        assertEquals(1, repairs.size)
        val entry = repairs[0]
        assertTrue(entry.needsRepair)
        assertEquals("损坏条目", entry.dirName)
        assertEquals(corrupt, entry.repairJson)   // 原始 JSON 保留，UI 可展示
        // 不崩溃：正常条目不受影响
        assertEquals(1, index.size())
    }

    @Test
    fun `缺失 meta 文件也标待修复`() {
        File(File(root, "数学"), "无meta目录").mkdirs()

        index.rebuild(root)

        val repairs = index.query(Filter(needsRepair = true), null)
        assertEquals(1, repairs.size)
        assertTrue(repairs[0].needsRepair)
        assertNull(repairs[0].repairJson)         // 无原始 JSON
    }

    @Test
    fun `损坏与正常混合-计数与过滤正确`() {
        writeEntry("数学", "正常A", MetaJson.serialize(makeMeta(id = "ok-a", subject = "数学")))
        writeEntry("数学", "损坏B", "{bad json")
        writeEntry("物理", "正常C", MetaJson.serialize(makeMeta(id = "ok-c", subject = "物理")))
        writeEntry("物理", "损坏D", "not json at all")

        index.rebuild(root)

        assertEquals(2, index.query(Filter(needsRepair = true), null).size)
        assertEquals(2, index.query(Filter(needsRepair = false), null).size)
        assertEquals(4, index.query(Filter(needsRepair = null), null).size)
        assertEquals(4, index.size())
        // 待修复不参与关键词检索（无文本倒排）
        assertTrue(index.query(Filter(needsRepair = true), "任何关键词").isEmpty())
    }

    @Test
    fun `待修复条目不参与科目统计外的过滤维度`() {
        writeEntry("数学", "损坏", "{bad")

        index.rebuild(root)
        val repairs = index.query(Filter(needsRepair = true), null)
        assertEquals(1, repairs.size)
        // questionType 为空串，不会被题型过滤误伤到正常条目
        assertEquals("", repairs[0].questionType)
        // 按科目过滤仍可看到待修复
        assertEquals(1, index.query(Filter(subjects = listOf("数学")), null).size)
    }

    @Test
    fun `修复文件后 rebuild 变回正常条目`() {
        val corrupt = "{bad json"
        writeEntry("数学", "待修复条目", corrupt)
        index.rebuild(root)
        assertEquals(1, index.query(Filter(needsRepair = true), null).size)

        // 用户修复 meta.json（合法内容）
        val fixed = makeMeta(id = "fixed-id", subject = "数学")
        File(File(File(root, "数学"), "待修复条目"), "meta.json")
            .writeText(MetaJson.serialize(fixed))

        index.rebuild(root)
        assertEquals(0, index.query(Filter(needsRepair = true), null).size)
        assertEquals(1, index.query(Filter(needsRepair = false), null).size)
        assertEquals("fixed-id", index.get("fixed-id")!!.id)
        assertFalse(index.get("fixed-id")!!.needsRepair)
    }

    @Test
    fun `损坏文件不被删除-数据保全`() {
        val corrupt = "{\"partial\": true"
        writeEntry("数学", "损坏", corrupt)
        index.rebuild(root)

        // 文件原样保留在磁盘
        val f = File(File(File(root, "数学"), "损坏"), "meta.json")
        assertTrue(f.isFile)
        assertEquals(corrupt, f.readText())
    }
}
