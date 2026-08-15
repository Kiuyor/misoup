# 任务 · 01 录入管线（M1）

> 依据：`doc/module/01-capture-pipeline.md`｜铁律：录入永不阻塞
> 依赖：00 完成

- [ ] T01 Photo Picker 接入：`PickMultipleVisualMedia` 选图（单张即单题，多张即批量）
- [ ] T02 CameraX 拍照接入：`ImageCapture`，CAMERA 权限动态申请
- [ ] T03 连拍模式：连续 N 次 capture，每张独立入管线
- [ ] T04 `Rectifier` 矫正：Canny→最大四边形→透视变换；失败返回 null 降级原图
- [ ] T05 `Enhancer` 画质：CLAHE + 阴影去除
- [ ] T06 `Compressor` 压缩：WebP 80→60 降级链，目标 ≤300KB
- [ ] T07 `PreprocessPipeline`：三步骤顺序执行 + `usedFallback` 记录（单题预算 ≤2s）
- [ ] T08 `CaptureCoordinator`：逐张 加载→预处理→`StoreService.create`(ocr=pending)→入识别队列，emit `CaptureProgress`
- [ ] T09 识别队列：单线程 Channel worker，消费→识别→回写 文本.md + meta.ocr + 索引倒排刷新
- [ ] T10 首页采集入口 UI：拍题/选图/连拍 3 大按钮 + 全屏进度页（缩略图 + 状态徽标：收录中/识别中/失败重试）
- [ ] ✅ 验收：模拟器相册导入 5 张（含倾斜照）全落库；倾斜照矫正目测 OK；降级样例不崩；单题 ≤300KB；断网录入不受影响

**开工提示**：T04-T07 是纯算法（可先写单测用本地图验证）；T01/T10 串起 UI；T09 的识别实现先留接口空实现（M3 填充），状态流转先行。
