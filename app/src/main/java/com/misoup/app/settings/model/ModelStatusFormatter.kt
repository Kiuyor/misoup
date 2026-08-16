package com.misoup.app.settings.model

import com.misoup.app.recognition.paddle.model.ModelBundle

/**
 * OCR 模型状态文案（模块 10 T03，纯逻辑）
 *
 * 设置页「识别模型 → OCR 引擎状态 + 模型版本」：把 [ModelBundle] 盘点结果
 * 翻译成中文状态（正常/缺失/损坏 + 版本），并附自换模型文件说明。
 */
object ModelStatusFormatter {

    /** 自换模型说明（module/10「识别模型 → 自换模型文件说明（文件名约定文档链接）」） */
    const val SWAP_MODEL_GUIDE =
        "自换模型：将 det.<版本>.onnx / rec.<版本>.onnx / cls.<版本>.onnx 与 ppocr_keys_v1.txt " +
            "放入应用目录 files/models/，文件名即版本约定（三件套版本需一致），重启后生效。"

    /** 健康判定：三件套齐全 + 版本一致 + 文件体积达标 */
    fun isHealthy(bundle: ModelBundle): Boolean = bundle.valid

    /**
     * 状态文案：
     * - 正常 → `正常（PP-OCRv4 mobile v1.0.0）`
     * - 仅缺失 → `模型缺失（det/rec），请在下方下载`
     * - 仅损坏 → `模型损坏（cls），请重新下载`
     * - 两者都有 → `模型缺失（det）且损坏（rec），请重新下载`
     */
    fun describe(bundle: ModelBundle): String = when {
        bundle.valid -> "正常（PP-OCRv4 mobile v${bundle.version}）"
        bundle.missing.isNotEmpty() && bundle.corrupt.isNotEmpty() ->
            "模型缺失（${bundle.missing.joinToString("/")}）且损坏（${bundle.corrupt.joinToString("/")}），请重新下载"
        bundle.missing.isNotEmpty() ->
            "模型缺失（${bundle.missing.joinToString("/")}），请在下方下载"
        else ->
            "模型损坏（${bundle.corrupt.joinToString("/")}），请重新下载"
    }
}
