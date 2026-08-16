package com.misoup.app.sync.backup

import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.store.MetaJson
import java.io.File

/**
 * 恢复服务（模块 09 T07）：SAF 选 zip → 校验 manifest → 解压进库 → 同名不同 hash 保留两份
 *
 * 流程：
 * 1. 读 manifest（缺失/损坏 → failure）
 * 2. 解压到暂存目录（[stagingRoot]，调用方给应用 cache）
 * 3. 逐条目 hash 校验（与 manifest 记录比对；失败 → 整次恢复中止并报告损坏条目）
 * 4. 落库：同名同 hash → 跳过；同名不同 hash 或同 id 不同 hash → 导入为 `<dirName>.imported-<ts>`（两份保留）；
 *    全新 → 原样导入
 * 5. 导入条目并入内存索引（本会话立即可见）
 *
 * 纯文件逻辑（无 Android 依赖），JVM 单测全覆盖；SAF 选 zip 由 UI 层（⏸ 待人工）接入。
 */
class RestoreService(
    private val libraryRoot: File,
    private val index: LibraryIndex,
    private val stagingRoot: File
) {

    /** 恢复结果 */
    data class RestoreReport(
        val restored: Int = 0,          // 新落库条目数
        val skippedSame: Int = 0,       // 同名同 hash 跳过
        val importedAsConflict: Int = 0,// 同名不同 hash → .imported-<ts> 两份保留
        val failed: List<String> = emptyList()
    ) {
        val isSuccess: Boolean get() = failed.isEmpty()
    }

    /**
     * 校验 zip：manifest 可读 + 全部条目 hash 通过。
     * @return 校验通过 → 空列表；否则损坏条目 id 列表
     */
    fun verify(source: BackupSource): Result<List<String>> = runCatching {
        val input = source.openInput() ?: throw IllegalStateException("无法读取备份文件")
        val manifest = BackupReader.readManifest(input).getOrThrow()
        val extracted = BackupReader.extract(source.openInput()!!, stagingRoot)
        manifest.entries.mapNotNull { me ->
            val dir = extracted["${me.subject}/${me.dirName}"]
            if (dir == null || !EntryHasher.verify(dir, me)) me.id else null
        }
    }

    /** 校验并恢复（校验失败 → failure，不落任何条目） */
    fun restore(source: BackupSource, now: Long = System.currentTimeMillis()): Result<RestoreReport> {
        val corrupt = verify(source).getOrElse { return Result.failure(it) }
        if (corrupt.isNotEmpty()) {
            return Result.failure(IllegalStateException("备份校验失败，损坏条目: ${corrupt.joinToString(",")}"))
        }
        return runCatching {
            val manifest = BackupReader.readManifest(source.openInput()!!).getOrThrow()
            val extracted = BackupReader.extract(source.openInput()!!, stagingRoot)
            var restored = 0
            var skipped = 0
            var conflicts = 0
            val failed = ArrayList<String>()

            for (me in manifest.entries) {
                val staged = extracted["${me.subject}/${me.dirName}"]
                    ?: run { failed += "${me.id}（zip 内缺失）"; continue }
                val existing = findExisting(me)
                val targetName = when {
                    existing == null -> me.dirName                                  // 全新导入
                    existing.second == me.hash -> { skipped++; continue }           // 同名同 hash 跳过
                    else -> "${me.dirName}.imported-$now"                           // 同名不同 hash 保留两份
                }
                // 目标目录已存在（索引陈旧等）→ 追加序号避免覆盖任何现有数据
                var targetDir = File(File(libraryRoot, me.subject), targetName)
                var n = 2
                while (targetDir.exists()) {
                    targetDir = File(File(libraryRoot, me.subject), "$targetName-$n")
                    n++
                }
                targetDir.mkdirs()
                if (!copyDir(staged, targetDir)) {
                    failed += "${me.id}（写入失败）"
                    continue
                }
                if (existing == null) restored++ else conflicts++
                indexImported(targetDir)
            }
            RestoreReport(restored, skipped, conflicts, failed)
        }
    }

    /** 查本地是否已有该条目：按 id 或 (subject, dirName)；返回 (id, hash) */
    private fun findExisting(me: ManifestEntry): Pair<String, String>? {
        val byId = index.get(me.id)
        if (byId != null && !byId.needsRepair) {
            val dir = File(File(libraryRoot, byId.subject), byId.dirName)
            if (dir.isDirectory) return byId.id to EntryHasher.hashOf(dir)
        }
        val byName = index.all().firstOrNull {
            !it.needsRepair && it.subject == me.subject && it.dirName == me.dirName
        }
        if (byName != null) {
            val dir = File(File(libraryRoot, byName.subject), byName.dirName)
            if (dir.isDirectory) return byName.id to EntryHasher.hashOf(dir)
        }
        return null
    }

    private fun copyDir(from: File, to: File): Boolean = runCatching {
        from.listFiles()?.forEach { f ->
            if (f.isDirectory) copyDir(f, File(to, f.name))
            else f.copyTo(File(to, f.name), overwrite = true)
        }
        true
    }.getOrDefault(false)

    /** 导入条目并入内存索引（读落盘 meta.json） */
    private fun indexImported(dir: File) {
        runCatching {
            val metaFile = File(dir, MisoupConfigConst.META_FILE_NAME)
            if (!metaFile.isFile) return
            val meta = MetaJson.parse(metaFile.readText()).getOrNull() ?: return
            index.upsert(meta, null, dirName = dir.name)
        }
    }
}
