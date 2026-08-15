package com.misoup.app.data.store

import com.misoup.app.data.model.MisoupConfigConst
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T06 配置与命名：NameGenerator 自动命名 / MisoupConfig 常量与路径
 */
class NameGeneratorTest {

    // 2026-08-16 14:05:23 +08:00（epoch 验证过）
    private val ts: Long = 1786860323000L

    @Test
    fun `正常命名-时间戳+摘要前12字`() {
        // 剔除公式符后："已知二次函数f(x)ax²+bx+c在区间1,3上的最值问题"，取前12字
        val name = NameGenerator.generate(ts, "已知二次函数f(x)=ax²+bx+c在区间[1,3]上的最值问题")
        assertEquals("20260816_140523_已知二次函数f(x)ax", name)
        // 前缀格式：yyyyMMdd_HHmmss_
        assertTrue(name.startsWith("20260816_140523_"))
    }

    @Test
    fun `摘要剔除公式符号`() {
        // $ \ { } ^ = 等 LaTeX 符号剔除；括号/逗号/数字保留
        val name = NameGenerator.generate(ts, "\$f(x)=x^2\$\\{a,b\\} 求最值")
        assertEquals("20260816_140523_f(x)x2a,b求最值", name)
    }

    @Test
    fun `摘要剔除空白`() {
        assertEquals("20260816_140523_二次函数区间最值", NameGenerator.generate(ts, "  二次 函数  区间最值  "))
    }

    @Test
    fun `摘要超过12字截断`() {
        // 一二三四五六七八九十甲乙 = 12 字（丙是第 13 字，应被截掉）
        val name = NameGenerator.generate(ts, "一二三四五六七八九十甲乙丙丁戊己庚辛")
        assertEquals("20260816_140523_一二三四五六七八九十甲乙", name)
        val summary = name.substringAfterLast("_")
        assertEquals(12, summary.length)
    }

    @Test
    fun `摘要不足12字全保留`() {
        assertEquals("20260816_140523_短题", NameGenerator.generate(ts, "短题"))
    }

    @Test
    fun `空摘要-仅时间戳无尾下划线`() {
        assertEquals("20260816_140523", NameGenerator.generate(ts, ""))
        assertEquals("20260816_140523", NameGenerator.generate(ts, "   "))
        assertEquals("20260816_140523", NameGenerator.generate(ts, "\$\\^_{}"))
    }

    @Test
    fun `cleanSummary 独立行为`() {
        // 输入字面量：二次 函数\$x^2\$区间最值；剔除 $ ^ 与空白 → 二次函数x2区间最值
        assertEquals("二次函数x2区间最值", NameGenerator.cleanSummary("二次 函数\\\$x^2\\\$区间最值"))
        assertEquals("", NameGenerator.cleanSummary(""))
        assertTrue(NameGenerator.cleanSummary("长长长长长长长长长长长长长长").length <= 12)
    }

    @Test
    fun `不同时间戳生成不同名字`() {
        val n1 = NameGenerator.generate(ts, "题目")
        val n2 = NameGenerator.generate(ts + 1000, "题目")
        assertFalse(n1 == n2)
        assertTrue(n2.startsWith("20260816_140524_"))
    }

    @Test
    fun `配置常量符合定稿`() {
        assertEquals(1, MisoupConfigConst.SCHEMA_VERSION)
        assertEquals("meta.json", MisoupConfigConst.META_FILE_NAME)
        assertEquals("文本.md", MisoupConfigConst.TEXT_FILE_NAME)
        assertEquals("原图.webp", MisoupConfigConst.ORIGINAL_IMAGE_NAME)
        assertEquals("原图-raw.jpg", MisoupConfigConst.RAW_IMAGE_NAME)
        assertEquals("去手写.webp", MisoupConfigConst.CLEANED_IMAGE_NAME)
        assertEquals("变式", MisoupConfigConst.VARIANT_DIR_NAME)
        assertEquals("library", MisoupConfigConst.DEFAULT_LIBRARY_DIR_NAME)
        assertEquals(80, MisoupConfigConst.IMAGE_QUALITY)          // WebP 质量 80
        assertEquals(2048, MisoupConfigConst.IMAGE_MAX_LONG_EDGE)  // 长边 2048 封顶
        assertEquals(256, MisoupConfigConst.THUMB_SIZE)            // 缩略图 256
        assertEquals("yyyyMMdd_HHmmss", MisoupConfigConst.NAME_DATE_PATTERN)
        assertEquals(12, MisoupConfigConst.NAME_SUMMARY_LENGTH)
    }

    @Test
    fun `库目录路径结构-科目与条目`() {
        // 纯 JVM 验证路径拼接（不触碰 Android Context）
        val root = java.io.File("/tmp/misoup-lib")
        val subjectDir = MisoupConfig.subjectDir(root, "数学")
        val entryDir = MisoupConfig.entryDir(root, "数学", "20260816_140523_二次函数区间最值")
        assertEquals(java.io.File("/tmp/misoup-lib/数学"), subjectDir)
        assertEquals(java.io.File("/tmp/misoup-lib/数学/20260816_140523_二次函数区间最值"), entryDir)
        assertTrue(entryDir.parentFile == subjectDir)
    }

    @Test
    fun `预设枚举列表齐全`() {
        assertEquals(9, MisoupConfig.presetSubjects.size)
        assertEquals(listOf("选择题", "填空题", "解答题", "实验题", "计算题", "其他"), MisoupConfig.presetQuestionTypes)
        assertEquals(listOf("概念不清", "计算失误", "审题错误", "思路错误", "粗心大意", "其他"), MisoupConfig.presetErrorReasons)
    }

    @Test
    fun `LibraryRoot 预留迁移接口`() {
        // SAF 迁移接口：set 后 get 返回自定义路径；reset 回默认
        LibraryRoot.reset()
        assertFalse(LibraryRoot.isCustomized())
        val custom = java.io.File("/sdcard/MisoupLibrary")
        LibraryRoot.set(custom)
        assertTrue(LibraryRoot.isCustomized())
        // 不触碰 Context 的部分可验证：get 的默认分支需要 Context，这里仅验证 set 状态
        LibraryRoot.reset()
        assertFalse(LibraryRoot.isCustomized())
        LibraryRoot.set(null)
        assertFalse(LibraryRoot.isCustomized())
    }
}
