# 错题汤 Misoup — 详细设计（技术施工图）

> 状态：定稿（2026-08-16）｜配合 `DESIGN.md` 使用：DESIGN 定"做什么"，本文定"怎么做"
> 本文是 AI 一次写完的执行蓝图。每模块完成 = 编译门禁过 + 冒烟验收过。

---

## 1. 工程与包结构

- 工程：`D:\andriod_misoup`，单 `app` 模块起步，Gradle Kotlin DSL
- 构建镜像：`D:\Android\templates` 模板（腾讯 Gradle 发行包 + 阿里云 Maven 仓库）
- `minSdk 26` / `targetSdk 36` / Kotlin 2.x / Compose BOM（最新稳定）
- 包结构 `com.misoup.app`：

```
com.misoup.app
├── MainActivity.kt / MisoupApp.kt（导航宿主）
├── data/          # 数据层：model / store（文件系统）/ index（内存索引）/ fsrs
├── pipeline/      # 录入管线：capture / rectify / enhance / compress
├── recognition/   # 识别：paddle / visionllm / coordinator（双轨协调）
├── review/        # 复习：queue / scheduler（FSRS 驱动）
├── output/        # 输出：pdf / markdown / csv / anki / share
├── sync/          # webdav 同步
├── settings/      # 配置：keys / model 选择 / fsrs 参数
└── ui/            # theme / components / screens（4 tab）
```

## 2. 数据层（文件系统即存储）

### 2.1 错题库目录结构

```
<库根>/
└── <科目>/
    └── 20260816_140523_二次函数区间最值/
        ├── meta.json
        ├── 原图.webp          # 矫正+压缩后（≤300KB，WebP 质量 80）
        ├── 原图-raw.jpg       # 可选：录入原始图（体积大时省略）
        ├── 去手写.webp        # 可选：擦除/遮罩产物
        ├── 文本.md            # 题干/答案/解析 分段 Markdown（含 $LaTeX$）
        └── 变式/
            ├── 20260816_140523_变式1.md
            └── ...
```

- **库根位置（开放问题 §14 → 定稿）**：默认 `getExternalFilesDir`（免权限），设置页提供"选择外部目录"（SAF）迁移入口
- 自动命名：`yyyyMMdd_HHmmss_<摘要前12字>`（摘要 = 题干前 12 字，剔除空白/公式符）
- **meta.id = UUID**（目录名 = 展示名，可重命名；id 不变，复习记录/引用稳定）

### 2.2 meta.json Schema（v1）

```json
{
  "schemaVersion": 1,
  "id": "20260816_140523_二次函数区间最值",
  "subject": "数学",
  "questionType": "解答题",
  "errorReasons": ["概念不清"],
  "tags": ["二次函数", "月考"],
  "knowledgePoints": ["二次函数区间最值"],
  "note": "",
  "source": "月考",
  "createdAt": "2026-08-16T14:05:23+08:00",
  "updatedAt": "2026-08-16T14:06:01+08:00",
  "images": {"original": "原图.webp", "cleaned": null, "raw": null},
  "textFile": "文本.md",
  "ocr": {"engine": "paddle", "status": "done", "model": "ppocr-v4-mobile", "at": "..."},
  "fsrs": {"state": 0, "due": "2026-08-18T00:00:00+08:00", "stability": 2.5, "difficulty": 5.0, "reps": 0, "lapses": 0, "lastReview": null},
  "reviews": []
}
```

枚举（定稿）：
- 科目预设：语文/数学/英语/物理/化学/生物/政治/历史/地理 + 自定义
- 题型：选择题/填空题/解答题/实验题/计算题/其他
- 错因预设：概念不清/计算失误/审题错误/思路错误/粗心大意/其他
- fsrs.state：0=新卡 1=学习中 2=已掌握 3=重学

### 2.3 索引与列表性能

- 启动时扫描库根 → 构建内存索引（id → meta 摘要 + 缩略图路径），≤1 万题毫秒级
- 索引失效策略：目录 mtime 变更 / FileObserver 触发局部重建
- 缩略图：Coil 按需加载，磁盘缓存 256px 版本（首次访问生成）
- 搜索：题干文本（OCR 结果）存内存倒排，关键词匹配 + 标签过滤；1 万题量级不引数据库

### 2.4 事务与一致性

