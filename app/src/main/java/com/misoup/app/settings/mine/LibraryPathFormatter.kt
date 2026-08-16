package com.misoup.app.settings.mine

/**
 * 库目录路径展示格式化（模块 10 T05，纯逻辑）
 *
 * 设置页「数据 → 库目录」：展示当前路径（[LibraryRoot.get]）+ 默认/自定义状态；
 * 长路径截断（保留尾部目录名，极客向信息不减）。
 */
object LibraryPathFormatter {

    /** 未设置/空路径时的占位文案 */
    const val DEFAULT_PLACEHOLDER = "默认目录（应用专属外部存储）"

    /** 展示截断长度（字符）；超过则保留尾部 */
    const val MAX_LENGTH = 44

    /**
     * 展示文案：
     * - null/空白 → [DEFAULT_PLACEHOLDER]
     * - 自定义目录 → 路径本身（超长截断）
     * - 默认目录 → 路径 + 「（默认）」后缀
     */
    fun displayPath(path: String?, customized: Boolean): String {
        if (path.isNullOrBlank()) return DEFAULT_PLACEHOLDER
        val suffix = if (customized) "" else "（默认）"
        return shorten(path, MAX_LENGTH) + suffix
    }

    /**
     * 超长截断：保留尾部（目录名/文件名信息价值高），头部省略号。
     * 长度 ≤ max 原样返回；max < 1 防御按 1 处理。
     */
    fun shorten(path: String, max: Int = MAX_LENGTH): String {
        val limit = max.coerceAtLeast(1)
        if (path.length <= limit) return path
        return "…" + path.takeLast(limit - 1)
    }
}
