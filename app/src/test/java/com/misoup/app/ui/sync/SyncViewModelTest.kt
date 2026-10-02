package com.misoup.app.ui.sync

import com.misoup.app.sync.SyncBackoff
import com.misoup.app.sync.SyncReport
import com.misoup.app.sync.webdav.DavCredentialRepository
import com.misoup.app.sync.webdav.DavCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 同步设置 ViewModel 单测（模块 09 T05）：配置读写/手动触发/报告/失败指数退避
 *
 * - 凭据仓库 = 内存 DavCredentialStore（无 Android 依赖）
 * - 同步动作 = 脚本化 lambda（成功/失败序列）
 * - 主线程 = StandardTestDispatcher，用 advanceTimeBy 控制退避重试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {

    private class MemStore : DavCredentialStore {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun has(key: String): Boolean = map.containsKey(key)
    }

    private lateinit var store: MemStore
    private lateinit var repo: DavCredentialRepository
    private lateinit var dispatcher: TestDispatcher
    private var syncCalls = 0
    private var syncResults = ArrayDeque<SyncReport>()

    @Before
    fun setUp() {
        store = MemStore()
        repo = DavCredentialRepository(store)
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        syncCalls = 0
        syncResults = ArrayDeque()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(): SyncViewModel = SyncViewModel(
        credRepo = repo,
        syncAction = {
            syncCalls++
            syncResults.removeFirstOrNull() ?: SyncReport()
        },
        // F5：测试注入测试调度器，保证 runCurrent 同步推进（生产用 Dispatchers.IO）
        ioDispatcher = dispatcher
    )

    private fun okReport() = SyncReport(uploaded = 3, downloaded = 1)
    private fun failReport() = SyncReport(errors = listOf("列取远端失败: 模拟断网"))

    // ---------- 配置读写 ----------

    @Test
    fun `初始状态 - 未配置`() {
        val v = vm()
        assertFalse(v.state.value.isConfigured)
        assertEquals("", v.state.value.config.url)
    }

    @Test
    fun `保存合法 https 配置 - 落加密存储`() {
        val v = vm()
        v.saveConfig("https://dav.jianguoyun.com/dav/", "user", "pass")
        dispatcher.scheduler.runCurrent()
        assertTrue(v.state.value.isConfigured)
        assertEquals("https://dav.jianguoyun.com/dav/", repo.load().url)
        assertEquals("user", repo.load().username)
        assertEquals("pass", repo.load().password)
    }

    @Test
    fun `保存 http 配置 - 拒绝并警告`() {
        val v = vm()
        v.saveConfig("http://192.168.1.10/dav/", "user", "pass")
        dispatcher.scheduler.runCurrent()
        assertFalse(v.state.value.isConfigured)          // 未落盘
        assertFalse(repo.isConfigured)
        assertNotNull(v.state.value.message)
        assertTrue(v.state.value.message!!.contains("HTTPS"))
        // 输入框仍展示 http 警告（refreshConfig 检测到未保存的输入）
        v.refreshConfig()
        assertTrue(v.state.value.message!!.contains("HTTPS"))
    }

    @Test
    fun `保存不完整配置 - 拒绝并提示原因`() {
        val v = vm()
        v.saveConfig("", "", "")
        dispatcher.scheduler.runCurrent()
        assertFalse(v.state.value.isConfigured)
        assertTrue(v.state.value.message!!.contains("配置"))
    }

    @Test
    fun `清除凭据 - 一键清空并重置状态`() {
        val v = vm()
        v.saveConfig("https://dav.jianguoyun.com/dav/", "user", "pass")
        dispatcher.scheduler.runCurrent()
        assertTrue(v.state.value.isConfigured)

        v.clearCredentials()
        dispatcher.scheduler.runCurrent()
        assertFalse(v.state.value.isConfigured)
        assertFalse(repo.isConfigured)
        assertNull(v.state.value.lastReport)
    }

    // ---------- 手动触发与报告 ----------

    @Test
    fun `手动同步成功 - 报告展示 计数重置`() {
        syncResults.addLast(okReport())
        val v = vm()
        v.syncNow()
        dispatcher.scheduler.runCurrent()

        assertEquals(1, syncCalls)
        assertFalse(v.state.value.syncing)
        val r = v.state.value.lastReport!!
        assertEquals(3, r.uploaded)
        assertEquals(1, r.downloaded)
        assertEquals(0, v.state.value.failedAttempts)
        assertNull(v.state.value.retryInMs)
        assertTrue(v.state.value.message!!.contains("同步完成"))
    }

    @Test
    fun `同步中重复点击 - 忽略`() {
        syncResults.addLast(okReport())
        val v = vm()
        v.syncNow()
        v.syncNow()   // syncing 中，第二次应被忽略
        dispatcher.scheduler.runCurrent()
        assertEquals(1, syncCalls)
    }

    // ---------- 失败指数退避 ----------

    @Test
    fun `失败后 - 30 秒自动重试 成功即重置`() {
        syncResults.addLast(failReport())   // 第一次失败
        syncResults.addLast(okReport())     // 30s 后重试成功
        val v = vm()
        v.syncNow()
        dispatcher.scheduler.runCurrent()

        assertEquals(1, syncCalls)
        assertFalse(v.state.value.syncing)
        assertEquals(30_000L, v.state.value.retryInMs)
        assertEquals(1, v.state.value.failedAttempts)
        assertTrue(v.state.value.message!!.contains("30s 后自动重试"))

        // 推进 30s → 自动重试
        dispatcher.scheduler.advanceTimeBy(30_000L)
        dispatcher.scheduler.runCurrent()
        assertEquals(2, syncCalls)
        assertEquals(0, v.state.value.failedAttempts)
        assertNull(v.state.value.retryInMs)
        assertTrue(v.state.value.lastReport!!.isSuccess)
    }

    @Test
    fun `连续失败 - 退避 30s 到 2m 到 10m`() {
        syncResults.addLast(failReport())
        syncResults.addLast(failReport())
        syncResults.addLast(failReport())
        syncResults.addLast(okReport())
        val v = vm()
        v.syncNow()
        dispatcher.scheduler.runCurrent()
        assertEquals(30_000L, v.state.value.retryInMs)

        dispatcher.scheduler.advanceTimeBy(30_000L); dispatcher.scheduler.runCurrent()
        assertEquals(2, syncCalls)
        assertEquals(120_000L, v.state.value.retryInMs)

        dispatcher.scheduler.advanceTimeBy(120_000L); dispatcher.scheduler.runCurrent()
        assertEquals(3, syncCalls)
        assertEquals(600_000L, v.state.value.retryInMs)

        dispatcher.scheduler.advanceTimeBy(600_000L); dispatcher.scheduler.runCurrent()
        assertEquals(4, syncCalls)
        assertTrue(v.state.value.lastReport!!.isSuccess)
        assertEquals(0, v.state.value.failedAttempts)
    }

    @Test
    fun `失败报告带错误明细 - message 展示首条错误`() {
        syncResults.addLast(SyncReport(errors = listOf("上传失败: 数学/A", "下载失败: 物理/B")))
        val v = vm()
        v.syncNow()
        dispatcher.scheduler.runCurrent()
        assertTrue(v.state.value.message!!.contains("上传失败"))
        assertEquals(2, v.state.value.lastReport!!.errors.size)
    }

    // ---------- 自动同步开关 ----------

    @Test
    fun `自动同步默认关 可开启`() {
        val v = vm()
        assertFalse(v.state.value.autoSync)
        v.setAutoSync(true)
        assertTrue(v.state.value.autoSync)
        v.setAutoSync(false)
        assertFalse(v.state.value.autoSync)
    }

    @Test
    fun `失败重试中手动再次同步 - 取消旧重试 立即执行`() {
        syncResults.addLast(failReport())
        syncResults.addLast(okReport())
        val v = vm()
        v.syncNow()
        dispatcher.scheduler.runCurrent()
        assertEquals(1, syncCalls)
        assertNotNull(v.state.value.retryInMs)

        v.syncNow()   // 手动再次触发：取消退避中的自动重试
        dispatcher.scheduler.runCurrent()
        assertEquals(2, syncCalls)
        assertNull(v.state.value.retryInMs)
        assertTrue(v.state.value.lastReport!!.isSuccess)
    }

    @Test
    fun `自定义退避序列生效`() {
        val v = SyncViewModel(repo, { failReport() }, SyncBackoff(listOf(1_000L, 2_000L)), ioDispatcher = dispatcher)
        v.syncNow()
        dispatcher.scheduler.runCurrent()
        assertEquals(1_000L, v.state.value.retryInMs)
    }
}
