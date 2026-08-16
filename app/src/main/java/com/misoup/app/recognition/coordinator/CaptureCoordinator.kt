package com.misoup.app.recognition.coordinator

import android.net.Uri
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.FsrsState
import com.misoup.app.data.model.Images
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.model.OcrState
import com.misoup.app.data.store.ImageAsset
import com.misoup.app.data.store.ImageFormat
import com.misoup.app.data.store.StoreService
import com.misoup.app.pipeline.PreprocessResult
import com.misoup.app.recognition.Recognizer
import com.misoup.app.recognition.queue.RecognitionQueue
import com.misoup.app.recognition.queue.RecognitionStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T08 采集协调器：逐张 加载 → 预处理 → 落库(ocr=pending) → 入识别队列
 *
 * 模块 01 文档定义：`CaptureCoordinator(store, index, recognizer)` +
 * `submit(images: List<Uri>): Flow<CaptureProgress>` + `retry(id)`。
 * 核心铁律：**录入永不阻塞**——图片先落库，识别后台异步（队列在 [scope] 上跑）。
 *
 * 泛型 [T]：生产 = Bitmap（相机/相册解码产物）；JVM 单测注入字符串桩，
 * 经 [loader]/[preprocess]/[assetFactory] 三处注入点完全绕开 Android 类。
 *
 * 进度模型（UI 全屏进度页数据源）：
 * - [submit] 返回 Flow<CaptureProgress>：顶部汇总条（"已收录 3/5"）
 * - [batch] StateFlow：逐张收录阶段（STORING/STORED/FAILED），与队列 [status]
 *   合成逐张徽标（收录中/识别中/已收录/失败）
 */
