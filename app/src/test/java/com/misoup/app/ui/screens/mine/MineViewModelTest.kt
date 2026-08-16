package com.misoup.app.ui.screens.mine

import com.misoup.app.review.stats.Stats
import com.misoup.app.settings.compat.RomKind
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** 「我的」设置页框架 ViewModel（模块 10 T01） */
@OptIn(ExperimentalCoroutinesApi::class)
class MineViewModelTest {

    private lateinit var dispatcher: kotlinx.coroutines.test.TestDispatcher

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(
        stats: Stats? = null,
        path: String = "/data/user/0/com.misoup.app/files/library",
        customized: Boolean = false,
        version: String = "0.1.0",
        manufacturer: String = "vivo",
    ) = MineViewModel(
        statsProvider = { stats },
        libraryPathProvider = { path },
        libraryCustomizedProvider = { customized },
        versionNameProvider = { version },
        manufacturerProvider = { manufacturer },
    )

    private fun sampleStats() = Stats(
        total = 12,
        bySubject = mapOf("数学" to 8, "物理" to 4),
        reviewRate = 0.75,
        accuracy = 0.8,
        trend30d = (0 until 30).map { LocalDate.of(2026, 8, 1) to 1 },
        mastery = emptyMap(),
    )

    @Test
    fun `初始加载 - 统计与库路径与版本就绪`() {
        val v = vm(stats = sampleStats())
        dispatcher.scheduler.advanceUntilIdle()
        val s = v.state.value
        assertEquals(12, s.stats?.total)
        assertTrue(s.libraryPath.contains("/data/user/0"))
        assertEquals("0.1.0", s.versionName)
        assertFalse(s.loading)
    }

    @Test
    fun `vivo 厂商 - 白名单引导文案就绪`() {
        val v = vm(manufacturer = "vivo")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(RomKind.VIVO, v.state.value.romKind)
        assertNotNull(v.state.value.romGuide)
        assertTrue(v.state.value.romGuide!!.contains("白名单"))
    }

    @Test
    fun `非白名单厂商 - 无引导文案`() {
        val v = vm(manufacturer = "samsung")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(RomKind.OTHER, v.state.value.romKind)
        assertNull(v.state.value.romGuide)
    }

    @Test
    fun `默认目录 - 展示路径加默认标记`() {
        val v = vm(path = "/storage/emulated/0/Android/data/com.misoup.app/files/library", customized = false)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(v.state.value.libraryPath.endsWith("（默认）"))
    }

    @Test
    fun `自定义目录 - 无默认标记`() {
        val v = vm(path = "/sdcard/MisoupLibrary", customized = true)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("/sdcard/MisoupLibrary", v.state.value.libraryPath)
    }

    @Test
    fun `stats 为空 - 展示占位`() {
        val v = vm(stats = null)
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(v.state.value.stats)
    }

    @Test
    fun `refresh 重读数据`() {
        var stats: Stats? = null
        val v = MineViewModel(
            statsProvider = { stats },
            libraryPathProvider = { "/a" },
            libraryCustomizedProvider = { false },
            versionNameProvider = { "0.1.0" },
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(v.state.value.stats)
        stats = sampleStats()
        v.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(12, v.state.value.stats?.total)
    }
}
