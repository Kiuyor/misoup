package com.misoup.app.ui.screens.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.review.stats.Stats
import com.misoup.app.settings.compat.RomCompat
import com.misoup.app.settings.compat.RomKind
import com.misoup.app.settings.mine.LibraryPathFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「我的」设置页框架状态（模块 10 T01）
 *
 * 聚合：统计概览（复用模块 03 StatsCalculator）+ 库目录路径 + 版本号 + ROM 白名单引导。
 * 全部依赖以函数注入（无 Android 依赖），JVM 单测可全量覆盖。
 */
data class MineUiState(
    /** 学情统计（模块 03）；未就绪 = null */
    val stats: Stats? = null,
    /** 库目录展示文案（已按 [LibraryPathFormatter] 格式化） */
    val libraryPath: String = LibraryPathFormatter.DEFAULT_PLACEHOLDER,
    /** 当前 ROM（vivo/小米/华为 → 白名单引导） */
    val romKind: RomKind = RomKind.OTHER,
    /** 白名单引导文案；非白名单 ROM 为 null */
    val romGuide: String? = null,
    /** 版本号（versionName） */
    val versionName: String = "",
    val loading: Boolean = false,
    val message: String? = null,
)

class MineViewModel(
    private val statsProvider: suspend () -> Stats?,
    private val libraryPathProvider: () -> String,
    private val libraryCustomizedProvider: () -> Boolean,
    private val versionNameProvider: () -> String,
    private val manufacturerProvider: () -> String = { "" },
) : ViewModel() {

    private val _state = MutableStateFlow(MineUiState())
    val state: StateFlow<MineUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** 刷新全部概览信息（进页面 / 迁移库目录后调用） */
    fun refresh() {
        _state.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            val stats = statsProvider()
            val romKind = RomCompat.detect(manufacturerProvider())
            _state.update {
                it.copy(
                    stats = stats,
                    libraryPath = LibraryPathFormatter.displayPath(
                        path = libraryPathProvider(),
                        customized = libraryCustomizedProvider(),
                    ),
                    romKind = romKind,
                    romGuide = RomCompat.guideText(romKind),
                    versionName = versionNameProvider(),
                    loading = false,
                )
            }
        }
    }
}
