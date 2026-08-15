# 模块 01 · 录入管线（M1）

> 依据：DETAILED-DESIGN.md §3。核心铁律：**录入永不阻塞**——图先落库，识别后补。

## 职责
- 四种采集：单拍 / 相册导入 / 批量 / 连拍
- 预处理链：矫正 → 画质 → 压缩（各步失败降级）
- 落库 + 触发异步识别队列

## 采集
| 方式 | 实现 | 备注 |
|---|---|---|
| 相册 | `ActivityResultContracts.PickMultipleVisualMedia` | 免存储权限，模拟器可测，**开发主路径** |
| 单拍/连拍 | CameraX `ImageCapture`（Capture 模式） | 连拍 = 连续 N 次 capture，每张独立走管线 |
| 批量 | PickMultipleVisualMedia 多选即批量 | 与相册共用 |

- 拍摄后 90° 旋转处理：EXIF 旋转（CameraX 已处理 / 相册用 `ImageDecoder` 自动）
- 采集入口：首页 3 个大按钮（拍题/选图/连拍）+ 悬浮按钮

## 预处理链（全部 `Dispatchers.Default`，单题预算 ≤2s）
```kotlin
object PreprocessPipeline {
  suspend fun run(input: Bitmap): PreprocessResult  // 按顺序执行，每步可独立失败
}
data class PreprocessResult(
  val rectified: Bitmap?,   // null=矫正失败用原图
  val enhanced: Bitmap,     // 画质后
  val webpBytes: ByteArray, // ≤300KB 目标
  val usedFallback: List<String> // 记录降级步骤（供日志/调试，极客可见）
)
```
1. **矫正** `Rectifier`：灰度→高斯模糊(5x5)→Canny(50,150)→`findContours`→按面积取最大轮廓→`approxPolyDP` 近似 4 点→`getPerspectiveTransform`+`warpPerspective`。判定：轮廓面积 < 画面 10% 或非 4 点 → 返回 null（用原图）
2. **画质** `Enhancer`：CLAHE（clipLimit=2.0, tile=8x8）作用于亮度通道 + 阴影去除（大核背景光照估计相减）
3. **压缩** `Compressor`：`Bitmap.compress(WEBP, 80)`，若 >300KB 降 quality 至 60 再试，仍大则长边缩至 1600px

## 落库与异步识别
```kotlin
class CaptureCoordinator(store: StoreService, index: LibraryIndex, recognizer: Recognizer) {
  fun submit(images: List<Uri>): Flow<CaptureProgress>  // 逐张: 加载→预处理→create(ocr.status=pending)→入识别队列
  fun retry(id: String)  // 识别失败重试
}
```
- 识别队列：单线程 `Channel<JobSpec>`（防模型并发），worker 循环消费
- 每张完成后 emit 进度：`CaptureProgress(ok: Int, failed: List<String>)` → UI 顶部汇总条（"已收录 3/5"）
- 识别结果回写：OCR → 文本.md + meta.ocr 更新 + 索引倒排刷新 → UI 自动刷新（StateFlow）

## UI 交互
- 首页：选择来源弹窗 → 采集 → 全屏进度页（逐张缩略图 + 状态徽标：收录中/识别中/失败重试）
- 完成后留在错题库列表（可立即看到新题）

## 验收标准
- [ ] 模拟器：相册导入 5 张（含一张倾斜试卷照）→ 全部落库，列表可见，无崩溃
- [ ] 倾斜照矫正结果目测水平；失败样例（纯文字截图）走降级不崩
- [ ] 单题压缩后 ≤300KB；识别队列 pending→done/failed 状态流转正确
- [ ] 断网状态下录入不受影响（识别失败仅标记）
