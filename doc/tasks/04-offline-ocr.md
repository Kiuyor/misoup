# 任务 · 04 离线 OCR（M3-A）

> 依据：`doc/module/04-offline-ocr.md`｜ONNX Runtime 路线，PaddleLite 备选
> 依赖：01 的识别队列接口（空实现待填）

- [ ] T01 依赖接入：`onnxruntime-android` + 模型三件套资源（det/rec/cls 转 ONNX，assets 打包，首启复制到 filesDir/models）
- [ ] T02 模型下载/更新入口（设置页）：国内镜像拉新版本，文件名=版本约定
- [ ] T03 `PaddleOcrEngine.detect`：det 推理，长边 640，输出行框
- [ ] T04 `cls + rec` 推理：行裁剪→方向校正→识别→文本+字典映射（ppocr_keys_v1）
- [ ] T05 行排序与组装：按 y 排序阅读顺序，`OcrResult(text, lines, latencyMs)`
- [ ] T06 写入管线：文本.md 临时段（整段题干）+ meta.ocr{engine:paddle,status}，失败→failed+可重试
- [ ] T07 接入 01 识别队列：替换空实现，单线程串行，超 5s 记慢设备日志
- [ ] T08 容错：模型缺失→明确提示+引导下载；损坏→重下；ABI 崩溃防护（x86_64 模拟器可跑）
- [ ] ✅ 验收：真机 10 张中文试卷照平均 <3s、错字率目测 <5%；断网全流程；模拟器不崩

**开工提示**：T01 是最大不确定项（模型转换链路），先验证再铺开；T03-T05 用本地测试图先出结果再连队列。
