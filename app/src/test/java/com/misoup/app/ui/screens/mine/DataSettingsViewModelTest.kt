package com.misoup.app.ui.screens.mine

import com.misoup.app.settings.data.CacheCleanResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数据设置 ViewModel（模块 10 T05） */
class DataSettingsViewModelTest {

    private var path = "/data/user/0/com.misoup.app/files/library"
    private var customized = false
    private var webdavConfigured = false
    private var cacheDirs: List<File> = emptyList()

    private fun vm() = DataSettingsViewModel(
        libraryPathProvider = { path },
        libraryCustomizedProvider = { customized },
        webdavConfiguredProvider = { webdavConfigured },
        cacheDirsProvider = { cacheDirs },
        cleanAction = { dirs -> CacheCleanResult(dirs.size, dirs.size * 2, 1024L) },
    )

    @Test
    fun `初始状态 - 库路径与 WebDAV 状态`() {
        val v = vm()
        assertTrue(v.state.value.libraryPath.contains("/data/user/0"))
        assertFalse(v.state.value.webdavConfigured)
    }

    @Test
    fun `自定义目录 - 展示无默认标记`() {
        customized = true
        path = "/sdcard/Misoup"
        val v = vm()
        assertEquals("/sdcard/Misoup", v.state.value.libraryPath)
    }

    @Test
    fun `WebDAV 已配置 - 状态就绪`() {
        webdavConfigured = true
        val v = vm()
        assertTrue(v.state.value.webdavConfigured)
    }

    @Test
    fun `清理缓存 - 展示释放统计`() {
        cacheDirs = listOf(File("/cache/thumbs"), File("/cache/ocr"))
        val v = vm()
        v.cleanCache()
        val s = v.state.value
        assertFalse(s.cleaning)
        assertTrue(s.cleanResult!!.contains("1 KB"))
        assertTrue(s.cleanResult!!.contains("4 个文件"))
    }

    @Test
    fun `迁移库目录 - 已选与取消两种提示`() {
        val v = vm()
        v.migrateLibrary(null)
        assertTrue(v.state.value.message!!.contains("取消"))

        v.migrateLibrary("content://com.android.externalstorage.documents/tree/primary%3AMisoup")
        assertTrue(v.state.value.message!!.contains("已选择新目录"))
        assertTrue(v.state.value.message!!.contains("后续版本"))
    }

    @Test
    fun `入口说明 - 备份导出提示不误导`() {
        val v = vm()
        v.showMessage("备份：SAF 保存文件选择将在后续版本接入（模块 09 服务已就绪）")
        assertTrue(v.state.value.message!!.contains("模块 09 服务已就绪"))
    }

    @Test
    fun `刷新 - 重读路径与状态`() {
        val v = vm()
        path = "/new/path"
        customized = true
        v.refresh()
        assertEquals("/new/path", v.state.value.libraryPath)
        assertTrue(v.state.value.libraryCustomized)
    }
}
