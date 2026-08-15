package com.misoup.app

import android.content.Context
import android.graphics.Bitmap
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.BitmapImageAsset
import com.misoup.app.data.store.LibraryRoot
import com.misoup.app.data.store.StoreService
import com.misoup.app.data.store.ThumbService
import com.misoup.app.data.store.WebpImageEncoder
import com.misoup.app.pipeline.PreprocessPipeline
import com.misoup.app.recognition.DummyRecognizer
import com.misoup.app.recognition.coordinator.CaptureCoordinator
import com.misoup.app.ui.capture.AndroidImageLoader
import com.misoup.app.ui.library.OcrRetryHandler
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 极简单例装配（工程暂无 DI 框架）：模块 01 采集链 + 模块 02 UI 层的依赖注入入口。
 *
 * - 启动时构建 StoreService + 重建索引（一次性）
 * - [captureCoordinator]：T08 采集协调器（落库 + 识别队列）；
 *   识别引擎暂用 [DummyRecognizer]（T09 状态流转先行，模块 05 接入真实 OCR）
 * - [ocrRetry]：T10 识别重试入口 = CaptureCoordinator.retry（详情页/列表页共用）
 */
object AppGraph {

    private var initialized = false

    /** 内存索引（启动全量扫描构建） */
    val index = LibraryIndex()

    /** 存储唯一写入口；init 后可用 */
    var store: StoreService? = null
        private set

    /** 库根目录（模块 03 遮罩持久化等按目录寻址用；init 后可用） */
    var libraryRoot: File? = null
        private set

    /** 采集协调器（模块 01：落库 + 识别队列 + 重试）；init 后可用 */
    var captureCoordinator: CaptureCoordinator<Bitmap>? = null
        private set

    /** 识别重试处理器（详情页/列表页 OCR 失败重试按钮共用） */
    var ocrRetry: OcrRetryHandler? = null
        private set

    /** 采集/识别后台作用域（App 级存活；worker 单线程在队列内部保证） */
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        val root = LibraryRoot.get(appContext)
        libraryRoot = root
        val encoder = WebpImageEncoder()
        store = StoreService(root, index, encoder, ThumbService(appContext.cacheDir, encoder))
        // 启动扫描：<root>/<科目>/<条目目录>/meta.json → 内存索引 + 缩略图探测
        index.rebuild(root, File(appContext.cacheDir, "thumbs"))
        // 模块 01：采集协调器（识别引擎占位 → 模块 05 替换 DummyRecognizer）
        captureCoordinator = CaptureCoordinator(
            store = store!!,
            index = index,
            recognizer = DummyRecognizer(),
            loader = AndroidImageLoader(appContext),
            preprocess = RealPreprocessRunner,
            scope = captureScope,
            assetFactory = { bitmap, format -> BitmapImageAsset(bitmap, format) }
        )
        // T10 识别重试：详情页/列表页失败徽标按钮 → CaptureCoordinator.retry
        ocrRetry = OcrRetryHandler { id -> captureCoordinator?.retry(id) }
    }
}

/**
 * 生产预处理执行器（T08）：包装 PreprocessPipeline.run（模块 01 预处理链）。
 * 兄弟代理实现；本模块只调用，不实现。
 */
object RealPreprocessRunner : com.misoup.app.recognition.coordinator.PreprocessRunner<Bitmap> {
    override suspend fun run(bitmap: Bitmap) = PreprocessPipeline.run(bitmap)
}