- 写流程统一走 `StoreService`（协程 Mutex 串行）：先写临时目录 → 校验 meta 可解析 → 原子 rename 进库 → 更新索引
- meta.json 损坏兜底：启动扫描发现解析失败 → 标为"待修复"卡片，展示原始 JSON，不丢文件

## 3. 录入管线（单题 / 批量 / 连拍共用）

```
拍摄/相册/批量/连拍 → 图像预处理 → 落库（status=pending）→ 异步识别 → 更新 meta+文本.md
```

### 3.1 采集
- 相机：CameraX（`ImageCapture`，拍照模式）；模拟器无真相机 → 相册导入为开发主路径
- 相册：Photo Picker（`PickMultipleVisualMedia`，**免存储权限**）
- 连拍：CameraX 连续 Capture 模式，N 张 → 每张独立走管线（不入"拆题"）

### 3.2 预处理链（全部 OpenCV + Bitmap，`Dispatchers.Default`）

| 步骤 | 算法 | 失败降级 |
|---|---|---|
| 矫正 | 灰度→高斯模糊→Canny→findContours→最大四边形→`getPerspectiveTransform`+`warpPerspective` | 无四边→原图直用 |
| 画质 | CLAHE 对比度 + 亮度自动均衡 + 阴影去除（分离背景光照估计） | 原图 |
| 压缩 | WebP 编码 80，长边 ≤2048px；仍 >300KB 降质量至 60 | — |

### 3.3 落库与异步识别
- 落库立即完成（录入永不阻塞）：目录 + 原图 + meta.json（ocr.status=pending）→ 索引
- 后台队列（单线程，防模型并发）：OCR → 文本.md + meta 更新 → 通知 UI（Flow/StateFlow 刷新）
- 识别失败：status=failed，列表可见"重试"按钮；**识别是锦上添花，图片永远在**

## 4. 识别层（双轨透明可控）

### 4.1 双轨策略
| 轨 | 引擎 | 用途 | 配置 |
|---|---|---|---|
| A | PaddleOCR（离线，ONNX Runtime） | 文字 OCR（题干文本、搜索索引） | 默认 |
| B | 视觉 LLM（BYOK/免费引导） | 公式 LaTeX + 题干/答案/解析结构拆分 | 默认开（可用性探测） |

- 用户可在设置页选"仅离线 / 离线+视觉增强（默认）/ 仅视觉"；识别结果页可手动改文本与分段

### 4.2 离线 OCR（PaddleOCR via ONNX Runtime）

- 依赖：`com.microsoft.onnxruntime:onnxruntime-android`（Maven Central）
- 模型：PP-OCRv4 mobile 三件套（det/rec/cls），转 ONNX，随 APK 打包（合计约 13MB）或首次运行下载到 `filesDir/models`（设置页有开关，极客可自换模型文件）
- 推理：`OrtSession` 单实例，输入 640 长边；det→裁剪行→cls 校正→rec；坐标映射回原图，写入文本.md
- **备选方案**：PaddleLite AAR（百度官方 demo 路线）——若 ONNX 转换产物质量/体积不达标再切换

### 4.3 视觉 LLM 通道（OpenAI 兼容协议）

- 统一客户端 `VisionLlmClient`：`baseUrl + apiKey + model`，图片 base64 传 `image_url`
- 预设端点（设置页一键填）：
  - 智谱（免费引导默认）：`https://open.bigmodel.cn/api/paas/v4`，模型 `glm-4v-flash`（免费）/ `glm-4v-plus`
  - 通义：`https://dashscope.aliyuncs.com/compatible-mode/v1`，`qwen-vl-plus`
  - 硅基流动：`https://api.siliconflow.cn/v1`（免费额度模型）
  - 自定义：任意 OpenAI 兼容
- **引导流程（替代内置 Key）**：设置页"AI 增强"→ 按钮跳转智谱注册页 → 复制 Key → 粘贴 → 连通性测试（1 次 1×1 像素图）
- Prompt（一次出结构 + 公式，JSON 输出）：

```
你是错题录入助手。识别图片中的题目并输出 JSON（只输出 JSON）：
{"question":"题干，公式用 $LaTeX$ 包裹","answer":"答案","explanation":"解析"}
要求：中文原文保留；公式/符号必须转 $LaTeX$；看不清的文字用[图]占位；无答案/解析时填空字符串。
```

