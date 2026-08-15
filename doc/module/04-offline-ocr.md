# 模块 04 · 离线 OCR（M3-A）

> 依据：DETAILED-DESIGN.md §4.2。PaddleOCR PP-OCRv4 mobile via ONNX Runtime，离线零成本。

## 职责
- 模型获取与加载（随包 / 可选下载）
- 图片 → 文本行（坐标 + 文本），写入文本.md
- 失败降级路径（不影响 M1/M2 已有功能）

## 依赖与模型
- `com.microsoft.onnxruntime:onnxruntime-android`（Maven Central，版本取最新稳定）
- 模型三件套（PP-OCRv4 mobile，转 ONNX）：
  - `det.onnx`（文本检测）≈4MB
  - `rec.onnx`（文字识别，含 CTC 字典）≈9MB
  - `cls.onnx`（方向分类）≈0.5MB
- 存放：`filesDir/models/`；打包进 APK `assets/models/`，首次运行复制（可选：设置"模型下载"开关从国内镜像拉新版本，极客可自换模型文件——文件名即版本约定）
- 模型来源（施工时验证）：PaddleOCR release 提供 infer 模型 → 官方 `paddle2onnx` 转换；若转换链路受阻，**备选 PaddleLite AAR**（见详设 §4.2），再备选纯视觉 LLM 兜底

## 推理实现
```kotlin
class PaddleOcrEngine(context: Context) : OcrEngine {
  override suspend fun recognize(bitmap: Bitmap): OcrResult
}
data class OcrResult(
  val lines: List<OcrLine>,     // 按 y 坐标排序（阅读顺序）
  val text: String,             // lines 拼接
  val latencyMs: Long
)
data class OcrLine(val text: String, val box: RectF)
```
- 流程：det（长边 640 归一化）→ 每行裁剪 → cls 判断旋转 → rec → 文本
- `OrtSession` 单实例 + `synchronized`（防并发）；输入 Tensor 复用 buffer 减 GC
- 线程：`Dispatchers.Default`；单题预算：778G+ 真机 <3s，超 5s 打日志标记慢设备
- 字典：PP-OCRv4 中文词典（ppocr_keys_v1）打包 assets

## 与文本.md 的写入
- 识别结果 → 文本.md 临时段 `[OCR]`：`## 题干\n<lines 文本>`（未做结构拆分前整段放题干；模块 05 视觉 LLM 做完结构拆分后重写分区）
- meta.ocr：`{engine:"paddle", status:"done", model:"ppocr-v4-mobile", at:...}`；失败 `status:"failed"` + 可重试

## 性能与体积
- 首启动加载耗时：≤1s（模型小，内存映射读）
- APK 体积增量：≤15MB（模型压缩后）
- 内存峰值：≤200MB（真机 8GB 无压力）

## 验收标准
- [ ] 真机（iQOO Z6）：中文试卷照 10 张 → 平均单题识别 <3s，常见印刷体错字率目测 <5%
- [ ] 模拟器（x86_64）：识别可跑（慢可接受），无 ABI 崩溃
- [ ] 断网全流程可用；模型文件缺失 → 明确错误提示 + 引导下载
- [ ] 竖排/旋转图 → cls 校正后文本可读
