package com.misoup.app.sync

import java.io.File
import java.io.IOException

/**
 * 内存 WebDAV fake（模块 09 单测）：以「远程完整路径 → 字节」模拟远端文件系统
 *
 * - [files]：path → RemoteFile（字节 + mtime）；目录由路径前缀隐式存在
 * - 记录调用历史（uploads/downloads/moves/deletes）供断言
 * - 可注入故障：failList/failUpload/failDownload 抛 IOException（断网模拟，验收「断网报错不崩」）
 */
class FakeDavClient : DavClient {

    data class RemoteFile(val bytes: ByteArray, val mtime: Long)

    /** 远端文件表：完整远程 URL 路径 → 内容 */
    val files = LinkedHashMap<String, RemoteFile>()

    /** 目录集合（mkdirs 显式创建） */
    val dirs = LinkedHashSet<String>()

    val uploadCalls = ArrayList<String>()
    val downloadCalls = ArrayList<String>()
    val deleteCalls = ArrayList<String>()
    val moveCalls = ArrayList<Pair<String, String>>()

    var failList = false
    var failUpload = false
    var failDownload = false

    /** 记录远端上传 mtime：回显本地文件 mtime（模拟服务器 Last-Modified 回显），
     *  保证「上传后双端指纹一致」；putFile 模拟另一设备写入，用独立递增时钟 */
    private var clock = System.currentTimeMillis()

    /** 手动放置一个远端条目目录（含 meta.json），返回其相对路径 */
    fun putEntry(subject: String, dirName: String, filesByName: Map<String, String>): String {
        val base = "$subject/$dirName"
        filesByName.forEach { (name, content) ->
            putFile("$base/$name", content)
        }
        return base
    }

    fun putFile(relPath: String, content: String): String {
        val key = "$ROOT/$relPath"
        // 模拟另一设备写入：mtime 必须晚于本地任何文件（时钟推进越过真实时间）
        clock = maxOf(clock, System.currentTimeMillis()) + 1
        files[key] = RemoteFile(content.toByteArray(Charsets.UTF_8), clock)
        return key
    }

    fun fileContent(relPath: String): String? =
        files["$ROOT/$relPath"]?.bytes?.toString(Charsets.UTF_8)

    fun remoteExists(relPath: String): Boolean = files.containsKey("$ROOT/$relPath")

    fun remoteEntryExists(subject: String, dirName: String): Boolean =
        files.keys.any { it.startsWith("$ROOT/$subject/$dirName/") }

    override suspend fun listRecursive(remoteDir: String): List<DavFileInfo> {
        if (failList) throw IOException("模拟断网：列取失败")
        val root = remoteDir.trimEnd('/')
        if (!files.keys.any { it.startsWith("$root/") } && !dirs.any { it.startsWith("$root/") }) {
            return emptyList()
        }
        val result = ArrayList<DavFileInfo>()
        // 目录节点
        val allDirs = dirs.filter { it.startsWith("$root/") || it == root } + files.keys
            .filter { it.startsWith("$root/") }
            .flatMap { path ->
                val rel = path.removePrefix("$root/").trim('/')
                if (rel.isEmpty()) emptyList()
                else (1 until rel.split('/').size).map { n ->
                    "$root/" + rel.split('/').take(n).joinToString("/")
                }
            }
        allDirs.distinct().sorted().forEach { d ->
            if (d != root) {
                result += DavFileInfo(
                    path = d.removePrefix("$root/").trim('/'),
                    isDirectory = true,
                    mtime = 0L,
                    size = 0L
                )
            }
        }
        files.forEach { (path, rf) ->
            if (path.startsWith("$root/")) {
                result += DavFileInfo(
                    path = path.removePrefix("$root/").trim('/'),
                    isDirectory = false,
                    mtime = rf.mtime,
                    size = rf.bytes.size.toLong()
                )
            }
        }
        return result
    }

    override suspend fun upload(localFile: File, remotePath: String): Boolean {
        if (failUpload) throw IOException("模拟断网：上传失败")
        uploadCalls += remotePath
        // 回显本地文件 mtime：WebDAV 服务器 PUT 后 Last-Modified 通常取客户端时间
        files[remotePath] = RemoteFile(localFile.readBytes(), localFile.lastModified())
        dirs += remotePath.substringBeforeLast('/')
        return true
    }

    override suspend fun download(remotePath: String, target: File): Boolean {
        if (failDownload) throw IOException("模拟断网：下载失败")
        downloadCalls += remotePath
        val rf = files[remotePath] ?: return false
        target.parentFile?.mkdirs()
        target.writeBytes(rf.bytes)
        return true
    }

    override suspend fun delete(remotePath: String): Boolean {
        deleteCalls += remotePath
        files.keys.removeAll { it == remotePath || it.startsWith("$remotePath/") }
        dirs.remove(remotePath)
        return true
    }

    override suspend fun mkdirs(remoteDir: String): Boolean {
        dirs += remoteDir
        return true
    }

    override suspend fun move(src: String, dst: String): Boolean {
        moveCalls += src to dst
        val moved = files.keys.filter { it == src || it.startsWith("$src/") }
        moved.forEach { old ->
            val new = dst + old.removePrefix(src)
            files[new] = files.remove(old)!!
        }
        dirs.remove(src)
        dirs += dst
        return true
    }

    override suspend fun exists(remotePath: String): Boolean =
        files.keys.any { it == remotePath || it.startsWith("$remotePath/") } || dirs.contains(remotePath)

    companion object {
        /** 测试用固定远端根（与 DavConfig.syncRoot() 一致） */
        const val ROOT = "https://dav.example.com/dav/misoup-library"
    }
}
