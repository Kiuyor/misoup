package com.misoup.app.output.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.misoup.app.output.ExportItem
import java.io.File

/**
 * 系统分享（父任务书 T07 / tasks T07 Android 部分）。
 *
 * - 单题：Markdown 文本（EXTRA_TEXT）+ 原图（EXTRA_STREAM），经 FileProvider
 *   （authority `com.misoup.app.fileprovider`）授予临时读权限
 * - 多题：SharePacker 打包临时 zip → FileProvider uri → ACTION_SEND
 *
 * ⚠️ 依赖 FileProvider/Intent，需模拟器或真机验证（JVM 单测覆盖打包纯逻辑 SharePacker）。
 */
object ShareIntents {

    const val FILE_PROVIDER_AUTHORITY = "com.misoup.app.fileprovider"

    /** 多题 zip 缓存目录 */
    private fun shareDir(context: Context): File =
        File(context.cacheDir, "misoup_share").apply { mkdirs() }

    /** 单题分享：md 文本 + 原图（有图时） */
    fun shareSingle(context: Context, item: ExportItem): Intent {
        val md = SharePacker.singleMarkdown(item)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, md)
        }
        val imageUri = fileUri(context, item.imagePath)
        if (imageUri != null) {
            intent.putExtra(Intent.EXTRA_STREAM, imageUri)
            intent.type = mimeOf(item.meta.images.original)   // image/* 优先，微信可预览
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    /** 多题分享：临时 zip */
    fun shareZip(context: Context, items: List<ExportItem>, zipName: String): Intent? {
        val files = SharePacker.buildZipEntries(items)
        if (files.isEmpty()) return null
        val zipFile = File(shareDir(context), zipName)
        if (!runCatching {
                SharePacker.writeZip(files, zipFile.outputStream())
            }.isSuccess) {
            return null
        }
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, zipFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** 原图 → FileProvider uri（仅当文件真实存在） */
    private fun fileUri(context: Context, path: String?): Uri? {
        if (path.isNullOrBlank()) return null
        val f = File(path)
        if (!f.isFile) return null
        return FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, f)
    }

    /** 图片 MIME 推断（webp/jpg/png；未知回退 application/octet-stream） */
    fun mimeOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        else -> "application/octet-stream"
    }
}
