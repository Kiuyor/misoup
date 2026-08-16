package com.misoup.app.sync.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 备份/导出 manifest（模块 09 T06/T07/T08）：id + hash 校验
 *
 * - 整库备份：entries = 全库条目；单条目导出：entries = 该条目
 * - hash = SHA-256(条目目录内全部文件按名排序后 name+size+content 串接)，
 *   恢复时逐条目重算比对 → 校验通过才落库
 * - 容错 Json（同 MetaJson 风格）：ignoreUnknownKeys + encodeDefaults
 */
@Serializable
data class BackupManifest(
    val schemaVersion: Int = 1,
    val app: String = "misoup",
    val kind: String = KIND_FULL,          // full-backup | single-export
    val createdAt: String = "",
    val entries: List<ManifestEntry> = emptyList()
) {
    companion object {
        const val KIND_FULL = "full-backup"
        const val KIND_SINGLE = "single-export"
        const val FILE_NAME = "manifest.json"
    }
}

/** 单条目 manifest 记录：id 唯一；subject/dirName 供恢复定位；hash 供完整性校验 */
@Serializable
data class ManifestEntry(
    val id: String,
    val subject: String,
    val dirName: String,
    val hash: String,
    val fileCount: Int = 0
)

/** manifest 序列化/解析（纯逻辑，JVM 可测） */
object BackupManifestCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun serialize(manifest: BackupManifest): String = json.encodeToString(manifest)

    fun parse(text: String): Result<BackupManifest> = runCatching {
        json.decodeFromString<BackupManifest>(text)
    }
}

/**
 * 条目 hash 计算与校验（纯逻辑，JVM 可测）
 *
 * hash = SHA-256(逐文件: name + '\n' + size + '\n' + content，按文件名排序)
 * 覆盖条目目录内全部文件（meta.json / 原图 / 文本 / 变式子目录等），忽略隐藏文件。
 */
object EntryHasher {

    fun hashOf(entryDir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        collectFiles(entryDir).forEach { f ->
            digest.update(f.name.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
            digest.update(f.length().toString().toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
            digest.update(f.readBytes())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 条目目录内全部文件（递归，按相对路径排序；忽略隐藏文件与隐藏目录） */
    fun collectFiles(entryDir: File): List<File> {
        if (!entryDir.isDirectory) return emptyList()
        val result = ArrayList<File>()
        fun walk(dir: File) {
            dir.listFiles()?.filter { !it.name.startsWith(".") }?.sortedBy { it.name }?.forEach { f ->
                if (f.isDirectory) walk(f) else result += f
            }
        }
        walk(entryDir)
        return result
    }

    /** 校验：重算 hash 与 manifest 记录一致 */
    fun verify(entryDir: File, expected: ManifestEntry): Boolean =
        hashOf(entryDir) == expected.hash && collectFiles(entryDir).size == expected.fileCount
}
