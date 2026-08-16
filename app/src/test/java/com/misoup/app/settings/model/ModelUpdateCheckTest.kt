package com.misoup.app.settings.model

import com.misoup.app.recognition.paddle.model.ModelBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型更新检查决策（模块 10 T03） */
class ModelUpdateCheckTest {

    private fun bundle(
        valid: Boolean = true,
        version: String? = "1.0.0",
        missing: List<String> = emptyList(),
        corrupt: List<String> = emptyList(),
    ) = ModelBundle(null, null, null, null, version, missing, corrupt).let {
        if (valid) it.copy(missing = emptyList(), corrupt = emptyList()) else it
    }

    @Test
    fun `清单拉取失败 - CheckFailed`() {
        val d = ModelUpdateCheck.evaluate(bundle(), manifestText = null)
        assertTrue(d is UpdateDecision.CheckFailed)
        assertTrue((d as UpdateDecision.CheckFailed).reason.contains("拉取失败"))
    }

    @Test
    fun `清单无法解析 - CheckFailed`() {
        val d = ModelUpdateCheck.evaluate(bundle(), manifestText = "not-a-version\n")
        assertTrue(d is UpdateDecision.CheckFailed)
        assertTrue((d as UpdateDecision.CheckFailed).reason.contains("解析失败"))
    }

    @Test
    fun `本地缺失 - NeedsDownload`() {
        val d = ModelUpdateCheck.evaluate(
            bundle(valid = false, missing = listOf("det")),
            manifestText = "1.1.0"
        )
        assertTrue(d is UpdateDecision.NeedsDownload)
        assertTrue((d as UpdateDecision.NeedsDownload).reason.contains("缺失"))
    }

    @Test
    fun `本地损坏 - NeedsDownload`() {
        val d = ModelUpdateCheck.evaluate(
            bundle(valid = false, corrupt = listOf("rec")),
            manifestText = "1.1.0"
        )
        assertTrue(d is UpdateDecision.NeedsDownload)
        assertTrue((d as UpdateDecision.NeedsDownload).reason.contains("损坏"))
    }

    @Test
    fun `有新版 - UpdateAvailable 且版本正确`() {
        val d = ModelUpdateCheck.evaluate(bundle(version = "1.0.0"), manifestText = "1.0.0\n1.1.0")
        assertTrue(d is UpdateDecision.UpdateAvailable)
        val u = d as UpdateDecision.UpdateAvailable
        assertEquals("1.0.0", u.localVersion)
        assertEquals("1.1.0", u.latestVersion)
    }

    @Test
    fun `已是最新 - UpToDate`() {
        val d = ModelUpdateCheck.evaluate(bundle(version = "1.1.0"), manifestText = "1.0.0\n1.1.0")
        assertTrue(d is UpdateDecision.UpToDate)
        assertEquals("1.1.0", (d as UpdateDecision.UpToDate).localVersion)
    }

    @Test
    fun `语义版本比较 - 1_9_0 小于 1_10_0`() {
        val d = ModelUpdateCheck.evaluate(bundle(version = "1.9.0"), manifestText = "1.10.0")
        assertTrue(d is UpdateDecision.UpdateAvailable)
        assertEquals("1.10.0", (d as UpdateDecision.UpdateAvailable).latestVersion)
    }

    @Test
    fun `describe - 各决策有文案`() {
        assertTrue(ModelUpdateCheck.describe(UpdateDecision.UpToDate("1.0.0")).contains("最新"))
        assertTrue(ModelUpdateCheck.describe(UpdateDecision.UpdateAvailable("1.0.0", "1.1.0")).contains("1.1.0"))
        assertTrue(ModelUpdateCheck.describe(UpdateDecision.NeedsDownload("原因")).contains("下载"))
        assertTrue(ModelUpdateCheck.describe(UpdateDecision.CheckFailed("原因")).contains("原因"))
    }
}
