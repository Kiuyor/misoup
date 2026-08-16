package com.misoup.app.settings.ai

import android.content.Context
import android.content.SharedPreferences
import com.misoup.app.settings.ai.RecognitionStrategy.Companion.DEFAULT

/**
 * 识别策略生产实现：普通 SharedPreferences（非敏感配置，明文即可）。
 * 接口化以便 JVM 单测注入内存实现。
 */
class PrefsStrategySettingsStore(context: Context) : StrategySettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): RecognitionStrategy =
        RecognitionStrategy.byId(prefs.getString(KEY_STRATEGY, null)) ?: DEFAULT

    override fun save(strategy: RecognitionStrategy) {
        prefs.edit().putString(KEY_STRATEGY, strategy.id).apply()
    }

    companion object {
        const val PREFS_NAME = "misoup_settings"
        const val KEY_STRATEGY = "recognition_strategy"
    }
}
