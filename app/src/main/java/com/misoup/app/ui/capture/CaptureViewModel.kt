package com.misoup.app.ui.capture

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misoup.app.AppGraph
import com.misoup.app.recognition.coordinator.BatchItem
import com.misoup.app.recognition.coordinator.CaptureProgress
import com.misoup.app.recognition.coordinator.CaptureSource
import com.misoup.app.recognition.queue.RecognitionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 采集会话桥：跨导航页面传递待提交 Uri 列表
 * （导航参数无法携带 List<Uri>，拍照页/首页选图 → 进度页 用此轻量桥）
 */
object CaptureSession {
    @Volatile
    var pendingUris: List<Uri> = emptyList()

    /** 取走并清空（进度页首次组合时消费） */
    fun take(): List<Uri> {
        val uris = pendingUris
        pendingUris = emptyList()
        return uris
    }
}

/**
 * 采集 ViewModel（T10 进度页）
 *
 * - [submitPending]：消费 [CaptureSession] 中待提交的 Uri（拍照/选图产物），
 *   交给 CaptureCoordinator（落库+入队），逐张进度收集到 [progress]（汇总条）
 * - [batch] / [recognitionStatus]：Coordinator 状态透传，合成逐张徽标
 * - [retry]：识别失败重试入口（与详情页 OcrRetryHandler 同一链路）
 */
class CaptureViewModel : ViewModel() {

    private val coordinator = checkNotNull(AppGraph.captureCoordinator) { "CaptureCoordinator 未初始化" }

    /** 汇总条进度（null=尚无批次） */
    private val _progress = MutableStateFlow<CaptureProgress?>(null)
    val progress: StateFlow<CaptureProgress?> = _progress.asStateFlow()

    /** 批次逐张收录状态（Coordinator 透传） */
    val batch: StateFlow<List<BatchItem>> = coordinator.batch

    /** 识别队列状态（Coordinator 透传） */
    val recognitionStatus: StateFlow<Map<String, RecognitionStatus>> = coordinator.recognitionStatus

    /** 消费会话桥中的待提交批次（进度页进入时调用；空 = 无操作） */
    fun submitPending() {
        submit(CaptureSession.take())
    }

    /** 提交采集批次；空列表 = 无操作 */
    fun submit(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val sources = uris.map { CaptureSource(it.toString()) }
        viewModelScope.launch {
            coordinator.submitSources(sources).collect { p ->
                _progress.value = p
            }
        }
    }

    /** 识别失败重试 */
    fun retry(id: String) {
        coordinator.retry(id)
    }
}
