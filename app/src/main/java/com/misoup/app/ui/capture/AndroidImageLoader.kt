package com.misoup.app.ui.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.misoup.app.recognition.coordinator.CaptureImageLoader
import com.misoup.app.recognition.coordinator.CaptureSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真机图片加载器（T08 生产实现）
 *
 * - 相册 content:// 与相机 file:// 统一走 [ImageDecoder]（minSdk 26 可用）
 * - ImageDecoder 自动处理 EXIF 旋转（模块 01 文档：相册用 ImageDecoder 自动旋转）
 * - SOFTWARE 分配器：后续 OpenCV 处理需要可读像素（硬件位图不可读）
 * - 失败（文件被删/格式损坏/超限）→ null，调用方按收录失败降级
 */
class AndroidImageLoader(
    private val context: Context
) : CaptureImageLoader<Bitmap> {

    override suspend fun load(source: CaptureSource): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(source.uri)
            val resolver = context.contentResolver
            val decoderSource = ImageDecoder.createSource(resolver, uri)
            ImageDecoder.decodeBitmap(decoderSource) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
            }
        }.getOrNull()
    }
}
