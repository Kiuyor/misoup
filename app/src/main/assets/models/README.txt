# 模型目录（模块 04 离线 OCR · PP-OCRv4 mobile via ONNX Runtime）

本目录由 Gradle 打包进 APK `assets/models/`，首次运行由
`AssetModelInstaller` 复制到 `filesDir/models/`。

## 需要的文件（施工验证项，⏸ 待人工前置：PaddleOCR infer 模型 → paddle2onnx 转换）

- `det.onnx`  文本检测 ≈4MB（长边 640 输入，输出 score map）
- `rec.onnx`  文字识别 ≈9MB（CTC 解码，依赖下方字典）
- `cls.onnx`  方向分类 ≈0.5MB（0°/180° 二分类）
- `ppocr_keys_v1.txt`  PP-OCRv4 中文词典（每行一个字符，行号 = 类别 id）

## 版本约定（文件名即版本）

assets 内可不带版本号（默认按 BUNDLED_MODEL_VERSION=1.0.0 落地）；
下载/自换模型请使用带版本文件名：`det.1.1.0.onnx` / `rec.1.1.0.onnx` / `cls.1.1.0.onnx`，
`ModelBundle.resolve` 自动选取三个角色共有的最高版本。

## 国内镜像（T02）

下载服务见 `recognition/paddle/download/ModelDownloadService.kt`，
镜像清单地址约定 `{base}/ppocr-v4-mobile/versions.txt`（每行一个版本号，末行为最新）。
