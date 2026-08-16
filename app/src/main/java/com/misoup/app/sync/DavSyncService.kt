package com.misoup.app.sync

import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.store.MetaJson
import com.misoup.app.data.store.StoreService
import com.misoup.app.sync.webdav.DavConfig
import com.misoup.app.sync.webdav.InsecureHttpException
import java.io.File

/**
 * WebDAV 同步服务（模块 09 T02/T03/T04）
 *
 * - 远程结构：`<dav根>/misoup-library/<科目>/<条目目录>/...`（与本地镜像）
 * - T02 [syncFull]：全量模式差集（忽略上次快照，镜像双端）；首同步即全量上传
 * - T03 [syncIncremental]：增量双向，差集上传/下载/删除传播
 * - T04：冲突保留较新 + 旧文件改名 `xxx.conflict-<ts>`；HTTPS 强制（http 拒绝 + 警告进报告）
 *
 * 网络层经 [client] 注入：生产 = SardineDavClient；JVM 单测 = 内存 fake。
 * 同步状态快照存远端 `.sync-state.json`，天然随远端走、多设备一致。
 */
class DavSyncService(
    private val cfg: () -> DavConfig,
    private val store: StoreService,
    private val index: LibraryIndex,
    private val client: DavClient,
    private val libraryRoot: File,
    private val statePath: String = DEFAULT_STATE_PATH
) {

    /** T02 全量同步：镜像整库到远端（忽略上次快照；双端都有且指纹不同 → 冲突裁决） */
    suspend fun syncFull(): SyncReport = runSync(ignoreState = true)

    /** T03 增量双向同步：差集上传/下载/删除传播 + T04 冲突处理 */
    suspend fun syncIncremental(): SyncReport = runSync(ignoreState = false)

    /** 同步主流程：配置校验（HTTPS 强制）→ 快照 → 差集 → 执行 → 回写状态 */
    private suspend fun runSync(ignoreState: Boolean): SyncReport {
        val config = cfg()
        config.validate().getOrElse { e ->
            // T04：http 拒绝 + 明确警告（不发起任何网络请求）
            val warn = when (e) {
                is InsecureHttpException -> e.message ?: "WebDAV 仅支持 HTTPS"
                else -> e.message ?: "WebDAV 配置无效"
            }
            return SyncReport(errors = listOf(warn))
        }

        val root = config.syncRoot()
        val remoteListing = try {
            client.listRecursive(root)
        } catch (e: Exception) {
            return SyncReport(errors = listOf("列取远端失败: ${e.message ?: e.javaClass.simpleName}"))
        }
        val remoteEntries = RemoteScanner.entryFingerprints(remoteListing)

        val localEntries = LocalScanner.entryFingerprints(index, libraryRoot)

        val state = loadState(root)
        val plan = SyncDiff.compute(
            local = localEntries,
            remote = remoteEntries,
            state = state,
            ignoreState = ignoreState
        )

        val errors = ArrayList<String>()
        var uploaded = 0
        var downloaded = 0
        var conflicts = 0
        var deleted = 0
        // 本次同步成功处理的条目路径（上传/下载/冲突/删除传播成功者）。
        // 失败条目从新快照剔除 → 下次对它们按「无快照」保守裁决（上传/下载方向由 mtime 冲突裁决），
        // 杜绝「传输失败却记为已同步」导致下次同步方向反转、旧版本覆盖新版本（F1）。
        val okPaths = HashSet<String>()

        for (action in plan.actions) {
            when (action) {
                is SyncDiff.Action.Upload -> {
                    val ok = uploadEntry(root, action.path)
                    if (ok) { uploaded++; okPaths += action.path } else errors += "上传失败: ${action.path}"
                }
                is SyncDiff.Action.Download -> {
                    val ok = downloadEntry(root, action.path)
                    if (ok) { downloaded++; okPaths += action.path } else errors += "下载失败: ${action.path}"
                }
                is SyncDiff.Action.Conflict -> {
                    val ok = resolveConflict(root, action)
                    if (ok) { conflicts++; okPaths += action.path } else errors += "冲突处理失败: ${action.path}"
                }
                is SyncDiff.Action.DeleteRemote -> {
                    val ok = runCatching { client.delete("$root/${action.path}") }.getOrDefault(false)
                    if (ok) { deleted++; okPaths += action.path } else errors += "远端删除失败: ${action.path}"
                }
                is SyncDiff.Action.DeleteLocal -> {
                    val dir = LocalScanner.resolveEntryDir(libraryRoot, action.path)
                    val ok = dir != null && dir.deleteRecursively()
                    if (ok) {
                        deleted++
                        okPaths += action.path
                        // 索引同步移除（否则本会话内列表残留；重启 rebuild 也会清理）
                        val parts = action.path.split('/')
                        index.all().firstOrNull { it.subject == parts[0] && it.dirName == parts[1] }
                            ?.let { index.remove(it.id) }
                    } else {
                        errors += "本地删除失败: ${action.path}"
                    }
                }
                is SyncDiff.Action.DropState -> Unit
            }
        }

        // 回写同步状态：快照只收录「本次同步后双端一致」的存活条目。
        // - 被删除条目剔除（删除传播后已不存在）
        // - 传输/冲突失败的条目剔除（下次按无快照保守裁决，不误判「未变」）
        // - 跳过的条目保留（否则下次同步误判「双端都变」产生假冲突）
        val deletedPaths = plan.actions
            .filterIsInstance<SyncDiff.Action.DeleteLocal>()
            .map { it.path } +
            plan.actions.filterIsInstance<SyncDiff.Action.DeleteRemote>().map { it.path }
        val failedPaths = plan.actions
            .filterNot { it is SyncDiff.Action.DropState }
            .mapNotNull { action ->
                val p = when (action) {
                    is SyncDiff.Action.Upload -> action.path
                    is SyncDiff.Action.Download -> action.path
                    is SyncDiff.Action.Conflict -> action.path
                    is SyncDiff.Action.DeleteRemote -> action.path
                    is SyncDiff.Action.DeleteLocal -> action.path
                    is SyncDiff.Action.DropState -> null
                }
                p?.takeIf { it !in okPaths }
            }
            .toSet()
        val excluded = deletedPaths.toSet() + failedPaths
        val entries = (localEntries.keys + remoteEntries.keys)
            .filterNot { it in excluded }
            .mapNotNull { path ->
                val l = localEntries[path]
                val r = remoteEntries[path]
                if (l == null && r == null) return@mapNotNull null
                val mtime = l?.mtime ?: r!!.mtime
                val size = l?.size ?: r!!.size
                path to SyncStateEntry(mtime, size)
            }
            .toMap()
        val newState = SyncState(lastSyncAt = System.currentTimeMillis(), entries = entries)
        val stateOk = writeState(root, newState)
        if (!stateOk && errors.isEmpty()) {
            errors += "同步状态回写失败（下次同步将按无快照保守处理）"
        }

        return SyncReport(
            uploaded = uploaded,
            downloaded = downloaded,
            conflicts = conflicts,
            deleted = deleted,
            skipped = plan.skipped,
            errors = errors,
            lastSyncAt = newState.lastSyncAt
        )
    }

    /** 上传整条目目录（远端逐级 mkdir → 递归收集全部文件逐个 PUT） */
    private suspend fun uploadEntry(root: String, relPath: String): Boolean {
        val localDir = LocalScanner.resolveEntryDir(libraryRoot, relPath) ?: return false
        val remoteDir = "$root/$relPath"
        return runCatching {
            client.mkdirs(remoteDir)
            // B 修复：递归收集（含 变式/ 等子目录），与 RemoteScanner「条目内深层文件也聚合进指纹」口径一致
            val files = localDir.walkTopDown()
                .filter { it.isFile && !it.name.startsWith(".") }
                .toList()
            files.all { f ->
                val rel = f.relativeTo(localDir).path.replace('\\', '/')
                val remote = "$remoteDir/$rel"
                if (client.upload(f, remote)) {
                    // A 附带：上传后把本地 mtime 对齐服务器 Last-Modified——
                    // 否则本地指纹（本地时钟）与远端指纹（服务器时钟）永不一致，
                    // 下次同步误判「远端变」→ 全库无限下载回环
                    client.lastModified(remote)?.let { f.setLastModified(it) }
                    true
                } else {
                    false
                }
            }
        }.getOrDefault(false)
    }

    /** 下载整条目目录（远端文件清单来自 PROPFIND 结果，按相对路径落盘保持子目录结构） */
    private suspend fun downloadEntry(root: String, relPath: String): Boolean {
        return runCatching {
            val remoteListing = client.listRecursive("$root/$relPath")
            val files = remoteListing.filter { !it.isDirectory && !it.path.substringAfterLast('/').startsWith(".") }
            if (files.isEmpty()) return@runCatching false   // 远端空目录不视为条目
            val localDir = LocalScanner.ensureEntryDir(libraryRoot, relPath) ?: return@runCatching false
            val ok = files.all { info ->
                // B 修复：远端条目内相对路径（如 变式/v1.md）原样落盘，不拍平到条目根
                val rel = info.path
                val target = File(localDir, rel)
                val remote = "$root/$relPath/$rel"
                if (client.download(remote, target)) {
                    // A 附带：下载后本地 mtime 对齐服务器 Last-Modified（双端指纹一致，防回环）
                    client.lastModified(remote)?.let { target.setLastModified(it) }
                    true
                } else {
                    false
                }
            }
            if (ok) indexDownloaded(localDir)
            ok
        }.getOrDefault(false)
    }

    /** 下载成功后把条目并入内存索引（本会话立即可见，无需重启 rebuild） */
    private fun indexDownloaded(localDir: File) {
        runCatching {
            val metaFile = File(localDir, MisoupConfigConst.META_FILE_NAME)
            if (!metaFile.isFile) return
            val meta = MetaJson.parse(metaFile.readText()).getOrNull() ?: return
            index.upsert(meta, null, dirName = localDir.name)
        }
    }

    /** T04 冲突：赢家作正式条目，败者旧文件改名 `xxx.conflict-<ts>`（不删任何一方） */
    private suspend fun resolveConflict(root: String, action: SyncDiff.Action.Conflict): Boolean {
        return runCatching {
            when (action.winner) {
                SyncDiff.Winner.LOCAL -> {
                    // 本地较新：远端旧文件改名（远程 move）→ 本地上传作为正式条目
                    val okMove = runCatching { client.move("$root/${action.path}", "$root/${action.loserRename}") }
                        .getOrDefault(false)
                    if (!okMove) return@runCatching false
                    uploadEntry(root, action.path)
                }
                SyncDiff.Winner.REMOTE -> {
                    // 远端较新：本地旧目录改名 → 远端下载作为正式条目
                    val localDir = LocalScanner.resolveEntryDir(libraryRoot, action.path)
                        ?: return@runCatching false
                    val renamed = File(localDir.parentFile, action.loserRename.substringAfterLast('/'))
                    if (!localDir.renameTo(renamed)) return@runCatching false
                    downloadEntry(root, action.path)
                }
            }
        }.getOrDefault(false)
    }

    /** 读取远端状态快照；不存在/解析失败 → 空状态（首次同步） */
    private suspend fun loadState(root: String): Map<String, SyncDiff.StateInfo> {
        val stateUrl = "$root/$statePath"
        return runCatching {
            val tmp = File.createTempFile("misoup-state", ".json")
            try {
                if (!client.download(stateUrl, tmp)) return emptyMap()
                val parsed = SyncStateCodec.parse(tmp.readText()).getOrNull() ?: return emptyMap()
                parsed.entries.mapValues { (_, e) -> SyncDiff.StateInfo(e.mtime, e.size) }
            } finally {
                tmp.delete()
            }
        }.getOrDefault(emptyMap())
    }

    /** 回写远端状态快照（临时文件 → 上传 → 删除远端旧文件由 PUT 覆盖语义保证） */
    private suspend fun writeState(root: String, state: SyncState): Boolean {
        return runCatching {
            val tmp = File.createTempFile("misoup-state", ".json")
            try {
                tmp.writeText(SyncStateCodec.serialize(state))
                client.upload(tmp, "$root/$statePath")
            } finally {
                tmp.delete()
            }
        }.getOrDefault(false)
    }

    companion object {
        /** 远端状态快照文件名（点开头，扫描器忽略） */
        const val DEFAULT_STATE_PATH = ".sync-state.json"
    }
}

