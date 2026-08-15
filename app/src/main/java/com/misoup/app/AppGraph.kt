package com.misoup.app

import android.content.Context
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.store.LibraryRoot
import com.misoup.app.data.store.StoreService
import com.misoup.app.data.store.ThumbService
import com.misoup.app.data.store.WebpImageEncoder
import com.misoup.app.ui.library.OcrRetryHandler
import java.io.File

/**
 * 极简单例装配（工程暂无 DI 框架）：模块 02 UI 层的依赖注入入口。
 *
 * - 启动时构建 StoreService + 重建索引（一次性）
 * - [ocrRetry]：T10 识别重试入口；模块 01 的 CaptureCoordinator 落定后在此接入：
 *   `ocrRetry = OcrRetryHandler { id -> captureCoordinator.retry(id) }`
 */
object AppGraph {

    private var initialized = false

    /** 内存索引（启动全量扫描构建） */
    val index = LibraryIndex()

    /** 存储唯一写入口；init 后可用 */
    var store: StoreService? = null
        private set

    /** 识别重试处理器（模块 01 落定后注入） */
    var ocrRetry: OcrRetryHandler? = null
        private set

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        val root = LibraryRoot.get(appContext)
        val encoder = WebpImageEncoder()
        store = StoreService(root, index, encoder, ThumbService(appContext.cacheDir, encoder))
        // 启动扫描：<root>/<科目>/<条目目录>/meta.json → 内存索引 + 缩略图探测
        index.rebuild(root, File(appContext.cacheDir, "thumbs"))
        // T10 预留：模块 01 完成后
        // ocrRetry = OcrRetryHandler { id -> CaptureCoordinator.retry(id) }
    }
}
