package com.misoup.app.data.model

import kotlinx.serialization.Serializable

/**
 * 错题元数据（meta.json 唯一事实源）
 *
 * 字段严格按 doc/DETAILED-DESIGN.md §2.2 meta.json Schema (v1) 定稿：
 * schemaVersion / id / subject / questionType / errorReasons / tags / knowledgePoints /
 * note / source / createdAt / updatedAt / images / textFile / ocr / fsrs / reviews
 *
 * 说明：
 * - id 为 UUID 不可变；目录名 = 展示名，可重命名（§2.1 定稿）
 * - subject/questionType 为字符串存储（JSON 存字符串），解析失败给默认值不崩
 * - 新增字段必须带默认值，保证旧文件可读（向前兼容）
 */
@Serializable
data class MisoupMeta(
    val schemaVersion: Int = 1,
    val id: String,
    val subject: String,
    val questionType: String,
    val errorReasons: List<String>,
    val tags: List<String>,
    val knowledgePoints: List<String>,
    val note: String = "",
    val source: String = "",
    val createdAt: String,
    val updatedAt: String,
    val images: Images,
    val textFile: String = MisoupConfigConst.TEXT_FILE_NAME,
    val ocr: OcrState,
    val fsrs: FsrsState,
    val reviews: List<Review> = emptyList()
)

/** 图片文件清单：相对当前错题目录的文件名；cleaned/raw 可选 */
@Serializable
data class Images(
    val original: String,
    val cleaned: String? = null,
    val raw: String? = null
)

/** OCR 识别状态 */
@Serializable
data class OcrState(
    val engine: String = "paddle",
    val status: String = "pending",   // pending / done / failed
    val model: String = "",
    val at: String? = null
)

/** FSRS 复习调度状态（自实现 FSRS-4.5，模块 03 填充算法） */
@Serializable
data class FsrsState(
    val state: Int = 0,               // 0=新卡 1=学习中 2=已掌握 3=重学
    val due: String? = null,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val lastReview: String? = null
)

/** 单次复习记录 */
@Serializable
data class Review(
    val date: String,
    val rating: Int,                  // 0=Again 1=Hard 2=Good 3=Easy
    val stateAfter: Int = 0,
    val intervalDays: Double = 0.0
)

/** meta.json 文件内的常量（独立于 MisoupConfig 便于纯 JVM 测试） */
object MisoupConfigConst {
    const val SCHEMA_VERSION = 1
    const val META_FILE_NAME = "meta.json"
    const val TEXT_FILE_NAME = "文本.md"
    const val ORIGINAL_IMAGE_NAME = "原图.webp"
    const val RAW_IMAGE_NAME = "原图-raw.jpg"
    const val CLEANED_IMAGE_NAME = "去手写.webp"
    const val VARIANT_DIR_NAME = "变式"
    const val DEFAULT_LIBRARY_DIR_NAME = "library"
    /** 原图压缩：WebP 质量 80，长边 2048 封顶 */
    const val IMAGE_QUALITY = 80
    const val IMAGE_MAX_LONG_EDGE = 2048
    /** 缩略图：长边 256px WebP */
    const val THUMB_SIZE = 256
    const val THUMB_QUALITY = 80
    /** 自动命名：yyyyMMdd_HHmmss_摘要前12字 */
    const val NAME_DATE_PATTERN = "yyyyMMdd_HHmmss"
    const val NAME_SUMMARY_LENGTH = 12
}