/**
 * 远端 PROPFIND 结果 → 条目指纹（纯逻辑，JVM 可测）
 *
 * 条目 = 含 meta.json 的目录；指纹 = (目录内最大文件 mtime, 全部文件 size 之和)。
 * 用文件级指纹而非目录 mtime：多数 WebDAV 服务器目录 mtime 不随内容更新。
 */
object RemoteScanner {

    /** 将递归列取结果分组为条目指纹；仅收录含 meta.json 的条目目录 */
    fun entryFingerprints(listing: List<DavFileInfo>): Map<String, SyncDiff.RemoteInfo> {
        // 文件 → 所属条目相对路径：取前两段 `<subject>/<dirName>`；
        // 条目内深层文件（如 变式/ 子目录）也聚合进条目指纹（目录结构原样镜像）
        val filesByEntry = HashMap<String, MutableList<DavFileInfo>>()
        for (info in listing) {
            if (info.isDirectory) continue
            val parts = info.path.split('/')
            if (parts.size < 3) continue            // 不在科目/条目两级结构内（含根状态文件）
            val entryPath = parts.take(2).joinToString("/")
            filesByEntry.getOrPut(entryPath) { ArrayList() }.add(info)
        }
        return filesByEntry.mapNotNull { (entryPath, files) ->
            val hasMeta = files.any { it.path.substringAfterLast('/') == MisoupConfigConst.META_FILE_NAME }
            if (!hasMeta) return@mapNotNull null
            val maxMtime = files.maxOf { it.mtime }
            val totalSize = files.sumOf { it.size }
            entryPath to SyncDiff.RemoteInfo(entryPath, maxMtime, totalSize)
        }.toMap()
    }
}

