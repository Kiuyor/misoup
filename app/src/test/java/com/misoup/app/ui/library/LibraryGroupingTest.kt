package com.misoup.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T01 分组逻辑：按科目分组，组内时间倒序，组间最新在前 */
class LibraryGroupingTest {

    @Test
    fun `空输入返回空列表`() {
        assertEquals(emptyList<SubjectGroup>(), LibraryGrouping.groupBySubject(emptyList()))
    }

    @Test
    fun `单科目-组内按 mtime 倒序`() {
        val old = makeEntry("a", "数学", mtime = 100)
        val mid = makeEntry("b", "数学", mtime = 300)
        val fresh = makeEntry("c", "数学", mtime = 500)
        val groups = LibraryGrouping.groupBySubject(listOf(old, mid, fresh))
        assertEquals(1, groups.size)
        assertEquals(listOf("c", "b", "a"), groups[0].entries.map { it.id })
    }

    @Test
    fun `多科目-组间按最新条目倒序-组内倒序`() {
        val mathNew = makeEntry("m1", "数学", mtime = 900)
        val mathOld = makeEntry("m2", "数学", mtime = 100)
        val engNew = makeEntry("e1", "英语", mtime = 700)
        val engOld = makeEntry("e2", "英语", mtime = 50)
        val chn = makeEntry("c1", "语文", mtime = 400)

        val groups = LibraryGrouping.groupBySubject(listOf(chn, engOld, mathOld, engNew, mathNew))
        assertEquals(listOf("数学", "英语", "语文"), groups.map { it.subject })
        assertEquals(listOf("m1", "m2"), groups[0].entries.map { it.id })
        assertEquals(listOf("e1", "e2"), groups[1].entries.map { it.id })
    }

    @Test
    fun `相同 mtime-保持稳定顺序不丢失`() {
        val a = makeEntry("a", "数学", mtime = 100)
        val b = makeEntry("b", "数学", mtime = 100)
        val groups = LibraryGrouping.groupBySubject(listOf(a, b))
        assertEquals(2, groups[0].entries.size)
    }

    @Test
    fun `损坏条目参与分组`() {
        val repair = makeEntry("repair:数学:x", "数学", mtime = 800, needsRepair = true)
        val normal = makeEntry("n", "数学", mtime = 300)
        val groups = LibraryGrouping.groupBySubject(listOf(normal, repair))
        assertEquals(1, groups.size)
        assertEquals(listOf("repair:数学:x", "n"), groups[0].entries.map { it.id })
    }

    @Test
    fun `自定义科目正常分组`() {
        val g = LibraryGrouping.groupBySubject(listOf(makeEntry("x", "竞赛数学", mtime = 1)))
        assertEquals("竞赛数学", g[0].subject)
    }

    @Test
    fun `subjectsOf 去重保序`() {
        val entries = listOf(
            makeEntry("a", "数学", mtime = 3),
            makeEntry("b", "英语", mtime = 2),
            makeEntry("c", "数学", mtime = 1)
        )
        assertEquals(listOf("数学", "英语"), LibraryGrouping.subjectsOf(entries))
        assertTrue(LibraryGrouping.subjectsOf(emptyList()).isEmpty())
    }
}
