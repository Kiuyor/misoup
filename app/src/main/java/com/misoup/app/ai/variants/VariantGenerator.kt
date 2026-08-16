package com.misoup.app.ai.variants

import com.misoup.app.ai.TextLlmClient
import com.misoup.app.data.store.StoreService

/**
 * 变式出题（模块 07 T02）：错题 → 文本 LLM → N 道同考点变式题（难度递增）。
 *
 * 流程：读 meta（考点）+ 文本.md（题干+答案）→ [VariantPrompt] → [TextLlmClient.complete]
 * → [VariantJsonParser.parse]。
 *
 * 失败语义（T07）：未配置 → NotReady（UI 引导）；限流/断网 → 明确「AI 服务不可用」，
 * 由调用方经 [com.misoup.app.ai.AiError.messageOf] 展示。
 *
 * @param llm 文本通道（复用 05 配置；与答疑共用同一实例）
 * @param store 错题存储（readMetaById / readTextById）
 */
class VariantGenerator(
    private val llm: TextLlmClient,
    private val store: StoreService,
) {

    /** 是否已配置文本通道（UI「无 Key → 引导」判定用，T07） */
    fun isReady(): Boolean = llm.isReady()

    /**
     * 生成变式题。
     * @param id 错题 id
     * @param count 变式数量 1-3（越界 → failure）
     * @return 成功 → 变式列表（按难度递增序）；失败 → 原因
     */
    suspend fun generateVariants(id: String, count: Int = 2): Result<List<Variant>> {
        if (count !in VariantPrompt.MIN_COUNT..VariantPrompt.MAX_COUNT) {
            return Result.failure(
                IllegalArgumentException("变式数量需在 ${VariantPrompt.MIN_COUNT}-${VariantPrompt.MAX_COUNT} 之间")
            )
        }
        val meta = store.readMetaById(id).getOrElse { return Result.failure(it) }
        val text = store.readTextById(id).getOrElse { return Result.failure(it) }
        val prompt = VariantPrompt.build(meta.knowledgePoints, text, count)
        val raw = llm.complete(prompt).getOrElse { return Result.failure(it) }
        return VariantJsonParser.parse(raw)
    }
}
