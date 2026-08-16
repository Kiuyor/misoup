package com.misoup.app.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 同步状态持久化（模块 09 T03）：上次同步快照，存远端 `<根>/.sync-state.json`
 *
 * - 记录每个条目在「上次同步完成时」的 (mtime, size)，用于判定双端谁变过
 * - 容错 Json（同 MetaJson 风格）：未知字段忽略、默认值写入
 * - 状态文件以 . 开头，扫描器与差集引擎天然忽略
 */
@Serializable
data class SyncState(
    val schemaVersion: Int = 1,
    val lastSyncAt: Long = 0L,
    val entries: Map<String, SyncStateEntry> = emptyMap()
)

@Serializable
data class SyncStateEntry(
    val mtime: Long = 0L,
    val size: Long = 0L
)

/** 状态序列化/解析（纯逻辑，JVM 可测） */
object SyncStateCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun serialize(state: SyncState): String = json.encodeToString(state)

    /** 解析；失败返回 failure（调用方按「首次同步 / 无快照」处理） */
    fun parse(text: String): Result<SyncState> = runCatching {
        json.decodeFromString<SyncState>(text)
    }
}