class CaptureCoordinator<T>(
    private val store: StoreService,
    private val index: LibraryIndex,
    recognizer: Recognizer<T>,
    private val loader: CaptureImageLoader<T>,
    private val preprocess: PreprocessRunner<T>,
    private val scope: CoroutineScope,
    /** 落库 IO 执行线程（FINDING 5：生产=Default 防主线程阻塞；测试注入测试调度器保持时序可控） */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val assetFactory: (T, ImageFormat) -> ImageAsset,
    private val clock: () -> String = { com.misoup.app.review.fsrs.FsrsTime.toIso(System.currentTimeMillis()) ?: "" },
    private val defaultSubject: String = CaptureDefaults.DEFAULT_SUBJECT
) {

    /** 识别队列（内部持有；单线程 worker 已在 [scope] 上启动） */
    private val queue = RecognitionQueue(store, index, recognizer, scope, clock).also { it.start() }

    init {
        // FINDING 6：进程被杀后遗留的 ocr=pending 条目（识别未完成）在启动时自动重新入队，
        // 否则永久 pending、文本.md 永为空（拆题/批量录入被杀进程场景）
        requeuePending()
    }

    /** 启动补扫：索引中 ocrStatus=pending 的条目从磁盘重载原图重新入队识别。
     *  基于 init 时刻快照——只补扫「上次进程遗留」的 pending，不动态扫描（避免误抓刚落库的新条目）。 */
    private fun requeuePending() {
        val snapshot = index.all()
            .filter { !it.needsRepair && it.ocrStatus == "pending" }
        if (snapshot.isEmpty()) return
        scope.launch {
            snapshot.forEach { entry ->
                runCatching {
                    val path = store.imagePathById(entry.id).getOrNull() ?: return@forEach
                    val bitmap = loader.load(CaptureSource("file://$path")) ?: return@forEach
                    val result = preprocess.run(bitmap)
                    queue.enqueue(RecognitionQueue.JobSpec(entry.id, result.enhanced))
                }
            }
        }
    }

    /** 识别状态透传（进度页徽标/列表 OCR 状态共用） */
    val recognitionStatus: StateFlow<Map<String, RecognitionStatus>> = queue.status

    /** 当前批次逐张收录状态（UI 全屏进度页） */
    private val _batch = MutableStateFlow<List<BatchItem>>(emptyList())
    val batch: StateFlow<List<BatchItem>> = _batch.asStateFlow()

    /**
     * 提交一批图片（Uri 列表：相册多选/相机单拍/连拍产物）。
     * 逐张：加载→预处理→create(pending)→入队，每张完成后 emit 一次进度。
     * 单张失败不阻塞整批（降级：计入 failed，继续下一张）。
     */
    fun submit(images: List<Uri>): Flow<CaptureProgress> =
        submitSources(images.map { CaptureSource(it.toString()) })

    /** 纯逻辑入口（JVM 单测用；语义与 [submit] 完全一致） */
    fun submitSources(sources: List<CaptureSource>): Flow<CaptureProgress> = flow {
        _batch.value = sources.map { BatchItem(it, StorePhase.STORING) }
        var ok = 0
        val failed = mutableListOf<String>()
        sources.forEachIndexed { index, source ->
            val metaId = processOne(source)
            val success = metaId != null
            if (success) {
                ok++
            } else {
                failed += source.uri
            }
            // 更新批次状态（按 index 定位，保证顺序一致；metaId 供进度页关联识别状态）
            _batch.value = _batch.value.toMutableList().also { list ->
                list[index] = list[index].copy(
                    storePhase = if (success) StorePhase.STORED else StorePhase.FAILED,
                    metaId = metaId
                )
            }
            emit(CaptureProgress(ok, failed.toList()))
        }
    }

    /**
     * 单张处理：加载 → 预处理 → 落库(pending) → 入识别队列。
     * @return 成功 → 落库条目的 meta.id；失败 → null
     */
    private suspend fun processOne(source: CaptureSource): String? = runCatching {
        val bitmap = loader.load(source) ?: return null
        val result = preprocess.run(bitmap)
        val meta = buildMeta()
        // FINDING 5：落库（WebP 编码 + 缩略图 + 文件写）切后台线程——
        // 否则 flow 在主线程收集时编码阻塞 UI（批量录入卡死/ANR）
        val stored = withContext(ioDispatcher) {
            store.create(meta, assetFactory(bitmap, ImageFormat.WEBP), text = null)
        }
        if (stored.isFailure) return null
        queue.enqueue(RecognitionQueue.JobSpec(meta.id, result.enhanced))
        meta.id
    }.getOrNull()

    /**
     * 识别失败重试（T09 队列重试；失败位图缓存命中才生效）。
     * F6：进程重启后内存缓存丢失 → 从磁盘重载条目原图，重新走预处理并入队，
     * 「识别失败」条目的重试按钮不再静默失效。
     */
    fun retry(id: String) {
        if (queue.retry(id)) return
        scope.launch {
            val path = store.imagePathById(id).getOrNull() ?: return@launch
            val bitmap = loader.load(CaptureSource("file://$path")) ?: return@launch
            val result = preprocess.run(bitmap)
            queue.enqueue(RecognitionQueue.JobSpec(id, result.enhanced))
        }
    }

    /**
     * 外部按条目 id 直接入队识别（FINDING 3：整页拆题等场景落库后必须入队，
     * 否则条目永久 ocr=pending、文本.md 永为空）。
     * @param bitmap 已预处理（enhanced）的位图
     */
    fun enqueueRecognition(id: String, bitmap: T) {
        queue.enqueue(RecognitionQueue.JobSpec(id, bitmap))
    }

    /** 停止识别队列 worker（测试收尾/界面销毁时调用；幂等） */
    fun stop() {
        queue.stop()
    }

    /** 构建落库 meta：ocr=pending，id 为 UUID（模块 01 文档字段约定） */
    private fun buildMeta(): MisoupMeta {
        val now = clock()
        return MisoupMeta(
            id = java.util.UUID.randomUUID().toString(),
            subject = defaultSubject,
            questionType = "其他",
            errorReasons = emptyList(),
            tags = emptyList(),
            knowledgePoints = emptyList(),
            note = "",
            source = "",
            createdAt = now,
            updatedAt = now,
            images = Images(original = MisoupConfigConst.ORIGINAL_IMAGE_NAME),
            textFile = MisoupConfigConst.TEXT_FILE_NAME,
            ocr = OcrState(engine = "paddle", status = "pending"),
            fsrs = FsrsState(),
            reviews = emptyList()
        )
    }
}

/** 采集进度（汇总条数据源）：ok=已落库数，failed=失败来源 Uri 列表 */
data class CaptureProgress(
    val ok: Int,
    val failed: List<String>
)

/** 采集来源（Uri 字符串化，便于 JVM 测试与日志） */
data class CaptureSource(val uri: String)

/** 单张收录阶段（批次进度页用） */
enum class StorePhase { STORING, STORED, FAILED }

/** 批次单张状态（收录阶段 + 落库后的 meta.id） */
data class BatchItem(
    val source: CaptureSource,
    val storePhase: StorePhase,
    val metaId: String? = null
)

/** 图片加载器（生产走 ContentResolver/ImageDecoder；测试注入桩） */
fun interface CaptureImageLoader<T> {
    /** 从来源加载位图；失败/取消 → null（调用方按收录失败降级） */
    suspend fun load(source: CaptureSource): T?
}

/** 预处理执行器（生产包装 PreprocessPipeline.run；测试注入桩） */
fun interface PreprocessRunner<T> {
    suspend fun run(bitmap: T): PreprocessResult<T>
}

/** 采集默认值（文档未定稿处由本模块裁决） */
object CaptureDefaults {
    /** 默认科目：采集流程未提供科目选择（模块 01 文档缺口），统一入「未分类」，编辑页可改 */
    const val DEFAULT_SUBJECT = "未分类"
}
