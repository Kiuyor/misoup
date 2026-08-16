package com.misoup.app.output.saver

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * SAF 导出落盘（父任务书 T06 / tasks T04 保存部分）。
 *
 * 规格（模块 08 定稿）：`ACTION_CREATE_DOCUMENT` 免存储权限，用户自选目录；
 * UI 层用 `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime))`
 * 拿到目标 uri 后调用 [writeUri]。
 *
 * MIME：PDF=application/pdf；Markdown=text/markdown；CSV=text/csv（含 BOM，Excel 兼容）；
 * ZIP=application/zip。
 *
 * ⚠️ 依赖 Activity Result API，需模拟器/真机验证（JVM 单测不覆盖；纯逻辑见 ExportFileName）。
 */
object ExportSaver {

    const val MIME_PDF = "application/pdf"
    const val MIME_MARKDOWN = "text/markdown"
    const val MIME_CSV = "text/csv"
    const val MIME_ZIP = "application/zip"

    /** 将内容写入 SAF uri；失败返回 false（调用方 Toast 提示） */
    fun writeUri(context: Context, uri: Uri, content: ByteArray): Boolean =
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content)
            } ?: throw IllegalStateException("无法打开目标文件: $uri")
        }.isSuccess

    /** 字符串便捷版（UTF-8） */
    fun writeUri(context: Context, uri: Uri, content: String): Boolean =
        writeUri(context, uri, content.toByteArray(Charsets.UTF_8))

    /** 由字符串构建内存字节（测试/工具用） */
    fun bytesOf(content: String): ByteArray = content.toByteArray(Charsets.UTF_8)
}
