package com.misoup.app.recognition.paddle.download

import com.misoup.app.recognition.paddle.model.ModelVersion
import com.misoup.app.recognition.paddle.model.PaddleModels

/**
 * 国内镜像地址约定与版本清单解析（T02，纯逻辑）
 *
 * 镜像目录结构（文件名即版本约定）：
 * ```
 * {base}/ppocr-v4-mobile/versions.txt      # 每行一个版本号，末行为最新
 * {base}/ppocr-v4-mobile/{version}/det.{version}.onnx
 * {base}/ppocr-v4-mobile/{version}/rec.{version}.onnx
 * {base}/ppocr-v4-mobile/{version}/cls.{version}.onnx
 * {base}/ppocr-v4-mobile/{version}/ppocr_keys_v1.txt
 * ```
 * [latestVersion] 与 [urlFor] 均可脱离网络纯测；真实镜像地址 ⏸ 待人工前置。
 */
object ModelMirror {

    /** 默认镜像基址（占位；真实国内镜像由施工/设置页提供） */
    const val DEFAULT_BASE = "https://models.misoup.local/mirror"

    const val MANIFEST_NAME = "versions.txt"

    /** 版本清单 URL：{base}/ppocr-v4-mobile/versions.txt */
    fun manifestUrl(base: String = DEFAULT_BASE): String =
        "$base/${PaddleModels.MODEL_TAG}/$MANIFEST_NAME"

    /** 单个模型文件 URL：{base}/ppocr-v4-mobile/{version}/{role}.{version}.onnx */
    fun urlFor(role: String, version: String, base: String = DEFAULT_BASE): String =
        "$base/${PaddleModels.MODEL_TAG}/$version/${PaddleModels.fileNameFor(role, version)}"

    /** 字典 URL（固定名） */
    fun dictUrl(version: String, base: String = DEFAULT_BASE): String =
        "$base/${PaddleModels.MODEL_TAG}/$version/${PaddleModels.DICT_FILE}"

    /**
     * 解析版本清单文本 → 按语义版本号升序去重排序的列表。
     * 非法行忽略；空清单 → 空列表。
     */
    fun parseManifest(text: String): List<ModelVersion> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ModelVersion.parse(it) }
            .distinct()
            .sorted()
            .toList()

    /** 清单最新版本；空清单 → null */
    fun latestVersion(text: String): ModelVersion? = parseManifest(text).lastOrNull()
}

/**
 * 下载/重下决策策略（T02/T08，纯逻辑）
 *
 * - 模型缺失或损坏 → 需要下载（[shouldDownload]）
 * - 本地版本 < 镜像最新 → 需要更新（[shouldUpdate]）
 * - 失败重试退避：1s → 2s → 4s … 封顶 60s（[retryDelayMs]）
 */
object DownloadPolicy {

    const val MAX_RETRY_DELAY_MS = 60_000L
    const val BASE_RETRY_DELAY_MS = 1_000L

    /** 缺失/损坏 → true */
    fun shouldDownload(missing: List<String>, corrupt: List<String>): Boolean =
        missing.isNotEmpty() || corrupt.isNotEmpty()

    /** 本地可用但版本落后 → true（模型更新入口） */
    fun shouldUpdate(localVersion: ModelVersion?, latest: ModelVersion?): Boolean =
        localVersion != null && latest != null && localVersion < latest

    /** 指数退避：attempt=1 → 1s，attempt=2 → 2s，attempt=3 → 4s…封顶 60s */
    fun retryDelayMs(attempt: Int): Long {
        val exp = (attempt - 1).coerceAtLeast(0)
        val shift = if (exp >= 63) Long.MAX_VALUE else BASE_RETRY_DELAY_MS shl exp
        return minOf(shift, MAX_RETRY_DELAY_MS).coerceAtLeast(0)
    }
}

/** 下载进度（单文件粒度） */
data class DownloadProgress(
    val role: String,
    val bytes: Long,
    val totalBytes: Long,
)

/** 下载结果 */
data class DownloadOutcome(
    val version: String,
    val files: List<String>,
)

/** 下载阶段状态（设置页「模型下载」入口占位数据源，T02） */
enum class DownloadStage { IDLE, DOWNLOADING, DONE, FAILED }

data class ModelDownloadState(
    val stage: DownloadStage = DownloadStage.IDLE,
    val version: String? = null,
    val error: String? = null,
    val progress: List<DownloadProgress> = emptyList(),
)

/**
 * 模型下载服务接口（T02 设置页入口由模块 10 填充 UI，本接口为服务契约）：
 * 从国内镜像拉取最新版本三件套 + 字典到 modelsDir，文件名即版本约定。
 * 实现需支持断点续传/失败重试（[DownloadPolicy.retryDelayMs]），
 * 下载完成后按 [com.misoup.app.recognition.paddle.model.ModelBundle.resolve] 自校验。
 */
interface ModelDownloadService {
    /** 下载最新版本；成功 → 已落地的版本号与文件列表 */
    suspend fun downloadLatest(
        modelsDir: java.io.File,
        base: String = ModelMirror.DEFAULT_BASE,
        onProgress: (DownloadProgress) -> Unit = {},
    ): Result<DownloadOutcome>
}
