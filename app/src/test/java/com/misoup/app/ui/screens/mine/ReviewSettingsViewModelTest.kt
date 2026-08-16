package com.misoup.app.ui.screens.mine

import com.misoup.app.review.fsrs.Fsrs
import com.misoup.app.settings.review.FsrsSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 复习设置 ViewModel（模块 10 T04） */
class ReviewSettingsViewModelTest {

    private class MemFsrsStore : FsrsSettingsStore {
        var saved: Fsrs.Params? = null
        override fun load(): Fsrs.Params = saved ?: Fsrs.Params()
        override fun save(params: Fsrs.Params) { saved = params }
    }

    @Test
    fun `初始加载 - 默认参数格式化为 17 个数值`() {
        val store = MemFsrsStore()
        val v = ReviewSettingsViewModel(store)
        val s = v.state.value
        assertTrue(s.wText.contains("0.40255"))
        assertTrue(s.wValid)
        assertNull(s.wError)
        assertTrue(s.enableShortTerm)
        assertEquals(17, s.wText.split(",").size)
    }

    @Test
    fun `非法输入 - 实时报错并禁止保存`() {
        val v = ReviewSettingsViewModel(MemFsrsStore())
        v.onWTextChanged("0.1, 0.2")
        assertFalse(v.state.value.wValid)
        assertTrue(v.state.value.wError!!.contains("需要 17 个参数"))
    }

    @Test
    fun `合法输入 - 保存落盘`() {
        val store = MemFsrsStore()
        val v = ReviewSettingsViewModel(store)
        val custom = (1..17).joinToString(", ") { (it * 0.5).toString() }
        v.onWTextChanged(custom)
        assertTrue(v.state.value.wValid)
        v.setEnableShortTerm(false)
        v.save()
        assertTrue(store.saved != null)
        assertEquals(17, store.saved!!.w.size)
        assertFalse(store.saved!!.enableShortTerm)
        assertTrue(v.state.value.message!!.contains("已保存"))
    }

    @Test
    fun `short-term 开关 - 本地状态切换`() {
        val v = ReviewSettingsViewModel(MemFsrsStore())
        v.setEnableShortTerm(false)
        assertFalse(v.state.value.enableShortTerm)
    }

    @Test
    fun `恢复默认 - 重置为 FSRS45 初始参数并落盘`() {
        val store = MemFsrsStore()
        val v = ReviewSettingsViewModel(store)
        v.onWTextChanged("0.1, 0.2")
        v.resetDefaults()
        assertTrue(v.state.value.wValid)
        assertTrue(store.saved!!.w.contentEquals(Fsrs.DEFAULT_W))
        assertTrue(v.state.value.wText.contains("15.69105"))
    }

    @Test
    fun `V2 占位 - 提醒时间与开关仅本地状态`() {
        val v = ReviewSettingsViewModel(MemFsrsStore())
        v.setReminderTime("07:30")
        assertEquals("07:30", v.state.value.reminderTime)
        v.setReminderEnabled(true)
        assertTrue(v.state.value.reminderEnabled)
        assertTrue(v.state.value.message!!.contains("V2"))
    }
}
