package com.misoup.app.sync.webdav

import com.misoup.app.sync.DavClient
import com.misoup.app.sync.DavFileInfo
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import java.io.File
import java.io.IOException

/**
 * 生产 WebDAV 客户端（模块 09 T02/T03）：sardine-android 适配 [DavClient]
 *
 * - 构造即用 [OkHttpSardine] + Basic 凭据；⚠️ 依赖 Android/OkHttp 运行时，
 *   JVM 单测不实例化本类（注入内存 fake 覆盖 DavSyncService 纯逻辑）
 * - 联调 ⏸ 待人工前置：需要坚果云测试账号（doc/tasks/09-sync-backup.md §前置③）
 *
 * 路径约定：[DavClient] 的 remotePath 为完整 URL；listRecursive 返回相对列出根的路径。
 */
class SardineDavClient(
    private val config: () -> DavConfig
) : DavClient {

    private val sardine: Sardine by lazy {
        val c = config()
        OkHttpSardine().apply {
            setCredentials(c.username, c.password)
        }
    }

    override suspend fun listRecursive(remoteDir: String): List<DavFileInfo> {
        val root = remoteDir.trimEnd('/')
        if (!exists(remoteDir)) return emptyList()
        return collectRecursive(root, root)
    }

    override suspend fun upload(localFile: File, remotePath: String): Boolean {
        sardine.put(remotePath, localFile, CONTENT_TYPE_OCTET)
        return true
    }

    override suspend fun download(remotePath: String, target: File): Boolean {
        // 原子下载：先写同目录临时文件（点开头，扫描器/同步器忽略），成功后原子替换目标。
        // 中断/失败时目标文件保持原状，杜绝半写文件污染条目（F1）。
        val tmp = File(target.parentFile, ".${target.name}.d-${System.currentTimeMillis()}.tmp")
        try {
            target.parentFile?.mkdirs()
            sardine.get(remotePath).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            atomicReplace(tmp, target)
            return true
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** 原子替换文件：优先 ATOMIC_MOVE（同卷），不支持则 REPLACE_EXISTING 兜底 */
    private fun atomicReplace(source: File, target: File) {
        val src = source.toPath()
        val dst = target.toPath()
        try {
            java.nio.file.Files.move(
                src, dst,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override suspend fun delete(remotePath: String): Boolean {
        if (!exists(remotePath)) return true
        sardine.delete(remotePath)
        return true
    }

    override suspend fun mkdirs(remoteDir: String): Boolean {
        val parts = remoteDir.trimEnd('/').split('/')
        var cur = ""
        for (part in parts) {
            cur = if (cur.isEmpty()) part else "$cur/$part"
            if (!exists(cur)) sardine.createDirectory(cur)
        }
        return true
    }

    override suspend fun move(src: String, dst: String): Boolean {
        sardine.move(src, dst)
        return true
    }

    override suspend fun exists(remotePath: String): Boolean = sardine.exists(remotePath)

    /** 递归列取：depth=1 逐层展开（sardine 的 list(path) 仅列直接子级） */
    private suspend fun collectRecursive(root: String, current: String): List<DavFileInfo> {
        val result = ArrayList<DavFileInfo>()
        val resources = sardine.list(current)
        for (res in resources) {
            if (res.path == current || res.path.trimEnd('/') == current.trimEnd('/')) continue
            val rel = relativize(root, res.path)
            val info = DavFileInfo(
                path = rel,
                isDirectory = res.isDirectory,
                mtime = res.modifiedTime,
                size = res.contentLength ?: 0L
            )
            result += info
            if (res.isDirectory) {
                result += collectRecursive(root, res.path)
            }
        }
        return result
    }

    /** 计算资源路径相对列出根的路径（去首尾斜杠；根自身 → 空串） */
    private fun relativize(root: String, resourcePath: String): String {
        val r = root.trim('/').trimEnd('/')
        val p = resourcePath.trim('/').trimEnd('/')
        if (p == r) return ""
        return if (p.startsWith("$r/")) p.removePrefix("$r/") else p.substringAfterLast('/')
    }

    /** DavResource 扩展：getModified 可能为 null（部分服务器）→ 兜底 0 */
    private val DavResource.modifiedTime: Long
        get() = getModified()?.time ?: 0L

    companion object {
        private const val CONTENT_TYPE_OCTET = "application/octet-stream"
    }
}
