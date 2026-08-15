package com.misoup.app.recognition

/**
 * T09 识别器抽象
 *
 * 模块 01 文档定义：`suspend fun recognize(bitmap: Bitmap): Result<String>`。
 * 此处泛型化为 [T]（生产 = Bitmap，JVM 单测可用字符串桩），语义不变。
 *
 * 契约：
 * - 成功 → Result.success(题干文本)：调用方负责写 文本.md + 更新 ocr 状态 + 倒排
 * - 失败 → Result.failure(原因)：调用方标记 ocr.status=failed，可重试
 * - 实现必须可重入（识别队列单线程消费，但 retry 可能并发入队）
 */
fun interface Recognizer<T> {
    /** 对预处理后的图片执行识别，返回识别文本（题干全文） */
    suspend fun recognize(input: T): Result<String>
}

/**
 * 识别引擎占位空实现（T09 状态流转先行）：
 * 模块 05 接入真实 OCR 前统一返回失败，驱动 pending → failed 流转与重试链路。
 */
class DummyRecognizer<T> : Recognizer<T> {
    override suspend fun recognize(input: T): Result<String> =
        Result.failure(IllegalStateException("识别引擎未接入（模块 05 提供）"))
}
