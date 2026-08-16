package com.misoup.app.output.pdf

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 导出建议文件名（SAF 默认名 / 分享文件名共用）。
 * 格式：错题汤_<科目>_<yyyyMMdd>_<HHmmss>.<ext>（纯 JVM 可测）
 */
object ExportFileName {

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    /** @param ext 扩展名（md/csv/pdf，不含点） */
    fun suggest(subject: String, ext: String, now: LocalDateTime = LocalDateTime.now()): String {
        val safeSubject = subject.trim()
            .ifBlank { "未分类" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "错题汤_${safeSubject}_${STAMP.format(now)}.$ext"
    }
}
