package com.misoup.app.recognition.paddle.write

import com.misoup.app.data.model.OcrState
import com.misoup.app.data.store.StoreService
import com.misoup.app.recognition.paddle.model.PaddleModels

/**
 * OCR 结果 → 文本.md 临时段 与 meta.ocr 状态（T06，纯逻辑）
 *
 * 文档约定：识别结果写入 文本.md 临时段 `[OCR]`：`## 题干\n<lines 文本>`
 * （未做结构拆分前整段放题干；模块 05 视觉 LLM 完成结构拆分后重写分区）。
 *
 * 文档矛盾裁决：module/04-offline-ocr.md 的「临时段 [OCR]」为章节描述性标签，
 * 并非要写入文件字面量 `[OCR]`——文本.md 是用户可见的题干文件，写 `[OCR]`
 * 会污染展示文本。本模块裁决采用工程既有的分段约定
 * （ui/library/LibraryTextFormat：`## 题干` 一级标题，模块 02 解析器可读回），
 * 标头为 [LibraryTextFormat.QUESTION_HEADER]。
 */
object OcrTextFormat {

    const val QUESTION_HEADER = "## 题干"

    /** 识别文本 → 文本.md 内容（整段题干，未拆分） */
    fun content(rawText: String): String {
        val t = rawText.trim()
        if (t.isEmpty()) return ""
        return "$QUESTION_HEADER\n$t"
    }
}

/** meta.ocr 状态流转（T06） */
object OcrStateLogic {

    const val ENGINE_PADDLE = "paddle"
    const val STATUS_DONE = "done"
    const val STATUS_FAILED = "failed"

    /** 识别成功：engine=paddle, status=done, model=版本标签, at=时间戳 */
    fun onSuccess(now: String): OcrState =
        OcrState(engine = ENGINE_PADDLE, status = STATUS_DONE, model = PaddleModels.MODEL_TAG, at = now)

    /** 识别失败：status=failed + at=时间戳（可重试；engine/model 保留原值） */
    fun onFailure(now: String): OcrState =
        OcrState(engine = ENGINE_PADDLE, status = STATUS_FAILED, at = now)

    /** 失败是否可重试（T08）：模型缺失/损坏/推理失败均可重试；空原因视为可重试 */
    fun retryable(error: String?): Boolean = error == null || !isPermanent(error)

    /** 永久失败（重试无意义）：配置类错误，如字典缺失导致解析失败 */
    fun isPermanent(error: String): Boolean =
        error.contains("永久") || error.contains("配置")
}

/**
 * T06 写入管线：识别结果 → 文本.md（整段题干）+ meta.ocr 状态写回。
 *
 * 生产链路说明：模块 01 的 RecognitionQueue 在识别成功后自行执行
 * updateText + ocr.status=done（writeBackSuccess），故当前生产路径由队列完成；
 * 本管线为模块 04 文档规定的写入语义（含 model 字段、[OcrTextFormat] 格式化、
 * 失败置 failed）的权威实现，供：
 * 1. T06 单测驱动验证（mock 引擎 → 真实 StoreService 断言落盘内容）
 * 2. 模块 05 视觉 LLM 写入复用（其识别结果同样需要「整段题干 + done」落盘）
 */
class OcrWritePipeline(
    private val store: StoreService,
) {

    /** 识别成功：格式化文本写 文本.md + meta.ocr{engine:paddle,status:done,model,at} */
    suspend fun writeSuccess(id: String, rawText: String, now: String): Result<Unit> {
        val content = OcrTextFormat.content(rawText)
        if (content.isNotBlank()) {
            store.updateText(id, content).getOrThrow()
        }
        return store.update(id) { meta -> meta.copy(ocr = OcrStateLogic.onSuccess(now)) }
    }

    /** 识别失败：仅标记 meta.ocr.status=failed（文本不动，可重试） */
    suspend fun writeFailed(id: String, now: String): Result<Unit> =
        store.update(id) { meta -> meta.copy(ocr = OcrStateLogic.onFailure(now)) }
}
