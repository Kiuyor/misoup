package com.misoup.app.output.share

import com.misoup.app.output.ExportItem
import com.misoup.app.output.markdown.MarkdownExporter
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 分享打包（父任务书 T07 / tasks T07）。
 *
 * 规则（模块 08 定稿）：
 * - 单题：Markdown 文本 + 原图（经 FileProvider 一起发）
 * - 多题：临时 zip（`错题导出/<NN>_<id前8位>/题目.md` + 原图）
 *
 * 本类只做**纯逻辑**（清单构造 + zip 写流），FileProvider/Intent 在 [ShareIntents]（Android 层）。
 * JVM 可测：清单文件名、sanitize、zip 字节内容。
 */
object SharePacker {

    /** zip 内根目录名 */
    const val ZIP_ROOT = "错题导出"

    /** 单题分享的 Markdown 文件名（与 md 正文同目录展示） */
    const val SINGLE_MD_NAME = "题目.md"

    /** 待写文件规格（data 与 path 二选一） */
    data class ShareFile(
        val name: String,          // zip 内完整路径
        val data: ByteArray? = null,
        val path: String? = null   // 源文件绝对路径（分享/打包时读）
    )

    /** 文件名净化：zip 条目名不允许 / \ : * ? " < > |；空 → 「未命名」 */
    fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "未命名" }
    }

    /**
     * 构造 zip 条目清单（多题分享）。
     * 顺序：题目.md → 原图（若存在）。目录名用净化后的 meta.id 前 8 位（ExportItem 不含展示名）。
     */
    fun buildZipEntries(items: List<ExportItem>): List<ShareFile> {
        val files = mutableListOf<ShareFile>()
        items.forEachIndexed { index, item ->
            val dirName = sanitize(item.meta.id.take(8).ifBlank { "题目" })
            val dir = "${ZIP_ROOT}/${String.format("%02d", index + 1)}_$dirName"
            files += ShareFile(
                name = "$dir/题目.md",
                data = MarkdownExporter.renderQuestion(index + 1, item).toByteArray(Charsets.UTF_8)
            )
            val imagePath = item.imagePath?.takeIf { it.isNotBlank() }
            if (imagePath != null && File(imagePath).isFile) {
                val imageName = item.meta.images.original.ifBlank { "原图.webp" }
                files += ShareFile(name = "$dir/${sanitize(imageName)}", path = imagePath)
            }
        }
        return files
    }

    /** 将清单写入 zip 流（UTF-8；图片按字节原样） */
    fun writeZip(files: List<ShareFile>, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zos ->
            files.forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                val bytes = f.data ?: File(f.path!!).readBytes()
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    /** 单题分享内容：md 正文（仅题目 + 图引用，不带头部文档标题） */
    fun singleMarkdown(item: ExportItem): String =
        MarkdownExporter.renderQuestion(1, item).trimEnd('\n')
}
