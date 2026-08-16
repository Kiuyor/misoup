package com.misoup.app.settings.model

import com.misoup.app.recognition.paddle.download.ModelMirror
import com.misoup.app.recognition.paddle.model.ModelBundle
import com.misoup.app.recognition.paddle.model.ModelVersion

/**
 * 模型更新检查决策（模块 10 T03，纯逻辑）
 *
 * 设置页「检查更新」：本地 [ModelBundle] + 镜像版本清单文本 → 更新决策。
 * 复用 04 模块的 [ModelMirror]（清单解析）与版本比较；本对象只做决策与文案。
 */
sealed class UpdateDecision {
    /** 无需下载：本地完好且已是最新 */
    data class UpToDate(val localVersion: String) : UpdateDecision()

    /** 有新版可更 */
    data class UpdateAvailable(val localVersion: String, val latestVersion: String) : UpdateDecision()

    /** 本地缺失/损坏，需要下载 */
    data class NeedsDownload(val reason: String) : UpdateDecision()

    /** 清单拉取/解析失败 */
    data class CheckFailed(val reason: String) : UpdateDecision()
}

object ModelUpdateCheck {

    /**
     * 决策：
     * 1. 清单 null → [CheckFailed]（网络失败）
     * 2. 清单非空但解析不出任何版本 → [CheckFailed]（格式异常）
     * 3. 本地不可用 → [NeedsDownload]
     * 4. 本地版本 < 最新 → [UpdateAvailable]
     * 5. 其余 → [UpToDate]
     */
    fun evaluate(local: ModelBundle, manifestText: String?): UpdateDecision {
        if (manifestText == null) {
            return UpdateDecision.CheckFailed("版本清单拉取失败（网络异常），请稍后重试")
        }
        val latest = ModelMirror.latestVersion(manifestText)
        if (latest == null) {
            return UpdateDecision.CheckFailed("版本清单解析失败：未找到合法版本号")
        }
        if (!local.valid) {
            return UpdateDecision.NeedsDownload(
                reason = if (local.missing.isNotEmpty()) {
                    "本地模型缺失（${local.missing.joinToString("/")}）"
                } else {
                    "本地模型损坏（${local.corrupt.joinToString("/")}）"
                }
            )
        }
        val localVersion = local.version?.let { ModelVersion.parse(it) }
        if (localVersion != null && localVersion < latest) {
            return UpdateDecision.UpdateAvailable(
                localVersion = local.version!!,
                latestVersion = latest.toString(),
            )
        }
        return UpdateDecision.UpToDate(localVersion = local.version ?: "未知")
    }

    /** 决策 → 设置页展示文案 */
    fun describe(decision: UpdateDecision): String = when (decision) {
        is UpdateDecision.UpToDate -> "已是最新版本（v${decision.localVersion}）"
        is UpdateDecision.UpdateAvailable ->
            "发现新版本 v${decision.latestVersion}（当前 v${decision.localVersion}），点击下方「下载最新模型」更新"
        is UpdateDecision.NeedsDownload -> "${decision.reason}，点击下方「下载最新模型」"
        is UpdateDecision.CheckFailed -> decision.reason
    }
}
