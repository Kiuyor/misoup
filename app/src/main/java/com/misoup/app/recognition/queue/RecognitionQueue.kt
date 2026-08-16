package com.misoup.app.recognition.queue

import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.StoreService
import com.misoup.app.recognition.Recognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 失败位图缓存上限（FINDING 4：防批量失败时位图无限累积 OOM） */
private const val MAX_FAILED_JOBS = 20

/** 识别阶段（进度页徽标 / 列表 OCR 状态共用） */
enum class RecognitionPhase { PENDING, DONE, FAILED }

/** 单条识别状态（按条目 id 索引；error 供进度页/极客排障展示） */
data class RecognitionStatus(
    val phase: RecognitionPhase,
    val error: String? = null,
)

/**
 * T09 单线程识别队列
 *
 * 模块 01 文档：`Channel<JobSpec>` 单线程 worker 循环消费，防模型并发；
 * 状态流转 pending → done/failed 先行落地，识别引擎（模块 M3）后填。
 *
 * 职责：
 * - [enqueue]：任务入队，立即置 PENDING（进度页「识别中…」即时可见）
 * - worker：单协程顺序消费，调 [Recognizer.recognize]
 * - 成功回写（T09 文档）：写 文本.md + meta.ocr.status=done + 倒排刷新（indexText）
 * - 失败：meta.ocr.status=failed + 缓存失败位图，[retry] 重试（不重新解码）
 *
 * 铁律：单条任务任何异常（识别器/回写）不得杀死 worker 循环，逐条 runCatching。
 *
 * @param store 存储写入口（回写 文本.md / meta.ocr）
 * @param index 内存索引（回写后经 StoreService 内部刷新倒排，无需直接调用）
 * @param recognizer 识别器实现（模块 M3 填充；现为 DummyRecognizer 占位）
 * @param scope worker 生命周期作用域（测试注入 TestScope，生产用应用级 scope）
 * @param clock 时间源（meta.ocr.at 回写；测试注入固定时间）
 */
class RecognitionQueue<T>(
    private val store: StoreService,
    private val index: LibraryIndex,
    private val recognizer: Recognizer<T>,
    private val scope: CoroutineScope,
    private val clock: () -> String,
) {

    /** 识别任务规格（嵌套于队列：仅队列内部消费与重试缓存使用） */
    data class JobSpec<T>(
        val id: String,
        val bitmap: T,
        val attempt: Int = 1,
    )

    private val jobs = Channel<JobSpec<T>>(Channel.UNLIMITED)

    private val _status = MutableStateFlow<Map<String, RecognitionStatus>>(emptyMap())

    /** 识别状态透传（进度页徽标 / 详情页 OCR 状态共用数据源） */
    val status: StateFlow<Map<String, RecognitionStatus>> = _status.asStateFlow()

    /** 失败任务缓存（retry 直接复用位图，不重新解码；ConcurrentHashMap 防 UI 线程并发读） */
    private val failedJobs = ConcurrentHashMap<String, JobSpec<T>>()

    @Volatile
    private var worker: Job? = null

    /** 启动 worker；幂等（重复调用不重建）。单线程消费保证识别器不并发。 */
    fun start() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (job in jobs) {
                // 单条异常不杀循环：识别器/回写任一失败都仅影响本条
                runCatching { process(job) }
            }
        }
    }

    /** 停止 worker（不再消费；scope 取消时自动生效，一般无需显式调用） */
    fun stop() {
        worker?.cancel()
        worker = null
    }

    /** 提交识别任务；立即置 PENDING（UI 即时反馈），worker 惰性启动 */
    fun enqueue(job: JobSpec<T>) {
        _status.update { it + (job.id to RecognitionStatus(RecognitionPhase.PENDING)) }
        start()
        jobs.trySend(job)
    }

    /**
     * 识别失败重试：命中失败缓存（[failedJobs]）才生效——只重试「已落库但识别失败」的条目。
     *
     * @return true = 已重新入队；false = 缓存未命中（进程重启后失败位图已丢失）。
     * 未命中时由调用方（CaptureCoordinator）从磁盘重载原图重新入队（F6）。
     */
    fun retry(id: String): Boolean {
        val cached = failedJobs[id] ?: return false
        enqueue(cached.copy(attempt = cached.attempt + 1))
        return true
    }

    /** 处理单条任务：识别 → 回写 → 状态流转 */
    private suspend fun process(job: JobSpec<T>) {
        val outcome = runCatching { recognizer.recognize(job.bitmap) }
            .getOrElse { Result.failure(it) }
        if (outcome.isSuccess) {
            writeBackSuccess(job.id, outcome.getOrNull())
            failedJobs.remove(job.id)
            _status.update { it + (job.id to RecognitionStatus(RecognitionPhase.DONE)) }
        } else {
            cacheFailed(job)
            writeBackFailed(job.id, outcome.exceptionOrNull()?.message)
        }
    }

    /** 失败位图缓存（重试复用，不重新解码）；FINDING 4：设容量上限防长链失败累积内存 */
    private fun cacheFailed(job: JobSpec<T>) {
        if (failedJobs.size >= MAX_FAILED_JOBS) {
            failedJobs.keys.firstOrNull()?.let { failedJobs.remove(it) }
        }
        failedJobs[job.id] = job
    }

    /** 识别成功回写：文本.md + meta.ocr.status=done + 倒排刷新（T09 文档约定） */
    private suspend fun writeBackSuccess(id: String, text: String?) {
        if (!text.isNullOrBlank()) {
            // F4 修复：用户已在识别窗口内手动编辑过 文本.md → 跳过覆盖，尊重用户输入；
            // 读取失败（文件不可读等）也跳过覆盖（保守，不丢用户数据）。
            val existing = store.readTextById(id).getOrNull()
            if (existing == null || existing.isBlank()) {
                store.updateText(id, text)   // 写 文本.md + 倒排刷新（indexText）+ mtime touch
            }
        }
        store.update(id) { meta ->
            meta.copy(ocr = meta.ocr.copy(status = "done", at = clock()))
        }
    }

    /** 识别失败回写：meta.ocr.status=failed（可重试），状态透传失败原因 */
    private suspend fun writeBackFailed(id: String, error: String?) {
        store.update(id) { meta ->
            meta.copy(ocr = meta.ocr.copy(status = "failed", at = clock()))
        }
        _status.update { it + (id to RecognitionStatus(RecognitionPhase.FAILED, error)) }
    }
}
