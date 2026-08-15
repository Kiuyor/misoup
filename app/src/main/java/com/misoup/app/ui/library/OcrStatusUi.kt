package com.misoup.app.ui.library

/**
 * 识别状态条（T10）：把 meta.ocr.status 映射为 UI 模型。
 *
 * - pending → 「识别中…」（灰色点 + 转圈）
 * - failed  → 「识别失败」+「重试」按钮（可点击，回调 [OcrRetryHandler.retry]）
 * - done    → 无徽标
 * - 损坏条目（needsRepair）→ 「待修复」（T10 修复入口）
 *
 * 模块 01 的 CaptureCoordinator.retry(id) 未落定前，用本地接口声明调用点；
 * 01 完成后在 AppGraph 注入真实实现。
 */
sealed class OcrStatusUi {
    data object Pending : OcrStatusUi()
    data object Failed : OcrStatusUi()
    data object Done : OcrStatusUi()
    data object Repair : OcrStatusUi()
}

/** 识别重试入口抽象（模块 01 落定后接 CaptureCoordinator.retry） */
fun interface OcrRetryHandler {
    /** 对指定条目重新触发识别 */
    fun retry(id: String)
}

object OcrStatusLogic {

    /** 状态字符串 → UI 模型；未知状态按 Done 处理（不阻塞展示） */
    fun of(status: String?, needsRepair: Boolean): OcrStatusUi {
        if (needsRepair) return OcrStatusUi.Repair
        return when (status) {
            "pending" -> OcrStatusUi.Pending
            "failed" -> OcrStatusUi.Failed
            "done" -> OcrStatusUi.Done
            else -> OcrStatusUi.Done   // 未知/空：视为已完成，不打扰
        }
    }

    /** 状态条文案（全中文；done/repair 不占用状态条） */
    fun label(status: OcrStatusUi): String = when (status) {
        OcrStatusUi.Pending -> "识别中…"
        OcrStatusUi.Failed -> "识别失败"
        OcrStatusUi.Repair -> "待修复"
        OcrStatusUi.Done -> ""
    }

    /** 是否需要显示状态条（pending/failed/repair 显示；done 不显示） */
    fun needsStatusBar(status: OcrStatusUi): Boolean = status != OcrStatusUi.Done
}
