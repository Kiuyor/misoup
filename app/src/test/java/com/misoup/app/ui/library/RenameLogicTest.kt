package com.misoup.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T09 重命名逻辑：id 不变、仅目录名变化；目录名安全校验 */
class RenameLogicTest {

    @Test
    fun `按题干生成目录名-格式 yyyyMMdd_HHmmss_前12字`() {
        val name = RenameLogic.computeDirName(1784300000000L, "二次函数在区间上的最值问题")
        assertTrue(name.matches(Regex("\\d{8}_\\d{6}_.+")))
        // 摘要取题干前 12 字（去空白/公式符后截断）
        assertTrue(name.endsWith("二次函数在区间上的最值问"))
    }

    @Test
    fun `空题干-仅时间戳`() {
        val name = RenameLogic.computeDirName(1784300000000L, "")
        assertTrue(name.matches(Regex("\\d{8}_\\d{6}")))
    }

    @Test
    fun `公式符号与空白被剔除`() {
        val name = RenameLogic.computeDirName(1784300000000L, "求 \$x^2\$ 的 最小值")
        assertFalse(name.contains("$"))
        assertFalse(name.contains(" "))
        assertFalse(name.contains("^"))
    }

    @Test
    fun `rename 结果- id 不变仅目录名变化`() {
        val result = RenameLogic.rename(
            id = "uuid-123",
            oldDirName = "20260816_140523_旧题",
            newDirName = "20260816_150000_新题"
        )!!
        assertEquals("uuid-123", result.id)
        assertEquals("20260816_140523_旧题", result.oldDirName)
        assertEquals("20260816_150000_新题", result.newDirName)
    }

    @Test
    fun `同名重命名-返回 null 无需操作`() {
        val result = RenameLogic.rename("id", "同名", "同名")
        assertNull(result)
    }

    @Test
    fun `非法目录名-返回 null`() {
        assertNull(RenameLogic.rename("id", "旧", ""))                 // 空
        assertNull(RenameLogic.rename("id", "旧", "   "))              // 空白
        assertNull(RenameLogic.rename("id", "旧", "a/b"))              // 路径分隔符
        assertNull(RenameLogic.rename("id", "旧", "a\\b"))             // Windows 分隔符
        assertNull(RenameLogic.rename("id", "旧", ".hidden"))          // 隐藏目录
    }

    @Test
    fun `isSafeName 边界`() {
        assertTrue(RenameLogic.isSafeName("正常目录名"))
        assertTrue(RenameLogic.isSafeName("a b c"))
        assertFalse(RenameLogic.isSafeName(""))
        assertFalse(RenameLogic.isSafeName(".."))
        assertFalse(RenameLogic.isSafeName("/abs"))
    }
}
