package com.misoup.app.settings.mine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关于信息与隐私摘要（模块 10 T06） */
class AboutInfoTest {

    @Test
    fun `链接均为 https`() {
        assertTrue(AboutInfo.GITHUB_URL.startsWith("https://"))
        assertTrue(AboutInfo.LICENSE_URL.startsWith("https://"))
        assertTrue(AboutInfo.PRIVACY_URL.startsWith("https://"))
        assertTrue(AboutInfo.DONATE_URL.startsWith("https://"))
    }

    @Test
    fun `开源许可为 GPL30`() {
        assertEquals("GPL-3.0", AboutInfo.LICENSE_NAME)
    }

    @Test
    fun `隐私摘要覆盖关键承诺 - 数据本地 无遥测 BYOK 未成年人`() {
        val summary = AboutInfo.privacySummary()
        assertTrue(summary.contains("本机"))
        assertTrue(summary.contains("遥测"))
        assertTrue(summary.contains("BYOK"))
        assertTrue(summary.contains("未成年人"))
    }

    @Test
    fun `摘要分条 - 非空且每条可读`() {
        val lines = AboutInfo.privacySummaryLines()
        assertTrue(lines.isNotEmpty())
        lines.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `摘要与实现一致 - 不夸大离线承诺`() {
        val summary = AboutInfo.privacySummary()
        // 配置 AI 增强时题目会发往第三方 API——必须如实声明，不得宣称完全本地
        assertTrue(summary.contains("第三方 API"))
    }
}
