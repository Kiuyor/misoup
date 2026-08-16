package com.misoup.app.sync.backup

import com.misoup.app.data.index.IndexedEntry
import com.misoup.app.data.index.LibraryIndex
import com.misoup.app.data.model.MisoupConfigConst
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份 zip 读写（模块 09 T06/T07/T08）：整库 / 单条目 → zip + manifest.json
 *
 * zip 结构（与本地库镜像）：
 * ```
 * manifest.json
 * <subject>/<dirName>/meta.json
 * <subject>/<dirName>/原图.webp
 * <subject>/<dirName>/文本.md
 * ...
 * ```
 * 条目目录含 meta.json 才算有效条目（同同步层扫描规则）。
 *
 * SAF 保存/选择由 [BackupSink]/[BackupSource] 抽象：生产 = ContentResolver Uri 流，
 * 测试 = 文件流（纯 JVM）。
 */
interface BackupSink {
    /** 打开输出流（SAF createDocument 或本地文件）；返回 null = 取消/失败 */
    fun openOutput(): OutputStream?
}

interface BackupSource {
    /** 打开输入流（SAF openInputStream 或本地文件）；返回 null = 不存在/失败 */
    fun openInput(): InputStream?
}

/** 打包结果：条目数与 id 列表 */
data class BackupResult(
    val entryCount: Int,
    val ids: List<String>
)

/**
 * 备份 zip 生成（T06 整库 / T08 单条目共用）
 *
 * @param libraryRoot 本地库根
 * @param index       内存索引（条目枚举）
 */
class BackupWriter(
    private val libraryRoot: File,
    private val index: LibraryIndex
) {

    /**
     * 打包到 [sink]。
     *
     * @param ids 要打包的条目 id 列表；null = 整库（T06）
     * @param kind manifest 类型：full-backup（T06）/ single-export（T08）
     * @return 打包条目数与 id 列表；空库/无法打开输出 → failure
     */
    fun write(sink: BackupSink, ids: List<String>? = null, kind: String = BackupManifest.KIND_FULL): Result<BackupResult> {
        val entries = resolveEntries(ids)
        if (entries.isEmpty()) {
            return Result.failure(IllegalStateException("没有可备份的条目"))
        }
        return try {
            val out = sink.openOutput() ?: return Result.failure(IllegalStateException("无法打开备份输出"))
            val manifestEntries = ArrayList<ManifestEntry>(entries.size)
            ZipOutputStream(out.buffered()).use { zos ->
                for ((entry, dir) in entries) {
                    val base = "${entry.subject}/${entry.dirName}"
                    EntryHasher.collectFiles(dir).forEach { f ->
                        val rel = f.relativeTo(dir).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry("$base/$rel"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    manifestEntries += ManifestEntry(
                        id = entry.id,
                        subject = entry.subject,
                        dirName = entry.dirName,
                        hash = EntryHasher.hashOf(dir),
                        fileCount = EntryHasher.collectFiles(dir).size
                    )
                }
                val manifest = BackupManifest(
                    kind = kind,
                    createdAt = java.time.Instant.now().toString(),
                    entries = manifestEntries
                )
                zos.putNextEntry(ZipEntry(BackupManifest.FILE_NAME))
                zos.write(BackupManifestCodec.serialize(manifest).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            Result.success(BackupResult(entries.size, entries.map { it.first.id }))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 由 id 列表（null=全部）解析条目目录；待修复条目与目录丢失条目跳过 */
    private fun resolveEntries(ids: List<String>?): List<Pair<IndexedEntry, File>> {
        val all = if (ids == null) index.all() else ids.mapNotNull { index.get(it) }
        return all
            .filter { !it.needsRepair }
            .mapNotNull { e ->
                val dir = File(File(libraryRoot, e.subject), e.dirName)
                if (dir.isDirectory && File(dir, MisoupConfigConst.META_FILE_NAME).isFile) e to dir else null
            }
    }
}

/**
 * zip 读取（T07 恢复 / T08 导入共用）
 */
object BackupReader {

    /** 读取 zip 内 manifest（缺失/解析失败 → failure） */
    fun readManifest(input: InputStream): Result<BackupManifest> = runCatching {
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == BackupManifest.FILE_NAME) {
                    return@use BackupManifestCodec.parse(zis.readBytes().decodeToString()).getOrThrow()
                }
                entry = zis.nextEntry
            }
            throw IllegalStateException("zip 内缺少 ${BackupManifest.FILE_NAME}")
        }
    }

    /**
     * 解包 zip 到 [targetRoot]（保持 `<subject>/<dirName>/...` 结构，跳过 manifest）。
     *
     * @return 「subject/dirName → 落盘目录」映射（供调用方按 manifest 关联）
     * 目录穿越/隐藏路径段（.. 或 . 开头）拒绝写入（安全）。
     */
    fun extract(input: InputStream, targetRoot: File): Map<String, File> {
        val result = LinkedHashMap<String, File>()
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name != BackupManifest.FILE_NAME) {
                    val parts = entry.name.split('/')
                    if (parts.size >= 3 && parts.none { it == ".." || it.startsWith(".") }) {
                        val subject = parts[0]
                        val dirName = parts[1]
                        val dir = File(File(targetRoot, subject), dirName)
                        dir.mkdirs()
                        val target = File(dir, parts.drop(2).joinToString("/"))
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zis.copyTo(it) }
                        result["$subject/$dirName"] = dir
                    }
                }
                entry = zis.nextEntry
            }
        }
        return result
    }
}