- 容错：返回非 JSON → 重试 1 次 → 失败降级仅存图 + 离线 OCR 文本；超时 60s
- 免费通道限速（智谱 Flash RPM 低）：录入队列加令牌桶（默认 1 req/3s），可设置

### 4.4 去手写（遮罩为主 + API 擦除为辅）

- **遮罩**：详情页"隐藏答案"开关 → 对答案区域（结构拆分定位 or 用户框选）覆盖遮罩层，重做模式渲染。零 AI、离线、100% 可靠
- **擦除按钮**（导出场景可选）：调百度 `image-process/v1/handwriting_eraser`（Key 可选配置，未配置则隐藏按钮）→ 产物存 `去手写.webp` → 导出/打印用它；失败提示"手写无法擦除，建议手动裁剪"

## 5. 复习层（FSRS-4.5 自实现）

- 算法：自实现 FSRS-4.5（公式公开，约 200 行 Kotlin，参考 fsrs4anki 实现），不引外部依赖
- 复习交互：重做模式 → 看题作答 → 揭晓 → 自评 4 档（Again/Hard/Good/Easy）→ FSRS 更新 → 写 reviews[] + meta
- 今日队列：`due ≤ now` 的错题，按科目分组；首页角标显示数量
- 通知（V2）：WorkManager 每日提醒 + `POST_NOTIFICATIONS`（API 33+）；vivo/小米系引导"自启动+后台白名单"（设置页检测 ROM 给出指引）
- 学情统计：错题总数/科目分布/复习率（due 内完成占比）/正确率（reviews 中 Good+Easy 占比）/最近 30 天趋势；知识点掌握度 = 标签聚合（错题数×复习正确率 → 红黄绿）

## 6. 输出层

### 6.1 LaTeX 级 PDF（骨架功能）
1. 选错题（单选/多选/按标签筛选）→ 生成 HTML 模板（A4，题号+题干图文+答题区留白）
2. 公式渲染：本地 KaTeX（`assets/katex/`，离线）→ WebView 加载 `file://` + 注入 JS 渲染
3. `PrintManager.print` → 用户选"保存为 PDF"（系统能力，不做自定义打印机）
4. 模板参数：是否含答案/解析（重做卷=不含，答案卷=含）、字体（HarmonyOS Sans SC + 数学斜体）、页边距

### 6.2 其他导出
- Markdown：每题一段，题干/答案/解析 + 图片引用（相对路径）
- CSV：列=题干/科目/题型/错因/知识点/创建时间/复习状态（Anki 可导入）
- Anki apkg（V1.5）：SQLite 卡组 + media 打包，字段=题干图+文本
- 分享：`FileProvider` + 系统分享（单题图/文本/Markdown）
- 备份/恢复：整库目录打包 zip（含 meta 校验和清单）→ 恢复时校验 + 去重

## 7. 同步层（WebDAV，无账号）

- 库：`com.github.thegrizzlylabs:sardine-android`（WebDAV 客户端）
- 配置：地址/用户名/密码（明文存 `EncryptedSharedPreferences`，极客可关）
- 策略：首次全量 PUT；增量双向（PROPFIND 列远端 mtime/size 对比本地索引）
- 冲突：同名同 mtime 不同 hash → 保留较新，旧文件改名 `xxx.conflict-<ts>` 不删
- 触发：设置页手动 / 录入后自动（可关）；后台协程串行，失败指数退避重试

## 8. UI 框架

- 底部导航 4 tab（极客向，暗色主题默认）：
  - **首页**：大按钮"拍题/选图/连拍" + 今日待复习角标 + 最近录入
  - **错题库**：科目/题型/标签筛选栏 + 搜索框 + 瀑布流/列表切换
  - **复习**：今日队列 + FSRS 统计 + 重做流程
  - **我的**：统计概览 / 设置（Key、模型、FSRS 参数、库目录）/ 捐赠
- 详情页：原图大图 + 题干（LaTeX 渲染）+ 答案/解析折叠 + 遮罩开关 + 编辑/移动/删除 + 变式/答疑入口
- 状态表达：识别 pending/failed 徽标；空态文案引导；全部中文（专有名词除外）

## 9. 权限清单

