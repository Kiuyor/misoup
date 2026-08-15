package com.misoup.app.data.index

import com.misoup.app.data.model.MisoupMeta
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
 * T08 内存索引：rebuild / upsert / remove / query（Filter+keyword）/ 倒排 / 排序
 */
class LibraryIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var index: LibraryIndex

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
        index = LibraryIndex()
    }

    // ---------- upsert / remove ----------

    @Test
    fun `upsert 后可按 id 查询`() {
        val meta = makeMeta(id = "id-1", subject = "数学", tags = listOf("二次函数"))
        index.upsert(meta, thumb = null, dirName = "20260816_140523_二次函数区间最值")

        assertEquals(1, index.size())
        val entry = index.get("id-1")!!
        assertEquals("20260816_140523_二次函数区间最值", entry.dirName)
        assertEquals("数学", entry.subject)
        assertEquals("解答题", entry.questionType)
        assertFalse(entry.needsRepair)
    }

    @Test
    fun `upsert 同名 id 覆盖旧条目`() {
        val a = makeMeta(id = "id-x", subject = "数学", note = "旧")
        val b = makeMeta(id = "id-x", subject = "物理", note = "新")
        index.upsert(a, thumb = null, dirName = "d1")
        index.upsert(b, thumb = null, dirName = "d1")

        assertEquals(1, index.size())
        assertEquals("物理", index.get("id-x")!!.subject)
        // 覆盖后 dirName 沿用旧值
        assertEquals("d1", index.get("id-x")!!.dirName)
    }

    @Test
    fun `remove 后条目与倒排均消失`() {
        val meta = makeMeta(id = "id-r", subject = "数学")
        index.upsert(meta, thumb = null, dirName = "d")
        index.indexText("id-r", "二次函数 最值")
        index.remove("id-r")

        assertNull(index.get("id-r"))
        assertEquals(0, index.size())
        // 关键词搜索不再命中
        assertTrue(index.query(Filter(), "二次").isEmpty())
    }

    @Test
    fun `remove 不存在的 id 静默成功`() {
        index.remove("no-such-id")
        assertEquals(0, index.size())
    }

    // ---------- query / Filter ----------

    @Test
    fun `query 按科目过滤`() {
        index.upsert(makeMeta(id = "m1", subject = "数学"), null, dirName = "a")
        index.upsert(makeMeta(id = "p1", subject = "物理"), null, dirName = "b")
        index.upsert(makeMeta(id = "c1", subject = "化学"), null, dirName = "c")

        val math = index.query(Filter(subjects = listOf("数学")), null)
        assertEquals(listOf("m1"), math.map { it.id })
        val multi = index.query(Filter(subjects = listOf("数学", "物理")), null)
        assertEquals(setOf("m1", "p1"), multi.map { it.id }.toSet())
        // 空过滤 = 全部
        assertEquals(3, index.query(Filter(), null).size)
    }

    @Test
    fun `query 按题型与错因过滤`() {
        index.upsert(makeMeta(id = "q1", questionType = "选择题", errorReasons = listOf("概念不清")), null, dirName = "a")
        index.upsert(makeMeta(id = "q2", questionType = "选择题", errorReasons = listOf("粗心大意")), null, dirName = "b")
        index.upsert(makeMeta(id = "q3", questionType = "解答题", errorReasons = listOf("概念不清")), null, dirName = "c")

        assertEquals(2, index.query(Filter(questionTypes = listOf("选择题")), null).size)
        assertEquals(2, index.query(Filter(errorReasons = listOf("概念不清")), null).size)
        // 多条件 AND
        assertEquals(
            listOf("q1"),
            index.query(Filter(questionTypes = listOf("选择题"), errorReasons = listOf("概念不清")), null).map { it.id }
        )
        // 错因任一命中即可（OR 语义）
        assertEquals(
            3,
            index.query(Filter(errorReasons = listOf("概念不清", "粗心大意", "思路错误")), null).size
        )
    }

    @Test
    fun `query 按标签过滤-任一命中`() {
        index.upsert(makeMeta(id = "t1", tags = listOf("二次函数", "月考")), null, dirName = "a")
        index.upsert(makeMeta(id = "t2", tags = listOf("月考")), null, dirName = "b")
        index.upsert(makeMeta(id = "t3", tags = listOf("期中")), null, dirName = "c")

        assertEquals(1, index.query(Filter(tags = listOf("二次函数")), null).size)
        assertEquals(3, index.query(Filter(tags = listOf("月考", "期中")), null).size)
        assertEquals(0, index.query(Filter(tags = listOf("不存在")), null).size)
    }

    @Test
    fun `结果按 mtime 降序`() {
        index.upsert(makeMeta(id = "old", subject = "数学"), null, dirName = "a", mtime = 100)
        index.upsert(makeMeta(id = "new", subject = "数学"), null, dirName = "b", mtime = 300)
        index.upsert(makeMeta(id = "mid", subject = "数学"), null, dirName = "c", mtime = 200)

        assertEquals(listOf("new", "mid", "old"), index.query(Filter(), null).map { it.id })
    }

    // ---------- 倒排 / keyword ----------

    @Test
    fun `indexText 后关键词命中`() {
        val a = makeMeta(id = "a", subject = "数学")
        val b = makeMeta(id = "b", subject = "物理")
        index.upsert(a, null, dirName = "a")
        index.upsert(b, null, dirName = "b")
        index.indexText("a", "二次函数在区间上的最值问题")
        index.indexText("b", "牛顿第二定律与摩擦力")

        assertEquals(listOf("a"), index.query(Filter(), "二次函数").map { it.id })
        assertEquals(listOf("a"), index.query(Filter(), "最值").map { it.id })
        assertEquals(listOf("b"), index.query(Filter(), "牛顿").map { it.id })
        assertEquals(0, index.query(Filter(), "微积分").size)
    }

    @Test
    fun `多关键词 AND 语义`() {
        val a = makeMeta(id = "a", subject = "数学")
        val b = makeMeta(id = "b", subject = "数学")
        index.upsert(a, null, dirName = "a")
        index.upsert(b, null, dirName = "b")
        index.indexText("a", "二次函数 区间 最值")
        index.indexText("b", "二次函数 单调性")

        // 两个 token 都需命中
        assertEquals(listOf("a"), index.query(Filter(), "二次 区间").map { it.id })
        assertEquals(2, index.query(Filter(), "二次 函数").size)
        assertEquals(0, index.query(Filter(), "区间 单调").size)
    }

    @Test
    fun `keyword 空白视为无关键词`() {
        index.upsert(makeMeta(id = "k", subject = "数学"), null, dirName = "a")
        assertEquals(1, index.query(Filter(), "").size)
        assertEquals(1, index.query(Filter(), "   ").size)
        assertEquals(1, index.query(Filter(), null).size)
    }

    @Test
    fun `未索引文本的关键词不命中`() {
        index.upsert(makeMeta(id = "n", subject = "数学"), null, dirName = "a")
        assertTrue(index.query(Filter(), "任何词").isEmpty())
    }

    @Test
    fun `重复 indexText 覆盖旧倒排`() {
        val a = makeMeta(id = "a", subject = "数学")
        index.upsert(a, null, dirName = "a")
        index.indexText("a", "旧内容 函数")
        index.indexText("a", "新内容 物理")

        assertEquals(0, index.query(Filter(), "旧内容").size)
        assertEquals(listOf("a"), index.query(Filter(), "新内容").map { it.id })
    }

    // ---------- rebuild ----------

    @Test
    fun `rebuild 扫描库树并读文本建倒排`() {
        // 构造库：数学/条目A、物理/条目B
        val mathDir = File(root, "数学/20260816_140523_二次函数区间最值").apply { mkdirs() }
        val metaA = makeMeta(id = "rebuild-a", subject = "数学", questionType = "解答题")
        File(mathDir, "meta.json").writeText(MetaJson.serialize(metaA))
        File(mathDir, "文本.md").writeText("二次函数在区间上的最值问题\n答案：略")

        val physDir = File(root, "物理/20260816_140524_牛顿第二定律").apply { mkdirs() }
        val metaB = makeMeta(id = "rebuild-b", subject = "物理", questionType = "实验题")
        File(physDir, "meta.json").writeText(MetaJson.serialize(metaB))
        File(physDir, "文本.md").writeText("牛顿第二定律实验")

        index.rebuild(root)

        assertEquals(2, index.size())
        assertEquals("数学", index.get("rebuild-a")!!.subject)
        assertEquals("20260816_140523_二次函数区间最值", index.get("rebuild-a")!!.dirName)
        // 文本倒排已建立
        assertEquals(listOf("rebuild-a"), index.query(Filter(), "区间").map { it.id })
        assertEquals(listOf("rebuild-b"), index.query(Filter(subjects = listOf("物理")), "牛顿").map { it.id })
        // 摘要 = 文本首行
        assertEquals("二次函数在区间上的最值问题", index.get("rebuild-a")!!.summary)
    }

    @Test
    fun `rebuild 空库与不存在目录安全`() {
        index.rebuild(root)   // 空目录
        assertEquals(0, index.size())

        index.rebuild(File(root, "不存在"))
        assertEquals(0, index.size())
    }

    @Test
    fun `rebuild 忽略隐藏目录`() {
        val hidden = File(root, ".tmp-123").apply { mkdirs() }
        File(hidden, "meta.json").writeText(MetaJson.serialize(makeMeta(id = "h", subject = "数学")))
        val normal = File(root, "数学/正常条目").apply { mkdirs() }
        File(normal, "meta.json").writeText(MetaJson.serialize(makeMeta(id = "n", subject = "数学")))

        index.rebuild(root)
        assertEquals(1, index.size())
        assertEquals("n", index.get("n")!!.id)
    }

    @Test
    fun `rebuild 会清空旧索引重建`() {
        val d = File(root, "数学/旧条目").apply { mkdirs() }
        File(d, "meta.json").writeText(MetaJson.serialize(makeMeta(id = "old", subject = "数学")))
        index.rebuild(root)
        assertEquals(1, index.size())

        // 磁盘删除后 rebuild → 索引清空
        d.deleteRecursively()
        index.rebuild(root)
        assertEquals(0, index.size())
        assertNull(index.get("old"))
    }

    @Test
    fun `rebuild 探测已存在缩略图`() {
        val d = File(root, "数学/条目").apply { mkdirs() }
        val meta = makeMeta(id = "thumb-id", subject = "数学")
        File(d, "meta.json").writeText(MetaJson.serialize(meta))

        val thumbDir = tmp.newFolder("thumbs")
        File(thumbDir, "thumb-id.webp").writeText("thumb")

        index.rebuild(root, thumbDir)
        assertTrue(index.get("thumb-id")!!.thumbPath!!.endsWith("thumb-id.webp"))
        // 无缩略图 → thumbPath 为空
        File(thumbDir, "thumb-id.webp").delete()
        index.rebuild(root, thumbDir)
        assertNull(index.get("thumb-id")!!.thumbPath)
    }

    @Test
    fun `countBySubject 统计`() {
        index.upsert(makeMeta(id = "1", subject = "数学"), null, dirName = "a")
        index.upsert(makeMeta(id = "2", subject = "数学"), null, dirName = "b")
        index.upsert(makeMeta(id = "3", subject = "英语"), null, dirName = "c")
        assertEquals(mapOf("数学" to 2, "英语" to 1), index.countBySubject())
    }

    @Test
    fun `all 返回全部条目`() {
        index.upsert(makeMeta(id = "1", subject = "数学"), null, dirName = "a")
        index.upsert(makeMeta(id = "2", subject = "物理"), null, dirName = "b")
        assertEquals(2, index.all().size)
        assertTrue(index.all().map { it.id }.containsAll(listOf("1", "2")))
    }

    // ---------- 文本分词 ----------

    @Test
    fun `分词-中英混排`() {
        val tokens = TextTokenizer.tokenize("二次函数 f(x) 区间最值 2026")
        assertTrue(tokens.contains("二次"))
        assertTrue(tokens.contains("函数"))
        assertTrue(tokens.contains("f"))
        assertTrue(tokens.contains("x"))
        assertTrue(tokens.contains("2026"))
        assertTrue(tokens.contains("区间"))
        // 单字也在
        assertTrue(tokens.contains("二"))
    }

    @Test
    fun `分词-空白输入返回空集`() {
        assertTrue(TextTokenizer.tokenize("").isEmpty())
        assertTrue(TextTokenizer.tokenize("   ").isEmpty())
    }
}
