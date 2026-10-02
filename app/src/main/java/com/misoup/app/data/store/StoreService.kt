package com.misoup.app.data.store

import android.util.Log
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.model.MisoupMeta
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

/**
 * StoreService：文件系统存储唯一写入口（协程 Mutex 串行，事务一致）
 *
 * 写流程（DETAILED-DESIGN §2.4）：
 *   临时目录(.tmp-*) → 写文件 → 校验 meta 可解析 → 原子 rename 进库 → 更新索引
 *
 * 事务性保障：
 * - create：全部写临时目录，任一失败 → 清理临时目录，库内无残留；rename 成功才更新索引
 * - update/delete/move：先在条目目录内写 .tmp 文件再原子 rename 覆盖/移动
 * - 目录 rename 为原子操作（同文件系统），临时目录放库根下保证同卷
 */
class StoreService(
    private val root: File,
    private val index: LibraryIndex,
    private val encoder: ImageEncoder,
    private val thumbService: ThumbService? = null
) {
    private val mutex = Mutex()

    companion object {
        /** 日志 TAG（回收区删除失败/回滚失败等 IO 异常排查用） */
        private const val TAG = "StoreService"
    }

    /**
     * 录入错题：meta + 原图 → 落库（图片先落库，识别后台异步，录入永不阻塞）
     *
     * @param meta 需已含 id（UUID，不可变）；subject 为所在科目目录
     * @param originalImage 矫正/压缩前的原始位图（内部按 2048/80 编码为 原图.webp）
     * @param text 题干/答案/解析文本（写 文本.md；可 null，识别后补）
     * @return 成功 → 条目目录名（展示名）；失败 → 原因
     */
    suspend fun create(meta: MisoupMeta, originalImage: ImageAsset, text: String?): Result<String> =
        mutex.withLock {
            try {
                require(meta.id.isNotBlank()) { "id 不能为空" }
                require(meta.subject.isNotBlank()) { "科目不能为空" }
                // 幂等保护：id 已存在 → 失败（同 id 不允许重复）
                check(index.get(meta.id) == null) { "条目已存在: ${meta.id}" }
                // 目录名冲突兜底：连拍/批量同毫秒落库时时间戳命名可能相同，
                // 追加递增序号（-2/-3…）保证每张独立落库，不阻塞录入（模块 01 铁律）
                val dirName = uniqueDirName(displayNameOf(meta, text), meta.subject)
                val subjectDir = File(root, meta.subject)
                val target = File(subjectDir, dirName)

                // 1. 临时目录（库根下 .tmp-*，与目标同卷保证原子 rename）
                val tmp = File(root, ".tmp-${System.currentTimeMillis()}")
                tmp.mkdirs()

                // rename 已成功的标记：其后失败需对 target 回滚（tmp 已被 rename 走）
                var renamedToTarget = false
                try {
                    // 2. 写文件：meta.json / 原图.webp / 文本.md（meta.json 写后 fsync）
                    writeAtomic(File(tmp, MisoupConfigConst.META_FILE_NAME), MetaJson.serialize(meta))
                    val imageName = meta.images.original
                    check(encoder.encodeOriginal(originalImage, File(tmp, imageName))) { "原图编码失败" }
                    if (!text.isNullOrBlank()) {
                        File(tmp, meta.textFile).writeText(text)
                    }

                    // 3. 校验 meta 可解析（写出的文件必须能读回）
                    val written = File(tmp, MisoupConfigConst.META_FILE_NAME).readText()
                    MetaJson.parse(written).getOrThrow()

                    // 4. 原子 rename 进库
                    subjectDir.mkdirs()
                    check(tmp.renameTo(target)) { "落库失败: rename 到 $dirName" }
                    renamedToTarget = true

                    // 5. 更新索引 + 缩略图（首访生成）
                    val thumbPath = thumbService?.let {
                        it.ensure(meta.id, originalImage).getOrNull()?.absolutePath
                    }
                    index.upsert(meta, thumbPath, dirName = dirName)
                    text?.let { index.indexText(meta.id, it) }
                    index.indexTags(meta.id, meta.tags)   // T03：关键词搜索命中标签
                    Result.success(dirName)
                } catch (e: Throwable) {
                    if (renamedToTarget) {
                        // rename 后（索引/缩略图阶段）失败：tmp 已被 rename 走，
                        // 把条目目录移回临时区恢复原状，库内无残留（清单 #9）
                        if (!target.renameTo(tmp)) {
                            Log.w(TAG, "create 回滚失败，条目目录残留: ${target.absolutePath}")
                        }
                    }
                    tmp.deleteRecursively()   // 失败清理，库内无残留
                    Result.failure(e)
                }
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /**
     * 更新条目：transform 读改写 meta.json（原子覆盖）。
     * 要求 transform 保持 id 不变（id 不可变契约）。
     */
    suspend fun update(id: String, transform: (MisoupMeta) -> MisoupMeta): Result<Unit> =
        mutex.withLock {
            try {
                val entry = index.get(id)
                    ?: throw IllegalArgumentException("条目不存在: $id")
                val entryDir = resolveEntryDir(entry.subject, entry.dirName)
                    ?: throw IllegalStateException("条目目录丢失: ${entry.dirName}")

                val current = readMeta(entryDir)
                    ?: throw IllegalStateException("meta.json 不可读: ${entry.dirName}")
                val updated = transform(current)
                check(updated.id == id) { "update 不允许修改 id" }
                // 校验新 meta 可解析（写前校验）
                MetaJson.parse(MetaJson.serialize(updated)).getOrThrow()

                // 条目目录内写 .tmp 文件 → 原子替换 meta.json
                // 注：Windows 上 File.renameTo 目标已存在会失败，用 Files.move(REPLACE_EXISTING)；
                // 优先 ATOMIC_MOVE（同卷），不支持时降级普通 REPLACE_EXISTING
                val tmpMeta = File(entryDir, ".meta-${System.currentTimeMillis()}.tmp")
                try {
                    writeAtomic(tmpMeta, MetaJson.serialize(updated))
                    atomicReplace(tmpMeta, File(entryDir, MisoupConfigConst.META_FILE_NAME))
                } catch (e: Throwable) {
                    tmpMeta.delete()
                    throw e
                }
                index.upsert(updated, entry.thumbPath)
                // upsert 已清倒排 → 按磁盘最新内容重建（题干 + 标签，T03）
                reindexEntry(entryDir, id, updated)
                Result.success(Unit)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** 删除条目：先改名到回收区（原子），再递归删除；索引最后更新 */
    suspend fun delete(id: String): Result<Unit> =
        mutex.withLock {
            try {
                val entry = index.get(id)
                    ?: throw IllegalArgumentException("条目不存在: $id")
                val entryDir = resolveEntryDir(entry.subject, entry.dirName)
                    ?: throw IllegalStateException("条目目录丢失: ${entry.dirName}")

                val trash = File(root, ".trash-${System.currentTimeMillis()}-${entry.dirName}")
                check(entryDir.renameTo(trash)) { "移入回收区失败" }
                // 回收区删除失败不中断流程（条目已移出库，索引照常更新）；仅记录路径供排查
                val ok = trash.deleteRecursively()
                if (!ok) {
                    Log.w(TAG, "回收区删除失败，目录残留: ${trash.absolutePath}")
                }
                index.remove(id)
                thumbService?.delete(id)
                Result.success(Unit)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** 移动条目到新科目（meta.subject 同步更新，目录原子 rename） */
    suspend fun move(id: String, newSubject: String): Result<Unit> =
        mutex.withLock {
            try {
                require(newSubject.isNotBlank()) { "科目不能为空" }
                val entry = index.get(id)
                    ?: throw IllegalArgumentException("条目不存在: $id")
                if (entry.subject == newSubject) {
                    return@withLock Result.success(Unit)   // 同科目无操作
                }
                val entryDir = resolveEntryDir(entry.subject, entry.dirName)
                    ?: throw IllegalStateException("条目目录丢失: ${entry.dirName}")

                val targetSubjectDir = File(root, newSubject)
                val target = File(targetSubjectDir, entry.dirName)
                check(!target.exists()) { "目标科目已存在同名目录" }

                // 先原子移动目录，再更新 meta.subject
                targetSubjectDir.mkdirs()
                check(entryDir.renameTo(target)) { "移动失败" }

                // 更新 meta.json 的 subject 字段（原子替换）
                val current = readMeta(target)
                if (current != null && current.subject != newSubject) {
                    val updated = current.copy(subject = newSubject)
                    val tmpMeta = File(target, ".meta-${System.currentTimeMillis()}.tmp")
                    try {
                        writeAtomic(tmpMeta, MetaJson.serialize(updated))
                        atomicReplace(tmpMeta, File(target, MisoupConfigConst.META_FILE_NAME))
                    } catch (e: Throwable) {
                        tmpMeta.delete()
                        throw e   // 目录已移动但 meta 未同步：向上报错，由调用方决定
                    }
                }
                // 用磁盘最新 meta 重建索引条目（subject 已同步）
                val movedMeta = readMeta(target)
                    ?: throw IllegalStateException("移动后 meta.json 不可读")
                index.upsert(movedMeta, entry.thumbPath)
                reindexEntry(target, id, movedMeta)   // upsert 清倒排后重建
                Result.success(Unit)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    // ---------- 模块 02 追加：读取 / 文本更新 / 重命名 ----------

    /** 读取条目 meta（详情/编辑页用）；条目不存在或 meta 不可读 → failure */
    suspend fun readMetaById(id: String): Result<MisoupMeta> = mutex.withLock {
        runCatching {
            val entry = index.get(id) ?: throw IllegalArgumentException("条目不存在: $id")
            val dir = resolveEntryDir(entry.subject, entry.dirName)
                ?: throw IllegalStateException("条目目录丢失: ${entry.dirName}")
            readMeta(dir) ?: throw IllegalStateException("meta.json 不可读: ${entry.dirName}")
        }
    }

    /** 读取条目 文本.md（详情/编辑页用）；无文本文件按空串处理 */
    suspend fun readTextById(id: String): Result<String> = mutex.withLock {
        runCatching {
            val entry = index.get(id) ?: throw IllegalArgumentException("条目不存在: $id")
            val dir = resolveEntryDir(entry.subject, entry.dirName)
                ?: throw IllegalStateException("条目目录丢失: ${entry.dirName}")
            val f = File(dir, entry.textFile)
            if (!f.isFile) return@runCatching ""
            f.readText()
        }
    }

    /**
     * 解析条目原图绝对路径（详情页 Coil 大图用）。
     * 优先 images.original；无原图文件返回 null（UI 显示占位）。
     */
    suspend fun imagePathById(id: String): Result<String?> = mutex.withLock {
        runCatching {
            val entry = index.get(id) ?: return@runCatching null
            val dir = resolveEntryDir(entry.subject, entry.dirName) ?: return@runCatching null
            val meta = readMeta(dir) ?: return@runCatching null
            val f = File(dir, meta.images.original)
            if (f.isFile) f.absolutePath else null
        }
    }

    /**
     * 更新 文本.md（编辑页题干/答案/解析保存）：原子覆盖 + 倒排刷新 + mtime 刷新。
     * 文本更新后列表按最近编辑排序（touch）。
     */
    suspend fun updateText(id: String, text: String): Result<Unit> = mutex.withLock {
        try {
            val entry = index.get(id) ?: throw IllegalArgumentException("条目不存在: $id")
            val entryDir = resolveEntryDir(entry.subject, entry.dirName)
                ?: throw IllegalStateException("条目目录丢失: ${entry.dirName}")
            val tmp = File(entryDir, ".text-${System.currentTimeMillis()}.tmp")
            try {
                tmp.writeText(text)
                atomicReplace(tmp, File(entryDir, entry.textFile))
            } catch (e: Throwable) {
                tmp.delete()
                throw e
            }
            // 倒排刷新（题干全文 + 标签搜索命中新文本；空白文本清空旧倒排）
            index.indexText(id, text)
            readMeta(entryDir)?.let { index.indexTags(id, it.tags) }
            index.touch(id)
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * 重命名目录（T09）：目录名 = 展示名可改，meta.id（UUID）不变。
     * 原子目录 rename + 索引以新目录名重建；同名 → 无操作成功。
     */
    suspend fun rename(id: String, newDirName: String): Result<Unit> = mutex.withLock {
        try {
            require(newDirName.isNotBlank()) { "目录名不能为空" }
            val entry = index.get(id) ?: throw IllegalArgumentException("条目不存在: $id")
            if (entry.dirName == newDirName) return@withLock Result.success(Unit)
            val entryDir = resolveEntryDir(entry.subject, entry.dirName)
                ?: throw IllegalStateException("条目目录丢失: ${entry.dirName}")
            val target = File(File(root, entry.subject), newDirName)
            check(!target.exists()) { "目标目录已存在: $newDirName" }
            check(entryDir.renameTo(target)) { "重命名失败" }
            val current = readMeta(target) ?: throw IllegalStateException("重命名后 meta.json 不可读")
            index.upsert(current, entry.thumbPath, dirName = newDirName)
            reindexEntry(target, id, current)   // upsert 清倒排后重建
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    // ---------- 模块 07 追加：变式题文件（<条目目录>/变式/yyyyMMdd_HHmmss_变式N.md） ----------

    /**
     * 保存一批变式 .md 文件（模块 07 T02）：写 <条目目录>/变式/<文件名>，原子落盘。
     * 文件名由调用方生成（时间戳命名，同批不撞）；已存在同名 → 失败（防覆盖）。
     */
    suspend fun saveVariantFiles(id: String, files: Map<String, String>): Result<Unit> =
        mutex.withLock {
            try {
                require(files.isNotEmpty()) { "变式文件列表为空" }
                val entry = index.get(id)
                    ?: throw IllegalArgumentException("条目不存在: $id")
                val entryDir = resolveEntryDir(entry.subject, entry.dirName)
                    ?: throw IllegalStateException("条目目录丢失: ${entry.dirName}")
                val variantDir = File(entryDir, MisoupConfigConst.VARIANT_DIR_NAME)
                variantDir.mkdirs()
                files.forEach { (name, content) ->
                    require(name.endsWith(".md")) { "变式文件名必须以 .md 结尾: $name" }
                    val target = File(variantDir, name)
                    check(!target.exists()) { "变式文件已存在: $name" }
                    val tmp = File(variantDir, ".v-${System.currentTimeMillis()}-$name")
                    try {
                        tmp.writeText(content)
                        atomicReplace(tmp, target)
                    } catch (e: Throwable) {
                        tmp.delete()
                        throw e
                    }
                }
                Result.success(Unit)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

    /** 列出条目已保存的变式文件名（名称倒序 = 最新在前）；无变式目录 → 空列表 */
    suspend fun listVariantFiles(id: String): Result<List<String>> = mutex.withLock {
        runCatching {
            val entry = index.get(id)
                ?: throw IllegalArgumentException("条目不存在: $id")
            val dir = resolveEntryDir(entry.subject, entry.dirName)
                ?: return@runCatching emptyList()
            val variantDir = File(dir, MisoupConfigConst.VARIANT_DIR_NAME)
            if (!variantDir.isDirectory) return@runCatching emptyList()
            variantDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") }
                ?.map { it.name }
                ?.sortedDescending()
                ?: emptyList()
        }
    }

    /** 读取一个变式文件内容（列表「展开答案」用）；文件不存在 → failure */
    suspend fun readVariantFile(id: String, fileName: String): Result<String> = mutex.withLock {
        runCatching {
            require(fileName.endsWith(".md")) { "变式文件名必须以 .md 结尾" }
            val entry = index.get(id)
                ?: throw IllegalArgumentException("条目不存在: $id")
            val dir = resolveEntryDir(entry.subject, entry.dirName)
                ?: throw IllegalStateException("条目目录丢失: ${entry.dirName}")
            val f = File(File(dir, MisoupConfigConst.VARIANT_DIR_NAME), fileName)
            if (!f.isFile) throw IllegalArgumentException("变式文件不存在: $fileName")
            f.readText()
        }
    }

    // ---------- 内部工具 ----------

    /**
     * 关键写点（meta.json）落盘：写入 + fd.sync() 强制刷到存储，防断电/进程被杀后
     * rename 已完成而文件内容未落盘（目录与内容不一致）。rename 仍由调用方执行。
     */
    private fun writeAtomic(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }
    }

    /** 条目文本 + 标签重建倒排（upsert 清 token 后调用；文本缺失按空处理） */
    private fun reindexEntry(entryDir: File, id: String, meta: MisoupMeta) {
        val text = runCatching { File(entryDir, meta.textFile).readText() }.getOrNull() ?: ""
        index.indexText(id, text)
        index.indexTags(id, meta.tags)
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

    /** 由索引条目定位磁盘目录（科目目录名可能被用户改名，双保险：先精确再扫描） */
    private fun resolveEntryDir(subject: String, dirName: String): File? {
        val direct = File(File(root, subject), dirName)
        if (direct.isDirectory) return direct
        // 索引陈旧时按目录名全库扫描（目录名=展示名，可重命名后索引失效）
        root.listFiles()?.forEach { s ->
            if (s.isDirectory) {
                val d = File(s, dirName)
                if (d.isDirectory) return d
            }
        }
        return null
    }

    private fun readMeta(entryDir: File): MisoupMeta? {
        val f = File(entryDir, MisoupConfigConst.META_FILE_NAME)
        if (!f.isFile) return null
        return MetaJson.parse(runCatching { f.readText() }.getOrNull() ?: return null).getOrNull()
    }

    /** 目录展示名：自动命名 yyyyMMdd_HHmmss_<题干前12字>；无文本时用 note */
    private fun displayNameOf(meta: MisoupMeta, text: String?): String {
        val summarySource = text?.takeIf { it.isNotBlank() } ?: meta.note
        return NameGenerator.generate(System.currentTimeMillis(), summarySource)
    }

    /** 目录名唯一化：目标已存在则追加 -2/-3…（同毫秒连拍/批量不阻塞） */
    private fun uniqueDirName(base: String, subject: String): String {
        if (!File(File(root, subject), base).exists()) return base
        var n = 2
        while (File(File(root, subject), "$base-$n").exists()) n++
        return "$base-$n"
    }
}
