package com.misoup.app.ai.variants

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 变式 JSON 数组解析（模块 07 T02 纯逻辑，可 JVM 单测）
 *
 * - 容错：```json 围栏剥离（模型常见输出）；元素缺 answer/hint → 填空/null
 * - 失败（坏 JSON / 非数组 / 空数组 / 缺题干）→ 明确失败（UI 提示，不静默）
 * - title 由 [titleOf] 从题干推导（LLM 输出 JSON 无 title 字段）
 */
object VariantJsonParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析模型输出 → 变式列表；坏 JSON/非数组/空数组/元素缺题干 → failure */
    fun parse(raw: String): Result<List<Variant>> = runCatching {
        val cleaned = stripFence(raw)
        require(cleaned.isNotBlank()) { "模型输出为空" }
        val element = json.parseToJsonElement(cleaned)
        val arr = element as? JsonArray
            ?: throw IllegalArgumentException("模型输出不是 JSON 数组")
        require(arr.isNotEmpty()) { "模型未返回变式题（空数组）" }
        arr.mapIndexed { index, item ->
            val obj = item as? JsonObject
                ?: throw IllegalArgumentException("第 ${index + 1} 项不是对象")
            val question = obj["question"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(question.isNotBlank()) { "第 ${index + 1} 项缺少题干" }
            val answer = obj["answer"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val hint = obj["hint"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            Variant(
                title = titleOf(question),
                question = question,
                answer = answer,
                hint = hint
            )
        }
    }

    /** 剥离 Markdown 围栏（```json / ```）；非围栏原样返回 */
    fun stripFence(raw: String): String {
        val t = raw.trim()
        val m = FENCE.matchEntire(t) ?: return t
        return m.groupValues[1].trim()
    }

    private val FENCE = Regex("^```(?:json)?\\s*([\\s\\S]*?)\\s*```$")
}
