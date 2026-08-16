package com.misoup.app.recognition.visionllm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 视觉 LLM 输出 JSON 解析（模块 05 T06）
 *
 * 容错策略：
 * - 容忍 ```json 代码围栏（部分模型输出带围栏）
 * - 容忍首尾杂散文本（取第一个 { 到最后一个 } 之间的内容）
 * - 未知字段忽略（ignoreUnknownKeys 风格，手写提取）
 * - 缺字段 → 空字符串（文档定稿：\"答案或解析不存在时填空字符串\"）
 * - 结构非法（非对象/坏 JSON/空响应）→ failure（调用方重试 1 次 → 降级）
 */
object VisionJsonParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析模型输出文本 → [StructuredContent] */
    fun parse(content: String): Result<StructuredContent> = runCatching {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "模型输出为空" }
        // 明确是 JSON 数组（而非对象）→ 结构非法，直接失败（文档：非对象 → failure）
        val noFence = trimmed
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        require(!noFence.startsWith("[")) { "输出是 JSON 数组而非对象" }
        val objectText = extractJsonObject(noFence)
            ?: throw IllegalArgumentException("未找到 JSON 对象")
        val root = json.parseToJsonElement(objectText).jsonObject
        StructuredContent(
            question = root.stringOr("question"),
            answer = root.stringOr("answer"),
            explanation = root.stringOr("explanation"),
        )
    }

    /** 剥代码围栏并截取首个 {...} 对象（容忍围栏/前后杂文） */
    private fun extractJsonObject(text: String): String? {
        val noFence = text
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        val start = noFence.indexOf('{')
        val end = noFence.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return noFence.substring(start, end + 1)
    }

    /** 对象字段字符串提取：缺字段/非字符串类型 → 空串（不抛；数字/布尔不入文本） */
    private fun JsonObject.stringOr(key: String): String = runCatching {
        val prim = this[key]?.jsonPrimitive ?: return ""
        if (prim.isString) prim.content else ""
    }.getOrDefault("")
}

/** JsonPrimitive 便捷扩展：content 或 null（缺字段/非字符串不崩）；同包工厂类共用 */
internal val JsonPrimitive.contentOrNull: String?
    get() = runCatching { this.content }.getOrNull()
