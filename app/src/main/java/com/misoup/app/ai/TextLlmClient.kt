package com.misoup.app.ai

import com.misoup.app.recognition.visionllm.LlmConfig
import com.misoup.app.recognition.visionllm.VisionLlmClient
import com.misoup.app.recognition.visionllm.VisionLlmRequestFactory
import com.misoup.app.recognition.visionllm.isReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 纯文本 LLM 客户端（模块 07 T01：变式出题/题目答疑的文本通道）
 *
 * 与模块 05 [VisionLlmClient] 同构、复用其配置/限速保护：
 * - 同一份 LlmConfig（baseUrl/apiKey/model/enabled/rateLimit），设置页改动即时生效
 * - OpenAI 兼容 chat/completions 纯文本请求（无图片），复用 [VisionLlmRequestFactory]
 *   的 chatUrl / buildTextBody / parseChatResponse / errorMessage
 * - HTTP 429 → 退避 [backoffMillis] 后重试 1 次（免费通道共享额度保护，同 05）
 * - 未配置/未启用 → [AiError.NotReady] 直接失败（不发请求，UI 走引导流程 T07）
 *
 * 生产 [defaultHttpClient] 同 05（超时 60s）；测试注入 fake 拦截器 client。
 *
 * @param okHttp 执行器（生产 [VisionLlmClient.defaultHttpClient]；测试 fake 拦截器 client）
 * @param cfg 实时配置源（() -> LlmConfig；与 05 共用同一仓库实例）
 * @param backoffMillis 429 退避时长（默认同 05 的 10s；测试注入 0 或依赖虚拟时间）
 */
open class TextLlmClient(
    private val okHttp: OkHttpClient,
    private val cfg: () -> LlmConfig,
    private val backoffMillis: Long = VisionLlmClient.RETRY_BACKOFF_MILLIS,
) {

    /** 当前是否已具备发起文本请求条件（UI「无 Key → 引导」判定用，T07） */
    open fun isReady(): Boolean = cfg().isReady()

    /**
     * 纯文本补全：prompt → 模型回答文本。
     * @return 成功 → 回答内容（原样，不解析 JSON；变式 JSON 解析由调用方负责）；
     *         失败 → 原因（429 退避重试后仍失败 / 网络异常 / 未配置）
     */
    open suspend fun complete(prompt: String): Result<String> {
        val config = cfg()
        if (!config.isReady()) {
            return Result.failure(AiError.NotReady)
        }
        val request = Request.Builder()
            .url(VisionLlmRequestFactory.chatUrl(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(VisionLlmRequestFactory.buildTextBody(prompt, config.model).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeWith429Backoff(request).mapCatching { body ->
            VisionLlmRequestFactory.parseChatResponse(body).getOrThrow()
        }
    }

    // ---------- 内部（与 VisionLlmClient 同策略） ----------

    private suspend fun executeWith429Backoff(request: Request): Result<String> {
        val first = executeOnce(request)
        if (first.isFailure) return Result.failure(first.exceptionOrNull()!!)
        val raw = first.getOrThrow()
        if (raw.code == VisionLlmClient.HTTP_TOO_MANY_REQUESTS) {
            delay(backoffMillis)   // 退避后重试 1 次（虚拟时间下测试不真等）
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

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
