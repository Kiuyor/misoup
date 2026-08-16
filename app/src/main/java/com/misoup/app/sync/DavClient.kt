package com.misoup.app.sync

import java.io.File

/**
 * WebDAV 网络层抽象（模块 09 T02/T03）
 *
 * - 生产实现：[SardineDavClient]（sardine-android）；JVM 单测注入内存 fake
 * - 路径一律为远程完整 URL；[DavFileInfo.path] 为相对所列出目录的路径（目录间用 / 分隔）
 * - 同步内容 = 明文文件（设计如此，数据开放原则，见 module/09-sync-backup.md）
 */
interface DavClient {

    /** 递归列出 [remoteDir] 下全部资源（含子目录），返回相对路径 → 资源信息；列取失败抛 IOException */
    suspend fun listRecursive(remoteDir: String): List<DavFileInfo>

    /** 上传本地文件到 [remotePath]（父目录需已存在）；成功返回 true */
    suspend fun upload(localFile: File, remotePath: String): Boolean

    /** 下载 [remotePath] 到本地 [target]（父目录需已存在）；成功返回 true */
    suspend fun download(remotePath: String, target: File): Boolean

    /** 删除远程资源（文件或目录）；不存在视为成功；成功返回 true */
    suspend fun delete(remotePath: String): Boolean

    /** 递归创建远程目录（已存在视为成功） */
    suspend fun mkdirs(remoteDir: String): Boolean

    /** 远程移动/改名；成功返回 true */
    suspend fun move(src: String, dst: String): Boolean

    /** 远程资源是否存在 */
    suspend fun exists(remotePath: String): Boolean
}

/** 远端资源信息（PROPFIND 结果）：[path] 相对列出根；mtime = 毫秒时间戳；目录 size 无意义填 0 */
data class DavFileInfo(
    val path: String,
    val isDirectory: Boolean,
    val mtime: Long,
    val size: Long
)
