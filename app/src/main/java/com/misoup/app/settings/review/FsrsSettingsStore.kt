package com.misoup.app.settings.review

import android.content.Context
import android.content.SharedPreferences
import com.misoup.app.review.fsrs.Fsrs

/**
 * FSRS 调度参数持久化（模块 10 T04）
 *
 * 设置页「复习 → FSRS 参数（高级）」落盘：17 个 w 值 + short-term 开关。
 * - w 存为逗号分隔文本（[FsrsParamsCodec] 编解码）；解析失败回退默认参数
 * - enableShortTerm 存布尔
 *
 * 接口化以便 JVM 单测注入内存实现；生产实现 [PrefsFsrsSettingsStore] 依赖 Android。
 */
interface FsrsSettingsStore {

    /** 读取参数；无记录/损坏 → [Fsrs.Params] 默认 */
    fun load(): Fsrs.Params

    /** 保存参数（调用方需先经 [FsrsParamsCodec.validate]） */
    fun save(params: Fsrs.Params)
}

/** 生产实现：普通 SharedPreferences（FSRS 参数非敏感） */
class PrefsFsrsSettingsStore(context: Context) : FsrsSettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): Fsrs.Params {
        val wText = prefs.getString(KEY_W, null) ?: return Fsrs.Params()
        val w = FsrsParamsCodec.parseW(wText).getOrNull() ?: return Fsrs.Params()
        val shortTerm = prefs.getBoolean(KEY_SHORT_TERM, true)
        return Fsrs.Params(w = w, enableShortTerm = shortTerm)
    }

    override fun save(params: Fsrs.Params) {
        prefs.edit()
            .putString(KEY_W, FsrsParamsCodec.formatW(params.w))
            .putBoolean(KEY_SHORT_TERM, params.enableShortTerm)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "misoup_fsrs_settings"
        const val KEY_W = "fsrs_w"
        const val KEY_SHORT_TERM = "fsrs_short_term"
    }
}
