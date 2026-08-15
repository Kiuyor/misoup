package com.misoup.app.review.mask

import com.misoup.app.data.index.LibraryIndex
import java.io.File

/**
 * 遮罩持久化（T05）：<条目目录>/.mask.json
 *
 * 说明：遮罩定位属于 UI 辅助状态，独立于 meta.json（不污染复习数据），
 * 单独小文件原子写（临时文件 + rename），进程杀后重进仍可恢复。
 *
 * 注意：本存储独立于 StoreService 的 Mutex 串行（遮罩写入不经过 meta 事务）；
 * 与复习提交并发写不同文件（.mask.json vs meta.json），无字段冲突。
 */
class MaskStore(
    private val root: File,
    private val index: LibraryIndex
) {
    private fun maskFile(id: String): File? {
        val entry = index.get(id) ?: return null
        if (entry.needsRepair) return null
        val dir = File(File(root, entry.subject), entry.dirName)
        if (!dir.isDirectory) return null
        return File(dir, MASK_FILE_NAME)
    }

    /** 读取自定义遮罩；无文件/解析失败 → null（回退默认整图） */
    fun load(id: String): MaskRect? {
        val f = maskFile(id) ?: return null
        if (!f.isFile) return null
        return MaskJson.parse(runCatching { f.readText() }.getOrNull() ?: return null)
    }

    /** 保存自定义遮罩（原子写）；条目不存在 → false */
    fun save(id: String, rect: MaskRect): Boolean {
        val f = maskFile(id) ?: return false
        val tmp = File(f.parentFile, ".mask-${System.currentTimeMillis()}.tmp")
        return try {
            tmp.writeText(MaskJson.serialize(rect.normalized()))
            atomicReplace(tmp, f)
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    /** 清除自定义遮罩（回到默认整图）；无文件时无操作成功 */
    fun clear(id: String): Boolean {
        val f = maskFile(id) ?: return false
        return f.delete() || !f.exists()
    }

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

    companion object {
        const val MASK_FILE_NAME = ".mask.json"
    }
}
