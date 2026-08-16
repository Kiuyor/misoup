package com.misoup.app.settings.data

import java.io.File

/**
 * 缓存清理纯逻辑（模块 10 T05）
 *
 * 设置页「数据 → 清理缓存」：缩略图缓存（cacheDir/thumbs）+ 识别缓存。
 * 清理 = 删除目标目录内容并重建目录（目录本身保留，避免影响运行中的服务）；
 * 返回统计供 UI 展示。
 */
data class CacheCleanResult(
    /** 成功清理的目录数 */
    val dirsCleaned: Int,
    /** 删除的文件数 */
    val filesDeleted: Int,
    /** 释放字节数 */
    val bytesFreed: Long,
)

object CacheCleanerLogic {

    /**
     * 清理一批缓存目录（幂等；目录不存在自动跳过）。
     * 删除目录内全部文件后重建目录（保持目录句柄有效）。
     */
    fun clean(dirs: List<File>): CacheCleanResult {
        var files = 0L
        var bytes = 0L
        var cleaned = 0
        for (d in dirs) {
            if (!d.isDirectory) continue
            d.walkTopDown().filter { it.isFile }.forEach {
                files++
                bytes += it.length()
            }
            if (d.deleteRecursively()) {
                d.mkdirs()   // 重建空目录
                cleaned++
            }
        }
        return CacheCleanResult(cleaned, files.toInt(), bytes)
    }

    /** 字节数 → 人类可读（B / KB / MB，1 位小数） */
    fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.0f".format(bytes / 1024.0)} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}
