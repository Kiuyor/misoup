package com.misoup.app.recognition.visionllm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI 兼容请求构造 / 响应解析（模块 05 T02/T04 纯逻辑，可 JVM 单测）
 *
 * 请求体严格按 module/05 文档定稿：
 * ```
 * messages[0] = {role:user, content:[
 *   {type:image_url, image_url:{url:"data:image/png;base64,..."}},
 *   {type:text, text:PROMPT}
 * ]}
 * ```
 * - [buildChatBody]：视觉结构化请求（图 + Prompt）
 * - [buildPingBody]：连通测试最小请求（纯文本 "ping"，T04）
 * - [parseChatResponse]：choices[0].message.content 提取（字符串或文本片段数组）
 * - [errorMessage]：非 2xx 响应体 error.message 提取（设置页失败原因展示）
 */
object VisionLlmRequestFactory {

    const val CHAT_PATH = "/chat/completions"
    const val DATA_URL_PREFIX = "data:image/png;base64,"
    const val PING_TEXT = "ping"

    private val json = Json { ignoreUnknownKeys = true }

    /** 聊天补全端点：baseUrl 去尾斜杠 + /chat/completions */
    fun chatUrl(baseUrl: String): String = baseUrl.trimEnd('/') + CHAT_PATH

    /** 视觉结构化请求体（T02）：image_url 数据 URI + 定稿 Prompt */
    fun buildChatBody(dataUrl: String, prompt: String, model: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("model", model)
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "image_url")
                                                put(
                                                    "image_url",
                                                    buildJsonObject { put("url", dataUrl) }
                                                )
                                            }
                                        )
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", prompt)
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        )

    /** 连通测试请求体（T04）：1 次最小纯文本请求，不含图片 */
    fun buildPingBody(model: String): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("model", model)
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", PING_TEXT)
                            }
                        )
                    }
                )
            }
        )

    /**
     * 提取聊天响应文本：choices[0].message.content。
     * content 可为字符串（OpenAI 风格）或文本片段数组（部分兼容实现）；空 choices → failure。
     */
    fun parseChatResponse(body: String): Result<String> = runCatching {
        require(body.isNotBlank()) { "响应体为空" }
        val root = json.parseToJsonElement(body).jsonObject
        val choices = root["choices"]?.jsonArray
            ?: throw IllegalArgumentException("响应缺少 choices 数组")
        val first = choices.firstOrNull() as? JsonObject
            ?: throw IllegalArgumentException("choices 为空")
        val message = first["message"] as? JsonObject
            ?: throw IllegalArgumentException("响应缺少 message 对象")
        val content = message["content"]
            ?: throw IllegalArgumentException("响应缺少 content")
        when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.mapNotNull { element ->
                (element as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
            }.joinToString("")
            else -> throw IllegalArgumentException("content 类型异常")
        }
    }

    /** 非 2xx 失败原因提取：优先 error.message（OpenAI 风格），否则 HTTP 状态说明 */
    fun errorMessage(code: Int, body: String): String = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val error = root["error"] as? JsonObject ?: return@runCatching null
        error["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $code"
}