/**
 * 本地库扫描 → 条目指纹（纯逻辑，JVM 可测）
 *
 * 枚举来源 = LibraryIndex（「对比本地索引」）；size 来自磁盘扫描（索引不存 size）。
 */
object LocalScanner {

    /** 由索引枚举条目并计算指纹；待修复条目（needsRepair）不参与同步 */
    fun entryFingerprints(index: LibraryIndex, root: File): Map<String, SyncDiff.LocalInfo> {
        val result = HashMap<String, SyncDiff.LocalInfo>()
        for (entry in index.all()) {
            if (entry.needsRepair) continue
            val dir = resolveEntryDir(root, "${entry.subject}/${entry.dirName}") ?: continue
            val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") } ?: emptyList()
            if (files.isEmpty()) continue
            val maxMtime = files.maxOf { it.lastModified() }
            val totalSize = files.sumOf { it.length() }
            val path = "${entry.subject}/${entry.dirName}"
            result[path] = SyncDiff.LocalInfo(path, maxMtime, totalSize)
        }
        return result
    }

    /** 由远程相对路径定位本地条目目录（`<subject>/<dirName>`） */
    fun resolveEntryDir(root: File, relPath: String): File? {
        val parts = relPath.split('/')
        if (parts.size != 2) return null
        val dir = File(File(root, parts[0]), parts[1])
        return if (dir.isDirectory) dir else null
    }

    /** 确保本地条目目录存在（下载目标），返回目录 */
    fun ensureEntryDir(root: File, relPath: String): File? {
        val parts = relPath.split('/')
        if (parts.size != 2) return null
        val dir = File(File(root, parts[0]), parts[1])
        dir.mkdirs()
        return dir
    }
}
