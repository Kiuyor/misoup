package com.misoup.app.ui.screens.mine

import com.misoup.app.recognition.paddle.download.DownloadOutcome
import com.misoup.app.recognition.paddle.model.ModelBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 识别模型设置 ViewModel（模块 10 T03） */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionModelViewModelTest {

    private lateinit var dispatcher: kotlinx.coroutines.test.TestDispatcher
    private var bundle = healthyBundle()
    private var manifest: String? = "1.0.0"
    private var downloadResults = ArrayDeque<Result<DownloadOutcome>>()

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        bundle = healthyBundle()
        manifest = "1.0.0"
        downloadResults = ArrayDeque()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun healthyBundle() = ModelBundle(
        det = null, rec = null, cls = null, dict = null,
        version = "1.0.0", missing = emptyList(), corrupt = emptyList()
    )

    private fun vm() = RecognitionModelViewModel(
        statusProvider = { bundle },
        manifestProvider = { manifest },
        downloadAction = { downloadResults.removeFirstOrNull() ?: Result.success(DownloadOutcome("1.1.0", listOf("det", "rec", "cls", "dict"))) },
    )

    @Test
    fun `初始状态 - 状态文案与版本就绪`() {
        val v = vm()
        val s = v.state.value
        assertTrue(s.statusText.contains("正常"))
        assertEquals("1.0.0", s.version)
        assertTrue(s.healthy)
    }

    @Test
    fun `模型缺失 - 状态文案提示下载`() {
        bundle = healthyBundle().copy(missing = listOf("det"))
        val v = vm()
        assertFalse(v.state.value.healthy)
        assertTrue(v.state.value.statusText.contains("缺失"))
    }

    @Test
    fun `检查更新 - 发现新版本`() {
        manifest = "1.0.0\n1.1.0"
        val v = vm()
        v.checkUpdate()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(v.state.value.checking)
        assertTrue(v.state.value.updateText!!.contains("1.1.0"))
    }

    @Test
    fun `检查更新 - 已是最新`() {
        val v = vm()
        v.checkUpdate()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.updateText!!.contains("最新"))
    }

    @Test
    fun `检查更新 - 清单拉取失败提示`() {
        manifest = null
        val v = vm()
        v.checkUpdate()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.updateText!!.contains("拉取失败"))
    }

    @Test
    fun `下载 - 成功刷新状态并提示版本`() {
        val v = vm()
        v.download()
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(v.state.value.downloading)
        assertTrue(v.state.value.downloadResult!!.contains("1.1.0"))
    }

    @Test
    fun `下载 - 失败提示原因`() {
        downloadResults.add(Result.failure(IllegalStateException("网络超时")))
        val v = vm()
        v.download()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.downloadResult!!.contains("下载失败"))
        assertTrue(v.state.value.downloadResult!!.contains("网络超时"))
    }
}
