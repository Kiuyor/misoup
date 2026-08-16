package com.misoup.app.ui.screens.mine

/**
 * 「我的」设置页分组（模块 10 T01）
 *
 * 对应 module/10-settings-security.md 的设置页树形结构：
 * 统计概览 / AI 增强 / 识别模型 / 复习 / 数据 / 关于
 */
enum class MineGroup(
    val id: String,
    val displayName: String,
    /** 列表项副标题（极客向，技术细节可见） */
    val subtitle: String,
) {
    AI("ai", "AI 增强", "视觉/文本模型配置 · 识别策略 · 限速"),
    MODEL("model", "识别模型", "OCR 引擎状态 · 版本 · 检查更新"),
    REVIEW("review", "复习", "FSRS 参数 · 复习提醒"),
    DATA("data", "数据", "库目录 · WebDAV · 备份/恢复/导入/导出 · 清理缓存"),
    ABOUT("about", "关于", "版本 · 开源许可 · 隐私政策 · 捐赠");

    companion object {
        fun byId(id: String): MineGroup? = entries.firstOrNull { it.id == id }
    }
}