| 权限 | 用途 | 时机 |
|---|---|---|
| CAMERA | CameraX 拍照/连拍 | 首次拍题动态申请 |
| （无存储权限） | Photo Picker + Scoped Storage + SAF | — |
| POST_NOTIFICATIONS | 复习提醒（V2） | 设置页开启时 |

## 10. 依赖清单（定稿基线）

- androidx：compose-bom / navigation-compose / camera-camera2 / lifecycle / work-runtime
- coil-compose（图片加载与缩略图）
- kotlinx-serialization-json（meta.json）
- okhttp（视觉 LLM / 百度 API / 下载）
- org.opencv:opencv（Maven Central 官方包，4.x）
- com.microsoft.onnxruntime:onnxruntime-android
- sardine-android（WebDAV）
- 自写：FSRS-4.5 / 矫正管线 / 预处理链 / PDF 模板 / KaTeX 本地资源

## 11. 里程碑任务清单（AI 生成顺序 + 验收标准）

### M0 骨架（验收：模拟器首屏）
- [ ] Gradle 工程 + 镜像模板 + 依赖基线
- [ ] 主题（暗色 + 色板定稿：暖汤色系主色 #D97A38 系，待视觉确认）
- [ ] 4 tab 导航壳 + 空态页
- [ ] 文件存储基座：库根创建 / 目录树 / meta.json 读写 / 索引
- 验收：`gradle build` 绿；模拟器启动进首页；adb 建库目录可见

### M1 录入闭环
- [ ] Photo Picker 相册导入（模拟器可用）/ CameraX 拍摄 / 批量 / 连拍
- [ ] 预处理链（矫正/画质/压缩）+ 失败降级
- [ ] 落库 + 索引 + 列表（分组筛选）+ 详情页
- [ ] 标签/搜索/编辑/移动/删除 + 自动命名
- [ ] 基础 PDF（图+文，未含 LaTeX）+ 分享 + WebDAV
- 验收：模拟器导入 5 张图 → 全流程录入 → 列表/搜索/编辑可用；矫正样例（倾斜照片）目测 OK

### M2 复习闭环
- [ ] 遮罩重做（手动框选答案区）
- [ ] FSRS 调度 + 今日队列 + 复习记录
- [ ] 学情统计（三数字 + 科目分布）
- 验收：模拟器造 10 题 → 复习 5 次 → due 变化符合 FSRS 预期

### M3 识别增强
- [ ] ONNX Runtime + PP-OCRv4 模型集成（真机测速：778G+ 单题 <3s）
- [ ] 视觉 LLM 通道（引导 + BYOK + 连通测试）+ 结构拆分 + 公式 LaTeX
- [ ] 去手写（遮罩 + 百度擦除按钮）
- [ ] 整页拆题（V2 特性，放本期末尾，失败可手动修正）
- 验收：真机拍照 10 题 → 识别文本可搜索、公式渲染正确率 ≥80%、离线无网可用

### M4 AI 增强
- [ ] 变式出题（选模型 + 生成 + 存变式/）
- [ ] 答疑（对话式，跟随错题上下文）
- [ ] 知识点掌握度聚合
- 验收：BYOK DeepSeek 生成变式题可读；免费通道限速不卡录入

### M5 输出收尾
- [ ] KaTeX 本地渲染 + LaTeX 级 PDF（重做卷/答案卷模板）
- [ ] Markdown / CSV / Anki apkg / 导入导出备份 / 可选加密
- [ ] 隐私政策 PRIVACY.md / README / 图标（汤意象）/ 捐赠页（爱发电链接）
- 验收：PDF 打印到文件在 PC 打开排版正确；备份→卸载→恢复 数据完整

## 12. 关键风险与预案（施工期重点盯）

| 风险 | 预案 |
|---|---|
| ONNX 模型转换/下载失败 | 备选 PaddleLite AAR；再备选纯视觉 LLM 通道兜底（M3 不阻塞 M1/M2） |
| KaTeX 离线包体积 | assets 只留核心 CSS/JS/fonts，gzip；<2MB 达标 |
| 视觉 LLM 免费通道不稳定 | 队列限速 + 失败静默降级；设置页 BYOK 一键切换 |
| WebDAV 冲突 | 冲突文件保留策略（§7），不静默覆盖 |
| 模拟器无相机 | M1 用相册导入验收；真机环节后补 |
