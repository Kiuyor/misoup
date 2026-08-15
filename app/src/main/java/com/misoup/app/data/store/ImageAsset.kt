package com.misoup.app.data.store

import android.graphics.Bitmap
import java.io.File

/** 图片格式 */
enum class ImageFormat { JPEG, WEBP, PNG }

/**
 * 图片资源抽象：StoreService/ThumbService 只依赖宽高与格式，
 * 位图具体实现由 Android 层 [BitmapImageAsset] 提供，便于 JVM 单元测试。
 */
interface ImageAsset {
    val width: Int
    val height: Int
    val format: ImageFormat
}

/**
 * Bitmap 封装（Android 真机路径）。Bitmap 由调用方持有（录入管线解码/矫正产物）。
 * bitmap 对同包编码器公开；JVM 单测用 FakeImageAsset，不触碰本类。
 */
class BitmapImageAsset(
    val bitmap: Bitmap,
    override val format: ImageFormat
) : ImageAsset {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

/**
 * 图片缩放纯逻辑（可单测）：
 * - 原图：长边 ≤2048 封顶（超出按比例缩小，不放大）
 * - 缩略图：长边 = 256（等比缩放，超出才缩，不放大）
 */
object ImageScale {

    /**
     * 计算等比缩放系数：长边超过 maxLongEdge 时缩小，否则 1f（不放大）。
     * 非法输入（<=0）一律返回 1f，避免除零。
     */
    fun scaleForMaxLongEdge(width: Int, height: Int, maxLongEdge: Int): Float {
        if (width <= 0 || height <= 0 || maxLongEdge <= 0) return 1f
        val longEdge = maxOf(width, height)
        return if (longEdge <= maxLongEdge) 1f else maxLongEdge.toFloat() / longEdge
    }

    /** 等比缩放后的目标尺寸（宽高至少 1，避免 createScaledBitmap 崩溃） */
    fun scaledSize(width: Int, height: Int, scale: Float): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    /** 缩略图目标尺寸：长边 = thumbEdge */
    fun thumbSize(width: Int, height: Int, thumbEdge: Int): Pair<Int, Int> {
        val scale = scaleForMaxLongEdge(width, height, thumbEdge)
        return scaledSize(width, height, scale)
    }
}

/**
 * 图片编码器接口：把 [ImageAsset] 编码为 WebP 落盘。
 * 真机实现 [WebpImageEncoder] 走 Bitmap.compress(WebP, quality)；
 * 单测用 FakeEncoder 替换，保证存储逻辑可纯 JVM 验证。
 */
interface ImageEncoder {
    /** 编码原图（长边 2048 封顶，quality 80）到 out；成功返回 true */
    fun encodeOriginal(asset: ImageAsset, out: File): Boolean

    /** 编码缩略图（长边 256）到 out；成功返回 true */
    fun encodeThumb(asset: ImageAsset, out: File): Boolean
}

/**
 * 真机 WebP 编码器。
 * 原图压缩策略（DETAILED-DESIGN §3.2）：WebP 质量 80，长边 ≤2048；
 * 缩略图 256px WebP（cacheDir/thumbs/<id>.webp）。
 */
class WebpImageEncoder(
    private val originalQuality: Int = com.misoup.app.data.model.MisoupConfigConst.IMAGE_QUALITY,
    private val thumbQuality: Int = com.misoup.app.data.model.MisoupConfigConst.THUMB_QUALITY,
    private val maxLongEdge: Int = com.misoup.app.data.model.MisoupConfigConst.IMAGE_MAX_LONG_EDGE,
    private val thumbEdge: Int = com.misoup.app.data.model.MisoupConfigConst.THUMB_SIZE
) : ImageEncoder {

    override fun encodeOriginal(asset: ImageAsset, out: File): Boolean {
        if (asset !is BitmapImageAsset) return false
        val scale = ImageScale.scaleForMaxLongEdge(asset.width, asset.height, maxLongEdge)
        return encode(asset, out, scale, originalQuality)
    }

    override fun encodeThumb(asset: ImageAsset, out: File): Boolean {
        if (asset !is BitmapImageAsset) return false
        val scale = ImageScale.scaleForMaxLongEdge(asset.width, asset.height, thumbEdge)
        return encode(asset, out, scale, thumbQuality)
    }

    private fun encode(asset: BitmapImageAsset, out: File, scale: Float, quality: Int): Boolean {
        val (w, h) = ImageScale.scaledSize(asset.width, asset.height, scale)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(asset.bitmap, w, h, true)
        } else {
            asset.bitmap
        }
        return try {
            out.parentFile?.mkdirs()
            out.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, quality, stream)
            }
        } catch (e: Exception) {
            false
        }
    }
}
