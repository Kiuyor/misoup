package com.misoup.app

import com.misoup.app.data.model.FsrsState
import com.misoup.app.data.model.Images
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.model.OcrState
import com.misoup.app.data.store.ImageAsset
import com.misoup.app.data.store.ImageEncoder
import com.misoup.app.data.store.ImageFormat
import java.io.File
import java.util.UUID

/** 测试用图片资源（避免依赖 android.graphics.Bitmap） */
class FakeImageAsset(
    override val width: Int,
    override val height: Int,
    override val format: ImageFormat = ImageFormat.JPEG
) : ImageAsset

/**
 * 测试用编码器：写标记文件模拟 WebP 编码。
 * 记录调用次数，便于验证缩略图"首访生成、复用不重复编码"。
 */
class FakeImageEncoder(
    var failOriginal: Boolean = false,
    var failThumb: Boolean = false
) : ImageEncoder {
    var originalCalls = 0
    var thumbCalls = 0
    val encodedOriginals = mutableListOf<String>()
    val encodedThumbs = mutableListOf<String>()

    override fun encodeOriginal(asset: ImageAsset, out: File): Boolean {
        originalCalls++
        if (failOriginal) return false
        out.parentFile?.mkdirs()
        out.writeText("webp:${asset.width}x${asset.height}")
        encodedOriginals.add(out.absolutePath)
        return true
    }

    override fun encodeThumb(asset: ImageAsset, out: File): Boolean {
        thumbCalls++
        if (failThumb) return false
        out.parentFile?.mkdirs()
        out.writeText("thumb:${asset.width}x${asset.height}")
        encodedThumbs.add(out.absolutePath)
        return true
    }
}

/** 构造合法 meta（默认值齐全），id 用 UUID（契约：id 不可变） */
fun makeMeta(
    id: String = UUID.randomUUID().toString(),
    subject: String = "数学",
    questionType: String = "解答题",
    errorReasons: List<String> = listOf("概念不清"),
    tags: List<String> = listOf("二次函数"),
    knowledgePoints: List<String> = listOf("二次函数区间最值"),
    note: String = "",
    source: String = "月考",
    createdAt: String = "2026-08-16T14:05:23+08:00",
    updatedAt: String = "2026-08-16T14:05:23+08:00",
    images: Images = Images(original = MisoupConfigConst.ORIGINAL_IMAGE_NAME),
    textFile: String = MisoupConfigConst.TEXT_FILE_NAME,
    ocr: OcrState = OcrState(status = "pending"),
    fsrs: FsrsState = FsrsState(),
    reviews: List<com.misoup.app.data.model.Review> = emptyList()
): MisoupMeta = MisoupMeta(
    id = id,
    subject = subject,
    questionType = questionType,
    errorReasons = errorReasons,
    tags = tags,
    knowledgePoints = knowledgePoints,
    note = note,
    source = source,
    createdAt = createdAt,
    updatedAt = updatedAt,
    images = images,
    textFile = textFile,
    ocr = ocr,
    fsrs = fsrs,
    reviews = reviews
)
