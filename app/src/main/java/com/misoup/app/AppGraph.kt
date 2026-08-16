package com.misoup.app

import android.content.Context
import android.graphics.Bitmap
import com.misoup.app.ai.TextLlmClient
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.security.CredentialStore
import com.misoup.app.data.store.BitmapImageAsset
import com.misoup.app.data.store.LibraryRoot
import com.misoup.app.data.store.StoreService
import com.misoup.app.data.store.ThumbService
import com.misoup.app.data.store.WebpImageEncoder
import com.misoup.app.pipeline.PreprocessPipeline
import com.misoup.app.recognition.coordinator.CaptureCoordinator
import com.misoup.app.recognition.paddle.PaddleOcrEngine
import com.misoup.app.recognition.paddle.PaddleOcrRecognizer
import com.misoup.app.recognition.visionllm.CredentialStoreLlmKeyStore
import com.misoup.app.recognition.visionllm.LlmConfigRepository
import com.misoup.app.recognition.visionllm.PrefsLlmSettingsStore
import com.misoup.app.recognition.visionllm.VisionLlmClient
import com.misoup.app.sync.DavSyncService
import com.misoup.app.sync.webdav.CredentialStoreBridge
import com.misoup.app.sync.webdav.DavCredentialRepository
import com.misoup.app.sync.webdav.SardineDavClient
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
 *   识别引擎 = 模块 04 PaddleOCR（离线 ONNX；模型缺失时返回明确错误 → 队列标记 failed 可重试）
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

    // ---------- 模块 09 同步 ----------

    private var appContext: Context? = null

    /** WebDAV 凭据仓库（加密存储；init 后可用） */
    val syncCredentialRepo: DavCredentialRepository by lazy {
        DavCredentialRepository(
            CredentialStoreBridge(CredentialStore.getInstance(appContext!!))
        )
    }

    /** WebDAV 同步服务（网络层 = sardine-android 生产实现；init 后可用） */
    val syncService: DavSyncService by lazy {
        DavSyncService(
            cfg = { syncCredentialRepo.load() },
            store = store!!,
            index = index,
            client = SardineDavClient { syncCredentialRepo.load() },
            libraryRoot = libraryRoot!!
        )
    }

    /** AI 增强（模块 07）：LLM 配置仓库（与模块 05 共用同一份配置/Key） */
    var llmConfig: LlmConfigRepository? = null
        private set

    /** AI 增强（模块 07）：文本 LLM 通道（变式出题/题目答疑共用；复用 05 配置与限速保护） */
    var textLlm: TextLlmClient? = null
        private set

    /** 采集/识别后台作用域（App 级存活；worker 单线程在队列内部保证） */
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        this.appContext = appContext
        val root = LibraryRoot.get(appContext)
        libraryRoot = root
        val encoder = WebpImageEncoder()
        store = StoreService(root, index, encoder, ThumbService(appContext.cacheDir, encoder))
        // 启动扫描：<root>/<科目>/<条目目录>/meta.json → 内存索引 + 缩略图探测
        index.rebuild(root, File(appContext.cacheDir, "thumbs"))
        // 模块 01：采集协调器（识别引擎 = 模块 04 PaddleOCR；模型缺失 → 明确错误 → failed 可重试）
        captureCoordinator = CaptureCoordinator(
            store = store!!,
            index = index,
            recognizer = PaddleOcrRecognizer(PaddleOcrEngine(appContext)),
            loader = AndroidImageLoader(appContext),
            preprocess = RealPreprocessRunner,
            scope = captureScope,
            assetFactory = { bitmap, format -> BitmapImageAsset(bitmap, format) }
        )
        // T10 识别重试：详情页/列表页失败徽标按钮 → CaptureCoordinator.retry
        ocrRetry = OcrRetryHandler { id -> captureCoordinator?.retry(id) }
        // 模块 07：AI 增强通道（复用 05 配置仓库与 Key；文本模型变式/答疑共用）
        val llmRepo = LlmConfigRepository(
            settings = PrefsLlmSettingsStore(appContext),
            credentials = CredentialStoreLlmKeyStore(CredentialStore.getInstance(appContext))
        )
        llmConfig = llmRepo
        textLlm = TextLlmClient(
            okHttp = VisionLlmClient.defaultHttpClient(),
            cfg = { llmRepo.load() }
        )
    }
}

/**
 * 生产预处理执行器（T08）：包装 PreprocessPipeline.run（模块 01 预处理链）。
 * 兄弟代理实现；本模块只调用，不实现。
 */
object RealPreprocessRunner : com.misoup.app.recognition.coordinator.PreprocessRunner<Bitmap> {
    override suspend fun run(bitmap: Bitmap) = PreprocessPipeline.run(bitmap)
}
