package com.misoup.app.recognition.visionllm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 端点预设（模块 05 T03）：设置页一键选择即填 baseUrl + model
 *
 * 文档定稿（module/05「端点预设」）：
 * - 智谱 `https://open.bigmodel.cn/api/paas/v4` → `glm-4v-flash`（免费引导默认）/ `glm-4v-plus`
 * - 通义 `https://dashscope.aliyuncs.com/compatible-mode/v1` → `qwen-vl-plus`
 * - 硅基流动 `https://api.siliconflow.cn/v1` → 免费视觉模型（列表动态获取）
 * - 自定义：任意 OpenAI 兼容（baseUrl+model 手填）
 *
 * [dynamicModels]：模型列表需运行时获取（硅基流动），设置页点击后调
 * [SiliconflowModelsParser] 解析 GET /models 响应，再以 modelOverride 填入。
 */
data class EndpointPreset(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    /** 引导注册页 URL（T04「获取免费 Key」跳转）；null = 无引导（BYOK 手填） */
    val registerUrl: String? = null,
    /** true = 模型列表动态获取（默认模型仅占位，需 /models 拉取） */
    val dynamicModels: Boolean = false,
)

object EndpointPresets {

    // ---- 智谱（免费引导默认） ----
    const val ZHIPU_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"
    const val ZHIPU_MODEL_FLASH = "glm-4v-flash"   // 免费
    const val ZHIPU_MODEL_PLUS = "glm-4v-plus"
    const val ZHIPU_REGISTER_URL = "https://open.bigmodel.cn/"

    // ---- 通义千问 ----
    const val QWEN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val QWEN_MODEL = "qwen-vl-plus"

    // ---- 硅基流动 ----
    const val SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1"
    const val SILICONFLOW_REGISTER_URL = "https://cloud.siliconflow.cn/"

    val ZHIPU_FLASH = EndpointPreset(
        id = "zhipu_flash",
        displayName = "智谱（免费引导）",
        baseUrl = ZHIPU_BASE_URL,
        defaultModel = ZHIPU_MODEL_FLASH,
        registerUrl = ZHIPU_REGISTER_URL,
    )

    val ZHIPU_PLUS = EndpointPreset(
        id = "zhipu_plus",
        displayName = "智谱 glm-4v-plus",
        baseUrl = ZHIPU_BASE_URL,
        defaultModel = ZHIPU_MODEL_PLUS,
        registerUrl = ZHIPU_REGISTER_URL,
    )

    val QWEN = EndpointPreset(
        id = "qwen",
        displayName = "通义千问",
        baseUrl = QWEN_BASE_URL,
        defaultModel = QWEN_MODEL,
        registerUrl = "https://dashscope.console.aliyun.com/",
    )

    val SILICONFLOW = EndpointPreset(
        id = "siliconflow",
        displayName = "硅基流动",
        baseUrl = SILICONFLOW_BASE_URL,
        defaultModel = "",   // 动态获取；占位空串
        registerUrl = SILICONFLOW_REGISTER_URL,
        dynamicModels = true,
    )

    /** 自定义 OpenAI 兼容：baseUrl+model 手填，无引导无默认模型 */
    val CUSTOM = EndpointPreset(
        id = "custom",
        displayName = "自定义（OpenAI 兼容）",
        baseUrl = "",
        defaultModel = "",
    )

    /** 全部预设（设置页列表展示顺序） */
    val all: List<EndpointPreset> = listOf(ZHIPU_FLASH, ZHIPU_PLUS, QWEN, SILICONFLOW, CUSTOM)

    /** 按 id 查预设；未知 id → null */
    fun byId(id: String): EndpointPreset? = all.firstOrNull { it.id == id }

    /** 按 baseUrl 反查预设（设置页回显当前端点）；未知 → null */
    fun byBaseUrl(baseUrl: String): EndpointPreset? =
        all.firstOrNull { it.baseUrl == baseUrl.trimEnd('/') }
}

/**
 * 硅基流动 /models 响应解析（T03 动态模型列表）
 *
 * OpenAI 兼容 GET {baseUrl}/models 返回：
 * ```json
 * {"object":"list","data":[{"id":"Qwen/Qwen2.5-VL-7B-Instruct","object":"model",...}, ...]}
 * ```
 * 纯逻辑可测；失败返回 failure（设置页提示"模型列表拉取失败"，回退手填）。
 */
object SiliconflowModelsParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析模型列表；返回模型 id 列表（保序）；非列表/坏 JSON → failure */
    fun parseModels(body: String): Result<List<String>> = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val data: JsonArray = root["data"]?.jsonArray
            ?: throw IllegalArgumentException("响应缺少 data 数组")
        data.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }
}
