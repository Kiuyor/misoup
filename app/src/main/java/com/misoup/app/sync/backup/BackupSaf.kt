package com.misoup.app.sync.backup

import android.content.ContentResolver
import android.net.Uri
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.sync.importexport.ImportExportService
import java.io.File

/**
 * SAF（Storage Access Framework）适配（模块 09 T06/T07/T08）：ContentResolver ⇄ BackupSink/Source
 *
 * - 备份保存：UI 经 SAF createDocument 拿到 Uri → [SafBackupSink] → [BackupWriter]
 * - 恢复/导入：UI 经 SAF openDocument 拿到 Uri → [SafBackupSource] → [RestoreService]/[ImportExportService]
 *
 * ⚠️ 依赖 Android 运行时（ContentResolver），JVM 单测不覆盖（核心逻辑已由
 * BackupWriter/RestoreService/ImportExportService 单测覆盖）；SAF 交互 ⏸ 待人工真机验证。
 */
class SafBackupSink(
    private val resolver: ContentResolver,
    private val uri: Uri
) : BackupSink {
    override fun openOutput() = resolver.openOutputStream(uri, "wt")
}

class SafBackupSource(
    private val resolver: ContentResolver,
    private val uri: Uri
) : BackupSource {
    override fun openInput() = resolver.openInputStream(uri)
}

/**
 * 备份/恢复/导入装配入口（生产）：给定库根/索引/缓存目录与 SAF Uri，执行完整操作。
 * UI 层（设置页/详情页）调用；返回结果可直接展示。
 */
class BackupController(
    private val libraryRoot: File,
    private val index: LibraryIndex,
    private val cacheDir: File
) {
    private val staging = File(cacheDir, "misoup-backup-staging")

    /** T06 整库备份到 SAF Uri */
    fun backupTo(resolver: ContentResolver, uri: Uri): Result<BackupResult> =
        BackupWriter(libraryRoot, index).write(SafBackupSink(resolver, uri))

    /** T07 从 SAF Uri 恢复（校验 → 解压进库；同名不同 hash 保留两份） */
    fun restoreFrom(resolver: ContentResolver, uri: Uri): Result<RestoreService.RestoreReport> =
        RestoreService(libraryRoot, index, staging).restore(SafBackupSource(resolver, uri))

    /** T08 单条目导出到 SAF Uri */
    fun exportTo(resolver: ContentResolver, uri: Uri, ids: List<String>): Result<BackupResult> =
        ImportExportService(libraryRoot, index, staging).export(SafBackupSink(resolver, uri), ids)

    /** T08 从 SAF Uri 导入（重复 id 跳过并报告） */
    fun importFrom(resolver: ContentResolver, uri: Uri): Result<ImportExportService.ImportReport> =
        ImportExportService(libraryRoot, index, staging).importZip(SafBackupSource(resolver, uri))
}
