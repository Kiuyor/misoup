package com.misoup.app.data.store

import java.io.File

/**
 * 缩略图服务：首次访问生成 256px WebP 到 cacheDir/thumbs/<id>.webp
 * （DETAILED-DESIGN §2.3：Coil 按需加载，磁盘缓存 256px 版本，首次访问生成）
 *
 * - 已存在且非空 → 直接复用，不重复编码（用 encoder 调用计数验证）
 * - 编码失败 → 返回 failure，不抛异常
 * - 幂等：并发/重复调用最终都指向同一文件
 */
class ThumbService(
    private val cacheDir: File,
    private val encoder: ImageEncoder
) {
    /** cacheDir/thumbs 目录（懒创建） */
    private val thumbsDir: File get() = File(cacheDir, "thumbs")

    /** 缩略图文件路径 cacheDir/thumbs/<id>.webp */
    fun thumbFile(id: String): File = File(thumbsDir, "$id.webp")

    /** 缩略图是否已生成且非空 */
    fun exists(id: String): Boolean {
        val f = thumbFile(id)
        return f.isFile && f.length() > 0
    }

    /**
     * 确保缩略图存在：缺失时用 [source] 生成。
     * @return 成功 → Result.success(文件)；失败 → Result.failure
     */
    fun ensure(id: String, source: ImageAsset): Result<File> {
        val target = thumbFile(id)
        if (exists(id)) return Result.success(target)
        return runCatching {
            thumbsDir.mkdirs()
            val ok = encoder.encodeThumb(source, target)
            check(ok && target.isFile && target.length() > 0) { "缩略图编码失败: $id" }
            target
        }
    }

    /** 删除缩略图（条目删除时调用） */
    fun delete(id: String) {
        thumbFile(id).delete()
    }
}
