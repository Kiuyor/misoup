package com.misoup.app.recognition.visionllm

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * 生产视觉客户端：Bitmap → PNG base64 数据 URI（模块 05 T02）
 *
 * Bitmap.compress 依赖 Android 运行时，JVM 单测无法覆盖（android.jar stub），
 * 逻辑已由 [VisionLlmClient] 泛型化（测试注入 String 桩），本类真机/模拟器验证。
 */
class BitmapVisionLlmClient(
    okHttp: okhttp3.OkHttpClient,
    cfg: () -> LlmConfig,
) : VisionLlmClient<Bitmap>(okHttp, cfg, ::bitmapToPngDataUrl)

/** Bitmap → data:image/png;base64,...（PNG 无损，避免 JPEG 压缩丢公式细节） */
private fun bitmapToPngDataUrl(bitmap: Bitmap): String {
    val baos = ByteArrayOutputStream()
    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)) { "PNG 编码失败" }
    val base64 = java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
    return VisionLlmRequestFactory.DATA_URL_PREFIX + base64
}
