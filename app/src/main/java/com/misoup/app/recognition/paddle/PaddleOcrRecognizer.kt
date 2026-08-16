package com.misoup.app.recognition.paddle

import android.graphics.Bitmap
import android.util.Log
import com.misoup.app.recognition.Recognizer
import com.misoup.app.recognition.paddle.write.OcrTextFormat

/**
 * PaddleOCR 识别器（T07）：接入模块 01 识别队列，替换 DummyRecognizer。
 *
 * 契约：返回 Result<String> = 文本.md 内容（`## 题干\n<识别文本>`，模块 04 写入约定）；
 * 引擎失败（模型缺失/损坏/推理异常）→ Result.failure(中文原因)，队列标记 failed 可重试。
 *
 * 核心逻辑抽到 [RecognizerAdapter]（泛型 + 假引擎可 JVM 单测）：
 * - 单线程串行由 RecognitionQueue worker 保证（本识别器本身可重入）
 * - 超 [SLOW_DEVICE_THRESHOLD_MS]（5s）→ onSlow 回调记慢设备日志
 */
class PaddleOcrRecognizer(
    engine: OcrEngine,
    onSlow: (Long) -> Unit = { ms ->
        Log.w(TAG, "慢设备提示：单题识别 ${ms}ms 超过 ${SLOW_DEVICE_THRESHOLD_MS}ms 阈值")
    },
) : Recognizer<Bitmap> {

    private val adapter = RecognizerAdapter<Bitmap>(
        engine = { engine.recognize(it) },
        onSlow = onSlow,
    )

    override suspend fun recognize(input: Bitmap): Result<String> = adapter.recognize(input)

    companion object {
        private const val TAG = "MisoupOcr"
    }
}

/** 慢设备阈值：文档约定 5s */
const val SLOW_DEVICE_THRESHOLD_MS = 5_000L

/**
 * 识别器适配核心（T07，纯 JVM 可测）：
 * 引擎调用 → 结果格式化（文本.md 内容）→ 慢设备标记。
 */
class RecognizerAdapter<T>(
    private val engine: suspend (T) -> OcrResult,
    private val format: (OcrResult) -> String = { OcrTextFormat.content(it.text) },
    private val slowThresholdMs: Long = SLOW_DEVICE_THRESHOLD_MS,
    private val onSlow: (Long) -> Unit = {},
) : Recognizer<T> {

    override suspend fun recognize(input: T): Result<String> = runCatching {
        val result = engine(input)
        if (result.latencyMs > slowThresholdMs) onSlow(result.latencyMs)
        format(result)
    }
}
