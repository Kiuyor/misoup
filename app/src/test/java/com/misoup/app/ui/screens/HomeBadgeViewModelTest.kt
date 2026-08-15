package com.misoup.app.ui.screens

import com.misoup.app.FakeImageAsset
import com.misoup.app.FakeImageEncoder
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.FsrsState
import com.misoup.app.data.store.StoreService
import com.misoup.app.makeMeta
import com.misoup.app.review.fsrs.FsrsTime
import com.misoup.app.review.queue.QueueLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 首页角标（T07）：今日待复习数量与复习 tab 同口径 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeBadgeViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var index: LibraryIndex
    private lateinit var store: StoreService
    private val img = FakeImageAsset(100, 100)
    private val NOW = 1_000_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        index = LibraryIndex()
        store = StoreService(tmp.newFolder("library"), index, FakeImageEncoder())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `角标数量-新卡与到期计入未来due不计`() = runTest {
        store.create(makeMeta(id = "n", fsrs = FsrsState()), img, "新卡").getOrThrow()
        store.create(
            makeMeta(id = "d", fsrs = FsrsState(state = 2, due = FsrsTime.toIso(NOW - 86_400_000L))),
            img, "到期"
        ).getOrThrow()
        store.create(
            makeMeta(id = "f", fsrs = FsrsState(state = 2, due = FsrsTime.toIso(NOW + 86_400_000L))),
            img, "未来"
        ).getOrThrow()

        val vm = HomeBadgeViewModel(QueueLoader(store, index))
        vm.refresh(NOW)
        advanceUntilIdle()
        assertEquals(2, vm.todayCount.value)
    }

    @Test
    fun `空库-角标为0`() = runTest {
        val vm = HomeBadgeViewModel(QueueLoader(store, index))
        vm.refresh(NOW)
        advanceUntilIdle()
        assertEquals(0, vm.todayCount.value)
    }
}
