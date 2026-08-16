package com.misoup.app.ui.sync

import android.content.Context

/**
 * 自动同步开关存储（F2 修复：开关必须持久化，重启不丢）
 *
 * - [AutoSyncStore]：抽象（JVM 可测；测试用 [MemAutoSyncStore]）
 * - [PrefsAutoSyncStore]：生产实现，SharedPreferences 落盘
 *
 * 触发点：录入流程完成后（CaptureProgressScreen）读取该开关，
 * 开启时自动调用一次增量同步（见 CaptureProgressScreen）。
 */
interface AutoSyncStore {
    var enabled: Boolean
}

/** 内存实现（JVM 单测用；默认构造注入） */
class MemAutoSyncStore : AutoSyncStore {
    override var enabled: Boolean = false
}

/** SharedPreferences 实现（生产；独立于加密凭据区，非敏感开关） */
class PrefsAutoSyncStore(context: Context) : AutoSyncStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    companion object {
        const val PREFS_NAME = "misoup_sync_prefs"
        const val KEY_AUTO_SYNC = "auto_sync"
    }
}
