package com.misoup.app.ui.screens.mine

import androidx.lifecycle.ViewModel
import com.misoup.app.settings.data.CacheCleanResult
import com.misoup.app.settings.data.CacheCleanerLogic
import com.misoup.app.settings.mine.LibraryPathFormatter
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 数据设置状态（模块 10 T05）
 *
 * - 库目录：当前路径 + 默认/自定义状态（SAF 迁移选择由 UI 层发起，[migrateLibrary] 承接结果）
 * - WebDAV 配置状态（配置详情与编辑走既有「同步与备份」页 = onOpenSync 路由）
 * - 备份/恢复/导入/导出：入口按钮（模块 09 服务已在既有页面接线，此处展示状态）
 * - 清理缓存：缩略图缓存 + 识别缓存（[CacheCleanerLogic]）
 */
data class DataSettingsUiState(
    /** 库目录展示文案（[LibraryPathFormatter] 已格式化） */
    val libraryPath: String = LibraryPathFormatter.DEFAULT_PLACEHOLDER,
    /** 是否自定义库目录 */
    val libraryCustomized: Boolean = false,
    /** WebDAV 是否已配置完整（详情见同步页） */
    val webdavConfigured: Boolean = false,
    val cleaning: Boolean = false,
    val cleanResult: String? = null,
    val message: String? = null,
)

class DataSettingsViewModel(
    private val libraryPathProvider: () -> String,
    private val libraryCustomizedProvider: () -> Boolean,
    private val webdavConfiguredProvider: () -> Boolean,
    private val cacheDirsProvider: () -> List<File>,
    private val cleanAction: (List<File>) -> CacheCleanResult = CacheCleanerLogic::clean,
) : ViewModel() {

    private val _state = MutableStateFlow(DataSettingsUiState())
    val state: StateFlow<DataSettingsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update {
            it.copy(
                libraryPath = LibraryPathFormatter.displayPath(
                    path = libraryPathProvider(),
                    customized = libraryCustomizedProvider(),
                ),
                libraryCustomized = libraryCustomizedProvider(),
                webdavConfigured = webdavConfiguredProvider(),
            )
        }
    }

    /** 清理缓存（同步执行；目录小，无需协程） */
    fun cleanCache() {
        if (_state.value.cleaning) return
        _state.update { it.copy(cleaning = true) }
        val result = cleanAction(cacheDirsProvider())
        _state.update {
            it.copy(
                cleaning = false,
                cleanResult = "已清理 ${CacheCleanerLogic.formatBytes(result.bytesFreed)}" +
                    "（${result.filesDeleted} 个文件 / ${result.dirsCleaned} 个目录）",
            )
        }
    }

    /**
     * 库目录迁移（SAF 选目录结果承接）。
     * ⚠️ 工程现状：库存储为 File 路径（[LibraryRoot.get]），SAF Uri 无法直接作为 File 使用；
     * 数据复制 + 索引重建属后续版本接入，当前版本提示用户仍使用原目录。
     */
    fun migrateLibrary(uri: String?) {
        _state.update {
            it.copy(
                message = if (uri.isNullOrBlank()) {
                    "已取消库目录迁移"
                } else {
                    "已选择新目录。数据复制与索引重建将在后续版本接入，当前版本仍使用原目录。"
                },
            )
        }
    }

    /** 通用提示（备份/恢复/导入/导出入口说明） */
    fun showMessage(text: String) {
        _state.update { it.copy(message = text) }
    }
}
