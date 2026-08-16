# 任务 · 05 视觉 LLM 识别（M3-B）

> 依据：`doc/module/05-vision-llm.md`｜与 04 双轨，Coordinator 决策
> 依赖：00；04 可并行

- [x] T01 `LlmConfig` + 凭据存取：EncryptedSharedPreferences（baseUrl/apiKey/model/enabled/rateLimit）
- [x] T02 `VisionLlmClient`：OpenAI 兼容请求，图片 base64（image_url），超时 60s，429 退避重试 1 次
- [x] T03 端点预设：智谱（免费引导默认）/通义/硅基流动/自定义，一键填入 baseUrl+model
- [x] T04 引导流程：设置页"获取免费 Key"→跳转注册页→回填→连通测试（纯文本 ping）
- [x] T05 令牌桶限速：默认 3 req/10s，设置可调
- [x] T06 Prompt 定稿 + JSON 解析：结构拆分（题干/答案/解析 + $LaTeX$），失败重试→降级
- [x] T07 结果合并 Coordinator：视觉成功→分区写文本.md + engine=vision；失败→保离线文本；双失败→failed
- [x] T08 识别策略三档：视觉优先/离线优先（隐私模式）/仅离线，设置切换即时生效
- [x] T09 百度擦除：access_token 换取（缓存 28 天）+ 擦除调用 + 产物存 `去手写.webp`；未配置 Key 隐藏按钮
- [ ] ✅ 验收：引导→连通→10 张数学卷结构拆分 ≥80%；无 Key 自动离线不崩；429 队列等待不丢；擦除产物正确

**开工提示**：T01-T05 是通道基建（先用假图验证连通）；T06 的 Prompt 定稿勿随意改；T09 依赖百度 Key（人肉前置②）。
