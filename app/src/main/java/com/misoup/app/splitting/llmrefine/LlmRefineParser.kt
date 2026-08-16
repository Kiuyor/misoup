package com.misoup.app.splitting.llmrefine

import com.misoup.app.splitting.candidates.BoundingBox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * T02 视觉 LLM 建议 JSON 解析（纯解析，JVM 可测）。
 *
 * 协议（本模块定稿，视觉 LLM prompt 输出同构）：
 * ```json
 * {
 *   "operations": [
 *     {"op": "merge",  "indices": [0, 2]},
 *     {"op": "split",  "index": 1},
 *     {"op": "delete", "index": 3},
 *     {"op": "keep",   "index": 0, "box": {"x":1,"y":2,"w":100,"h":50}}
 *   ]
 * }
 * ```
 *
 * 容错策略（同 VisionJsonParser 风格）：
 * - 容忍 ```json 代码围栏与首尾杂散文本（取首个 { 到末个 }）
 * - 未知字段/未知 op 忽略（不崩）
 * - 坏 JSON/结构非法 → [Result.failure]（调用方降级：直接返回离线结果）
 */
object LlmRefineParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析模型输出文本 → 操作列表；坏 JSON/空 operations → failure */
    fun parse(content: String): Result<List<RefineOp>> = runCatching {
        val trimmed = content.trim()
        require(trimmed.isNotEmpty()) { "模型输出为空" }
        val objectText = extractJsonObject(trimmed)
            ?: throw IllegalArgumentException("未找到 JSON 对象")
        val root = json.parseToJsonElement(objectText).jsonObject
        val ops = root["operations"]?.jsonArray ?: throw IllegalArgumentException("缺少 operations 数组")
        ops.mapNotNull { el -> parseOp(el) }
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

    /** 单个 op 解析：未知 op/坏结构 → null（忽略）；未知字段忽略 */
    private fun parseOp(el: JsonElement): RefineOp? = runCatching {
        val obj = el.jsonObject
        when (obj.stringOr("op")) {
            "merge" -> RefineOp.Merge(obj.intListOr("indices"))
            "split" -> RefineOp.Split(obj.intOr("index") ?: return null)
            "delete" -> RefineOp.Delete(obj.intOr("index") ?: return null)
            "keep" -> RefineOp.Keep(
                index = obj.intOr("index") ?: return null,
                box = obj["box"]?.jsonObject?.let { boxObj ->
                    BoundingBox(
                        x = boxObj.floatOr("x") ?: return@let null,
                        y = boxObj.floatOr("y") ?: return@let null,
                        width = boxObj.floatOr("w") ?: return@let null,
                        height = boxObj.floatOr("h") ?: return@let null,
                    )
                }
            )
            else -> null
        }
    }.getOrNull()

    private fun JsonObject.stringOr(key: String): String? = runCatching {
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()

    private fun JsonObject.intOr(key: String): Int? = runCatching {
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
    }.getOrNull()

    private fun JsonObject.floatOr(key: String): Float? = runCatching {
        (this[key] as? JsonPrimitive)?.content?.toFloatOrNull()
    }.getOrNull()

    private fun JsonObject.intListOr(key: String): List<Int> = runCatching {
        (this[key] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonPrimitive)?.content?.toIntOrNull()
        } ?: emptyList()
    }.getOrDefault(emptyList())
}
