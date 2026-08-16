package com.misoup.app.sync.importexport

import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.store.MetaJson
import com.misoup.app.sync.backup.BackupManifest
import com.misoup.app.sync.backup.BackupReader
import com.misoup.app.sync.backup.BackupResult
import com.misoup.app.sync.backup.BackupSink
import com.misoup.app.sync.backup.BackupSource
import com.misoup.app.sync.backup.BackupWriter
import com.misoup.app.sync.backup.EntryHasher
import java.io.File

/**
 * 单条目导入/导出（模块 09 T08）
 *
 * - 导出：单题/多题 → zip（目录结构原样 + manifest，kind=single-export）→ 分享/保存
 * - 导入：识别 manifest（校验 id+hash）或裸目录（扫描 meta.json）→ 校验 meta → 进库；
 *   重复 id → 跳过并报告（不覆盖、不产生副本）
 *
 * 纯文件逻辑（无 Android 依赖），JVM 单测全覆盖；SAF 分享/选择由 UI 层（⏸ 待人工）接入。
 */
class ImportExportService(
    private val libraryRoot: File,
    private val index: LibraryIndex,
    private val stagingRoot: File
) {

    /** 导入结果 */
    data class ImportReport(
        val imported: Int = 0,
        val skippedDuplicates: Int = 0,
        val skippedInvalid: Int = 0,
        val failed: List<String> = emptyList()
    ) {
        val isSuccess: Boolean get() = failed.isEmpty()
    }

    private val writer = BackupWriter(libraryRoot, index)

    /** T08 导出：指定 id 列表 → zip（manifest kind=single-export） */
    fun export(sink: BackupSink, ids: List<String>): Result<BackupResult> {
        if (ids.isEmpty()) return Result.failure(IllegalStateException("未选择要导出的条目"))
        return writer.write(sink, ids = ids, kind = BackupManifest.KIND_SINGLE)
    }

    /** 导入 zip：优先按 manifest（校验 hash），无 manifest 按裸目录扫描 */
    fun importZip(source: BackupSource): Result<ImportReport> = runCatching {
        clearStaging()
        val input = source.openInput() ?: throw IllegalStateException("无法读取导入文件")
        val manifest = BackupReader.readManifest(input).getOrNull()   // 裸 zip 允许无 manifest
        val extracted = BackupReader.extract(source.openInput()!!, stagingRoot)

        val report = if (manifest != null) {
            importWithManifest(manifest, extracted)
        } else {
            importScanned()
        }
        report
    }

    /** 导入裸目录（staging 内已解包/外部目录均可）：扫描含 meta.json 的条目目录 */
    fun importBareDir(dir: File): Result<ImportReport> = runCatching {
        clearStaging()
        copyTree(dir, stagingRoot)
        importScanned()
    }

    /** 按 manifest 顺序导入：hash 校验 + meta 校验 + id 去重 */
    private fun importWithManifest(manifest: BackupManifest, extracted: Map<String, File>): ImportReport {
        var imported = 0
        var dup = 0
        var invalid = 0
        val failed = ArrayList<String>()
        for (me in manifest.entries) {
            val dir = extracted["${me.subject}/${me.dirName}"]
            if (dir == null) { failed += "${me.id}（zip 内缺失）"; continue }
            if (!EntryHasher.verify(dir, me)) { failed += "${me.id}（hash 校验失败）"; continue }
            when (importEntry(dir)) {
                ImportOutcome.IMPORTED -> imported++
                ImportOutcome.DUPLICATE -> dup++
                ImportOutcome.INVALID -> invalid++
            }
        }
        return ImportReport(imported, dup, invalid, failed)
    }

    /** 裸目录扫描导入：staging 下所有含 meta.json 的条目目录 */
    private fun importScanned(): ImportReport {
        var imported = 0
        var dup = 0
        var invalid = 0
        val failed = ArrayList<String>()
        val entryDirs = scanEntryDirs(stagingRoot)
        if (entryDirs.isEmpty() && stagingRoot.listFiles().isNullOrEmpty()) {
            failed += "导入文件为空"
        }
        for (dir in entryDirs) {
            when (importEntry(dir)) {
                ImportOutcome.IMPORTED -> imported++
                ImportOutcome.DUPLICATE -> dup++
                ImportOutcome.INVALID -> invalid++
            }
        }
        return ImportReport(imported, dup, invalid, failed)
    }

    private enum class ImportOutcome { IMPORTED, DUPLICATE, INVALID }

    /** 单条目进库：meta 可解析 + id 未重复；重复 id → 跳过并报告 */
    private fun importEntry(dir: File): ImportOutcome {
        val metaFile = File(dir, MisoupConfigConst.META_FILE_NAME)
        if (!metaFile.isFile) return ImportOutcome.INVALID
        val meta = MetaJson.parse(runCatching { metaFile.readText() }.getOrNull() ?: return ImportOutcome.INVALID)
            .getOrNull() ?: return ImportOutcome.INVALID
        if (index.get(meta.id) != null) return ImportOutcome.DUPLICATE   // 重复 id 跳过
        if (meta.subject.isBlank() || meta.id.isBlank()) return ImportOutcome.INVALID
        // D 修复：subject 必须为合法单段目录名——拒绝路径分隔/穿越/隐藏名，
        // 否则 File(库根, subject) 可写到库外或产生嵌套目录（同步层按两段路径定位会失联）
        if (!isSafeSubject(meta.subject)) return ImportOutcome.INVALID

        // 目标目录名：优先 manifest/目录原名，冲突追加序号
        val targetBase = dir.name
        var target = File(File(libraryRoot, meta.subject), targetBase)
        var n = 2
        while (target.exists()) {
            target = File(File(libraryRoot, meta.subject), "$targetBase-$n")
            n++
        }
        target.mkdirs()
        copyTree(dir, target)
        index.upsert(meta, null, dirName = target.name)
        return ImportOutcome.IMPORTED
    }

    /** 合法科目目录名：非空、单段、非隐藏、非路径穿越（D 修复） */
    private fun isSafeSubject(subject: String): Boolean =
        subject.isNotBlank() &&
            !subject.contains('/') && !subject.contains('\\') &&
            !subject.startsWith(".") && subject != ".."

    /** staging 下扫描：<科目>/<条目目录>/meta.json 结构 */
    private fun scanEntryDirs(root: File): List<File> {
        val result = ArrayList<File>()
        root.listFiles()?.forEach { subjectDir ->
            if (!subjectDir.isDirectory || subjectDir.name.startsWith(".")) return@forEach
            subjectDir.listFiles()?.forEach { entryDir ->
                if (entryDir.isDirectory && !entryDir.name.startsWith(".") &&
                    File(entryDir, MisoupConfigConst.META_FILE_NAME).isFile
                ) {
                    result += entryDir
                }
            }
        }
        return result
    }

    private fun copyTree(from: File, to: File) {
        from.listFiles()?.forEach { f ->
            if (f.isDirectory) copyTree(f, File(to, f.name))
            else f.copyTo(File(to, f.name), overwrite = true)
        }
    }

    private fun clearStaging() {
        stagingRoot.listFiles()?.forEach { it.deleteRecursively() }
        stagingRoot.mkdirs()
    }
}
