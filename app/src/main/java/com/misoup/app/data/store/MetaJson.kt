package com.misoup.app.data.store

import com.misoup.app.data.model.MisoupMeta
import kotlinx.serialization.json.Json

/**
 * meta.json 序列化/反序列化（唯一入口）
 *
 * - ignoreUnknownKeys=true：旧文件含未知字段不崩（向前兼容）
 * - encodeDefaults=true：默认值也写入文件，保证文件自洽可读
 */
object MetaJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** 序列化为 JSON 字符串 */
    fun serialize(meta: MisoupMeta): String = json.encodeToString(MisoupMeta.serializer(), meta)

    /** 解析；失败返回 failure（调用方按"待修复"处理） */
    fun parse(text: String): Result<MisoupMeta> = runCatching {
        json.decodeFromString(MisoupMeta.serializer(), text)
    }
}
