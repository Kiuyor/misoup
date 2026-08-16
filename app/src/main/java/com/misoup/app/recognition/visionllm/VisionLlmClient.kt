package com.misoup.app.recognition.visionllm

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI 兼容视觉 LLM 客户端（模块 05 T02）
 *
 * - 请求体：messages[0] = {role:user, content:[image_url(base64 数据 URI), text(PROMPT)]}
 * - 超时 60s（生产客户端用 [defaultHttpClient] 构建；测试注入自定义 client）
 * - HTTP 429 → 退避 10s 重试 1 次（[RETRY_BACKOFF_MILLIS]，免费通道共享额度保护）
 * - 返回非 JSON → 重试 1 次 → 失败（降级由调用方 Coordinator 处理）
 * - 未配置/未启用 → 直接失败（无网络调用，无 Key 自动走离线）
 *
 * 泛型 [I]：生产 = Bitmap（[BitmapVisionLlmClient]），JVM 单测 = String 桩，
 * 经 [imageToDataUrl] 注入点完全绕开 Android 类（工程既有模式，同 CaptureCoordinator<T>）。
 *
 * @param okHttp 执行器（生产 [defaultHttpClient]；测试 fake 拦截器 client）
 * @param cfg 实时配置源（() -> LlmConfig；设置页改动即时生效，无需重建客户端）
 * @param imageToDataUrl 图片 → data:image/png;base64,... 编码器
 * @param backoffMillis 429 退避时长（默认 10s；测试注入 0 或依赖虚拟时间）
 */
open class VisionLlmClient<I>(
    private val okHttp: OkHttpClient,
    private val cfg: () -> LlmConfig,
    private val imageToDataUrl: (I) -> String,
    private val backoffMillis: Long = RETRY_BACKOFF_MILLIS,
) {

    /**
     * 结构化识别：一张图 → 题干/答案/解析（公式 $LaTeX$）。
     * @return 成功 → [StructuredContent]；失败 → 原因（调用方按策略降级）
     */
    open suspend fun structure(image: I): Result<StructuredContent> {
        val config = cfg()
        if (!config.isReady()) {
            return Result.failure(IllegalStateException("视觉 LLM 未配置或未启用"))
        }
        val dataUrl = imageToDataUrl(image)
        val request = chatRequest(
            config,
            VisionLlmRequestFactory.buildChatBody(dataUrl, VisionPrompt.PROMPT, config.model)
        )
        var lastError: Throwable = IllegalStateException("未知错误")
        repeat(MAX_CONTENT_ATTEMPTS) {
            val body = executeWith429Backoff(request)
            if (body.isFailure) {
                lastError = body.exceptionOrNull() ?: lastError
                return Result.failure(lastError)   // HTTP/网络失败：不再内容重试
            }
            val content = VisionLlmRequestFactory.parseChatResponse(body.getOrThrow())
            if (content.isFailure) {
                lastError = content.exceptionOrNull() ?: lastError
                return@repeat
            }
            val parsed = VisionJsonParser.parse(content.getOrThrow())
            if (parsed.isSuccess) return parsed
            lastError = parsed.exceptionOrNull() ?: lastError
            // 非 JSON 输出：继续下一轮 = 重试 1 次
        }
        return Result.failure(lastError)
    }

    /**
     * 连通测试（T04）：1 次最小纯文本请求（content="ping"，不含图片）。
     * @return 成功 → "OK"；失败 → 原因（设置页展示失败原因）
     */
    open suspend fun ping(): Result<String> {
        val config = cfg()
        if (!config.isReady()) {
            return Result.failure(IllegalStateException("视觉 LLM 未配置或未启用"))
        }
        val request = chatRequest(config, VisionLlmRequestFactory.buildPingBody(config.model))
        return executeWith429Backoff(request).map { "OK" }
    }

    // ---------- 内部 ----------

    private fun chatRequest(config: LlmConfig, body: String): Request =
        Request.Builder()
            .url(VisionLlmRequestFactory.chatUrl(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    /**
     * 执行一次请求；HTTP 429 → 退避 [backoffMillis] 后重试 1 次。
     * 成功（2xx）→ body；其余 → failure（429 重试后仍 429 也算失败）。
     */
    private suspend fun executeWith429Backoff(request: Request): Result<String> {
        val first = executeOnce(request)
        if (first.isFailure) return Result.failure(first.exceptionOrNull()!!)
        val raw = first.getOrThrow()
        if (raw.code == HTTP_TOO_MANY_REQUESTS) {
            delay(backoffMillis)   // 退避 10s 后重试 1 次（虚拟时间下测试不真等）
            val second = executeOnce(request)
            if (second.isFailure) return Result.failure(second.exceptionOrNull()!!)
            return toBody(second.getOrThrow())
        }
        return toBody(raw)
    }

    private suspend fun executeOnce(request: Request): Result<RawResponse> =
        withContext(Dispatchers.IO) {
            runCatching {
                okHttp.newCall(request).execute().use { resp ->
                    RawResponse(resp.code, resp.body?.string() ?: "")
                }
            }
        }

    private fun toBody(raw: RawResponse): Result<String> =
        if (raw.code in 200..299) {
            Result.success(raw.body)
        } else {
            Result.failure(
                IllegalStateException(VisionLlmRequestFactory.errorMessage(raw.code, raw.body))
            )
        }

    private data class RawResponse(val code: Int, val body: String)

    companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429

        /** 429 退避时长：10s（module/05 文档定稿） */
        const val RETRY_BACKOFF_MILLIS = 10_000L

        /** 内容级重试上限：原始 1 次 + 重试 1 次（"返回非 JSON → 重试 1 次"） */
        const val MAX_CONTENT_ATTEMPTS = 2

        /** 超时 60s（module/05 文档定稿：超时 60s） */
        const val TIMEOUT_SECONDS = 60L

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 生产客户端：连接/读/写超时全部 60s */
        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
