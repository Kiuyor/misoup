package com.misoup.app.ui.screens.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.recognition.paddle.download.DownloadOutcome
import com.misoup.app.recognition.paddle.model.ModelBundle
import com.misoup.app.settings.model.ModelStatusFormatter
import com.misoup.app.settings.model.ModelUpdateCheck
import com.misoup.app.settings.model.UpdateDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 识别模型设置状态（模块 10 T03）
 *
 * OCR 引擎状态/版本（[ModelBundle] 盘点）+ 检查更新（镜像清单 → [ModelUpdateCheck]）
 * + 下载最新模型（[ModelDownloadService] 入口）。
 * 依赖函数注入（manifest 拉取与下载动作由 UI 层接真实服务），JVM 单测全量覆盖。
 */
data class RecognitionModelUiState(
    val statusText: String = "",
    val healthy: Boolean = false,
    val version: String? = null,
    val checking: Boolean = false,
    val updateText: String? = null,
    val downloading: Boolean = false,
    val downloadResult: String? = null,
)

class RecognitionModelViewModel(
    private val statusProvider: () -> ModelBundle,
    private val manifestProvider: suspend () -> String?,
    private val downloadAction: suspend () -> Result<DownloadOutcome>,
) : ViewModel() {

    private val _state = MutableStateFlow(RecognitionModelUiState())
    val state: StateFlow<RecognitionModelUiState> = _state.asStateFlow()

    init {
        refreshStatus()
    }

    /** 刷新本地模型状态（进页面/下载完成后调用） */
    fun refreshStatus() {
        val bundle = statusProvider()
        _state.update {
            it.copy(
                statusText = ModelStatusFormatter.describe(bundle),
                healthy = ModelStatusFormatter.isHealthy(bundle),
                version = bundle.version,
            )
        }
    }

    /** 检查更新：拉取镜像版本清单 → 决策文案 */
    fun checkUpdate() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true, updateText = null) }
        viewModelScope.launch {
            val manifest = manifestProvider()
            val decision = ModelUpdateCheck.evaluate(statusProvider(), manifest)
            _state.update {
                it.copy(checking = false, updateText = ModelUpdateCheck.describe(decision))
            }
        }
    }

    /** 下载最新模型（成功后刷新状态） */
    fun download() {
        if (_state.value.downloading) return
        _state.update { it.copy(downloading = true, downloadResult = null) }
        viewModelScope.launch {
            val result = downloadAction()
            refreshStatus()
            _state.update {
                it.copy(
                    downloading = false,
                    downloadResult = result.fold(
                        onSuccess = { o -> "下载完成：v${o.version}（${o.files.size} 个文件）" },
                        onFailure = { e -> "下载失败：${e.message ?: "未知错误"}" },
                    ),
                )
            }
        }
    }
}
