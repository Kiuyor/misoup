# 模块 05 · 视觉 LLM 识别（M3-B）

> 依据：DETAILED-DESIGN.md §4.3/§4.4。公式 LaTeX + 结构拆分 + 去手写。与模块 04 构成双轨。

## 职责
- OpenAI 兼容视觉通道：引导注册 / BYOK / 连通测试 / 限速
- 一张图 → 结构化 JSON（题干/答案/解析，公式转 LaTeX）
- 去手写：遮罩（模块 03 已含交互）+ 百度擦除 API 按钮
- 与离线 OCR 的结果合并策略

## 通道实现
```kotlin
data class LlmConfig(
  val baseUrl: String, val apiKey: String, val model: String,
  val enabled: Boolean = false, val rateLimit: Int = 3  // 请求/10s
)
class VisionLlmClient(private val okHttp: OkHttpClient, private val cfg: () -> LlmConfig) {
  suspend fun structure(bitmap: Bitmap): Result<StructuredContent>
}
data class StructuredContent(
  val question: String, val answer: String, val explanation: String
)
```
- 请求体：`messages[0] = {role:user, content:[{type:image_url, image_url:{url:"data:image/png;base64,..."}}, {type:text, text:PROMPT}]}`
- 端点预设（设置页一键选择即填 baseUrl/model）：
  - 智谱 `https://open.bigmodel.cn/api/paas/v4` → `glm-4v-flash`（免费引导默认）/ `glm-4v-plus`
  - 通义 `https://dashscope.aliyuncs.com/compatible-mode/v1` → `qwen-vl-plus`
  - 硅基流动 `https://api.siliconflow.cn/v1` → 免费视觉模型（列表动态获取）
  - 自定义（任意 OpenAI 兼容）
- 限速：令牌桶（默认 3 req/10s，设置可调）——保护免费通道共享额度
- 连通测试：1 次最小请求（纯文本 "ping"）→ 显示 OK/失败原因

## Prompt（定稿，勿随意改）
```
你是错题录入助手。识别图片中的题目并只输出 JSON：
{"question":"题干，公式用 $LaTeX$ 包裹","answer":"答案","explanation":"解析"}
规则：中文原文保留；公式/数学符号必须转 $LaTeX$；看不清的文字用[图]占位；答案或解析不存在时填空字符串。
```
- 解析：`Json.parseToJsonElement`；失败 → 重试 1 次 → 降级（仅存图 + 模块 04 离线文本）
- 超时 60s；HTTP 429 → 退避 10s 重试 1 次

## 结果合并（Coordinator）
```
视觉 LLM 成功: 文本.md = 结构分区(题干/答案/解析), meta.ocr.engine=vision
视觉 LLM 失败: 保留离线 OCR 文本(整段题干), meta.ocr.engine=paddle, 状态标"可重试"
两者都失败: status=failed, 仅图可用
```
- 优先级可配置：默认"视觉优先，离线兜底"；设置可切"离线优先（隐私模式）"

## 去手写（遮罩 + 百度擦除）
- 遮罩：模块 02/03 已实现（答案区遮盖），本模块无新增
- 擦除按钮（详情页/导出页）：调百度 `https://aip.baidubce.com/rest/2.0/image-process/v1/handwriting_eraser`
  - Key 配置在设置页（未配置 → 按钮隐藏）；`access_token` 用 API Key+Secret Key 换（client_credentials，缓存 28 天）
  - 产物存 `去手写.webp`；导出/打印时若存在优先用它
  - 失败 → Toast"擦除失败，建议手动裁剪"

## 验收标准
- [ ] 引导流程：设置页点"获取免费 Key"→ 打开智谱注册页 → 回填 → 连通测试 OK
- [ ] 数学试卷照 10 张（含公式/图形题）→ 结构拆分正确率 ≥80%，LaTeX 渲染无崩溃（KaTeX 就绪后核验）
- [ ] 断网/无 Key → 自动走离线 OCR，无阻塞无崩溃
- [ ] 429 限速 → 队列等待不丢任务；BYOK 用户无感
