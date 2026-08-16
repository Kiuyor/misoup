package com.misoup.app.recognition.paddle.download

import com.misoup.app.recognition.paddle.model.ModelBundle
import com.misoup.app.recognition.paddle.model.ModelVersion
import com.misoup.app.recognition.paddle.model.PaddleModels
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 基于 OkHttp 的镜像下载实现（T02）。
 *
 * 真实网络链路依赖镜像就绪（⏸ 待人工前置），JVM 单测只覆盖纯逻辑
 * （ModelMirror/DownloadPolicy）与 [HttpModelDownloadService.downloadTo] 的
 * 文件落地与幂等规则（用 file:// 桩响应注入）。
 *
 * 流程：拉取 versions.txt → 最新版本 → 逐个下载三件套 + 字典到 modelsDir
 * （带版本文件名）→ 校验体积 → 失败抛 [DownloadException]（可重试）。
 */
class HttpModelDownloadService(
    private val client: OkHttpClient = OkHttpClient(),
) : ModelDownloadService {

    override suspend fun downloadLatest(
        modelsDir: File,
        base: String,
        onProgress: (DownloadProgress) -> Unit,
    ): Result<DownloadOutcome> = runCatching {
        modelsDir.mkdirs()
        val manifest = fetchText(ModelMirror.manifestUrl(base))
            .getOrElse { throw DownloadException("版本清单获取失败: $it", retryable = true) }
        val latest = ModelMirror.latestVersion(manifest)
            ?: throw DownloadException("版本清单为空或格式非法: $manifest", retryable = false)
        val version = latest.toString()

        // 下载四件（按固定顺序 det→rec→cls→dict）
        val files = mutableListOf<String>()
        PaddleModels.ROLES.forEach { role ->
            val target = File(modelsDir, PaddleModels.fileNameFor(role, version))
            downloadTo(ModelMirror.urlFor(role, version, base), target, role, onProgress)
            files += target.name
        }
        val dict = File(modelsDir, PaddleModels.DICT_FILE)
        downloadTo(ModelMirror.dictUrl(version, base), dict, PaddleModels.DICT_FILE, onProgress)
        files += dict.name

        // 下载后自校验：体积不达标 → 判损坏，可重试
        val bundle = ModelBundle.resolve(modelsDir)
        if (!bundle.valid) {
            throw DownloadException(
                "下载完成但校验失败: missing=${bundle.missing} corrupt=${bundle.corrupt}",
                retryable = true
            )
        }
        DownloadOutcome(version = version, files = files)
    }

    /** 单文件下载：流式写 .part 临时文件 + 进度回调；HTTP 非 2xx / IO 异常 → 可重试异常 */
    private fun downloadTo(
        url: String,
        target: File,
        role: String,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) {
                throw DownloadException("下载 $role 失败: HTTP ${response.code}", retryable = true)
            }
            val body = response.body ?: throw DownloadException("下载 $role 失败: 空响应体", retryable = true)
            val total = body.contentLength()
            target.parentFile?.mkdirs()
            // FINDING 7：原子下载——先写 .part 临时文件，成功后 rename 为正式文件。
            // 中断/失败不残留半写文件（否则体积校验仅有下限时截断文件会"过检"，OCR 永久失败）。
            val part = File(target.parentFile, "${target.name}.part")
            try {
                part.outputStream().use { out ->
                    var done = 0L
                    body.byteStream().use { input ->
                        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            out.write(buf, 0, read)
                            done += read
                            onProgress(DownloadProgress(role, done, total))
                        }
                    }
                }
                if (!part.renameTo(target)) {
                    part.delete()
                    throw DownloadException("下载 $role 失败: 文件落地失败", retryable = true)
                }
            } catch (e: Exception) {
                part.delete()
                throw e
            }
        }
    }

    private fun fetchText(url: String): Result<String> = runCatching {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string() ?: ""
        }
    }
}

/** 下载异常：retryable=true 表示可重试（网络/校验失败），false 表示配置错误（勿重试） */
class DownloadException(
    message: String,
    val retryable: Boolean,
) : Exception(message)
