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

    /**
     * 凭据变更时刷新（C 修复）：sardine 实例 lazy 单例，配置保存后若不刷新会一直用旧凭据
     * 直到杀进程。按 (username, password) 哈希检测变更，仅在变化时重新 setCredentials。
     */
    private var lastCredentialHash = 0

    private fun refreshCredentialsIfChanged() {
        val c = config()
        val h = c.username.hashCode() * 31 + c.password.hashCode()
        if (h != lastCredentialHash) {
            sardine.setCredentials(c.username, c.password)
            lastCredentialHash = h
        }
    }

    override suspend fun listRecursive(remoteDir: String): List<DavFileInfo> {
        refreshCredentialsIfChanged()
        val root = remoteDir.trimEnd('/')
        if (!exists(remoteDir)) return emptyList()
        return collectRecursive(root, root)
    }

    override suspend fun upload(localFile: File, remotePath: String): Boolean {
        refreshCredentialsIfChanged()
        sardine.put(remotePath, localFile, CONTENT_TYPE_OCTET)
        return true
    }

    override suspend fun download(remotePath: String, target: File): Boolean {
        refreshCredentialsIfChanged()
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

    /** 远端资源最后修改时间（毫秒）；PROPFIND 未返回或失败 → null */
    override suspend fun lastModified(remotePath: String): Long? {
        refreshCredentialsIfChanged()
        return runCatching { sardine.list(remotePath).firstOrNull()?.getModified()?.time }.getOrNull()
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
        refreshCredentialsIfChanged()
        if (!exists(remotePath)) return true
        sardine.delete(remotePath)
        return true
    }

    /**
     * 递归创建远程目录（已存在视为成功）。
     *
     * A 修复：只迭代 URL 的**路径部分**（scheme://host 整体作为 base）。
     * 旧实现对完整 URL split('/') 逐段创建，首段 "https:" 被当目录名，
     * OkHttp 解析 `https:` 直接抛 IllegalStateException → 上传 100% 失败。
     */
    override suspend fun mkdirs(remoteDir: String): Boolean {
        refreshCredentialsIfChanged()
        val trimmed = remoteDir.trimEnd('/')
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return false
        val hostEnd = trimmed.indexOf('/', schemeEnd + 3)
        if (hostEnd < 0) return true                  // 仅 scheme://host → 根自身
        val base = trimmed.substring(0, hostEnd)       // scheme://host[:port]
        val path = trimmed.substring(hostEnd + 1)
        var cur = base
        for (part in path.split('/')) {
            if (part.isEmpty()) continue
            cur = "$cur/$part"
            if (!exists(cur)) sardine.createDirectory(cur)
        }
        return true
    }

    override suspend fun move(src: String, dst: String): Boolean {
        refreshCredentialsIfChanged()
        sardine.move(src, dst)
        return true
    }

    override suspend fun exists(remotePath: String): Boolean {
        refreshCredentialsIfChanged()
        return sardine.exists(remotePath)
    }

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
