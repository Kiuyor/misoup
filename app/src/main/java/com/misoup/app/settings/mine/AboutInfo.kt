package com.misoup.app.settings.mine

/**
 * 「关于」信息（模块 10 T06，纯逻辑）
 *
 * 版本号 / GitHub / GPL-3.0 / 隐私政策（链接 + 摘要）/ 爱发电捐赠。
 * 链接 ⏸ 占位待人工前置（仓库地址/爱发电主页未定稿）。
 */
object AboutInfo {

    const val APP_NAME = "错题汤 Misoup"

    /** 开源仓库（⏸ 占位：仓库地址待人工确认后替换） */
    const val GITHUB_URL = "https://github.com/misoup/misoup"

    /** 开源许可（仓库根 LICENSE 文件为 GPL-3.0 全文） */
    const val LICENSE_NAME = "GPL-3.0"
    const val LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

    /** 隐私政策（仓库根 PRIVACY.md） */
    const val PRIVACY_URL = "https://github.com/misoup/misoup/blob/main/PRIVACY.md"

    /** 捐赠（⏸ 占位：爱发电主页待人工创建后替换） */
    const val DONATE_URL = "https://afdian.com/a/misoup"

    /** 隐私政策摘要（必须与 PRIVACY.md 实现一致，不夸大） */
    fun privacySummary(): String =
        "所有错题数据仅保存在本机应用专属目录，卸载即清除；不上传任何遥测/统计信息。\n" +
            "若你在「AI 增强」中配置了自己的大模型 Key（BYOK），开启视觉识别时题目图片与文本" +
            "会发送到你自行配置的第三方 API（智谱/通义/硅基流动等），关闭 AI 增强则完全离线。\n" +
            "本应用面向自主学习场景，不收集未成年人身份信息。"

    /** 摘要要点（供 UI 分条渲染） */
    fun privacySummaryLines(): List<String> = privacySummary().split("\n").filter { it.isNotBlank() }
}
