package com.misoup.app.ai

import com.misoup.app.recognition.visionllm.LlmConfig
import okhttp3.OkHttpClient

/**
 * 测试用文本 LLM：注入结果 + 记录请求（变式/答疑 VM 单测共用）。
 * 复用 [TextLlmClient] 的 open 扩展点，绕开真实网络。
 */
open class FakeTextLlm(
    var result: Result<String> = Result.failure(IllegalStateException("未设置结果")),
    var ready: Boolean = true,
) : TextLlmClient(OkHttpClient(), { LlmConfig() }, backoffMillis = 0) {

    /** 记录每次 complete 的 prompt */
    val prompts = mutableListOf<String>()

    open override suspend fun complete(prompt: String): Result<String> {
        prompts.add(prompt)
        return result
    }

    open override fun isReady(): Boolean = ready
}
