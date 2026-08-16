package com.misoup.app.splitting.ui

import android.graphics.Bitmap
import com.misoup.app.data.model.FsrsState
import com.misoup.app.data.model.Images
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.model.OcrState
import com.misoup.app.data.store.BitmapImageAsset
import com.misoup.app.data.store.ImageFormat
import com.misoup.app.data.store.StoreService
import com.misoup.app.pipeline.PreprocessPipeline
import com.misoup.app.splitting.candidates.BoundingBox
import com.misoup.app.splitting.candidates.CropCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * T04 逐题入库：确认后每框 → 裁剪 → 模块 01 预处理 → StoreService.create。
 *
 * 模块 06 数据流约定：
 * - 拆题不直接改原图；每题 = 新错题条目
 * - 来源 tag 自动带「整页拆题」（meta.source + tags 同时写入，检索/展示双通道）
 * - 单框失败不阻塞整批（降级：计入 failed 继续下一框，同模块 01 录入铁律）
 *
 * 裁剪坐标钳制走 [CropCalculator]（纯逻辑，JVM 已测）；本类仅 Android 侧组装。
 */
class SplitEntrySubmitter(
    private val store: StoreService,
    private val subject: String = SplitDefaults.DEFAULT_SUBJECT,
) {

    /**
     * 逐框入库。
     * @param pageBitmap 整页原图
     * @param boxes 确认后的候选框（原图坐标）
     * @param onProgress 每框完成回调（okCount, failedCount, 当前下标, 总数）
     * @return 汇总：ok = 成功入库数，failed = 失败框下标列表
     */
    suspend fun submitAll(
        pageBitmap: Bitmap,
        boxes: List<BoundingBox>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SplitSubmitSummary = withContext(Dispatchers.Default) {
        val failed = mutableListOf<Int>()
        var ok = 0
        boxes.forEachIndexed { index, box ->
            val success = submitOne(pageBitmap, box)
            if (success) ok++ else failed += index
            onProgress(ok + failed.size, boxes.size)
        }
        SplitSubmitSummary(ok = ok, failed = failed)
    }

    /** 单框：裁剪 → 预处理 → 落库；失败返回 false（不抛出，批内降级） */
    private suspend fun submitOne(pageBitmap: Bitmap, box: BoundingBox): Boolean = runCatching {
        val crop = CropCalculator.clamp(box, pageBitmap.width, pageBitmap.height)
            ?: return false
        val cropped = Bitmap.createBitmap(
            pageBitmap,
            crop.x, crop.y, crop.width, crop.height,
        )
        // 模块 01 预处理链（矫正→画质→压缩；内部逐步降级，永不抛出）
        val result = PreprocessPipeline.run(cropped)
        val meta = buildMeta()
        val stored = store.create(meta, BitmapImageAsset(result.enhanced, ImageFormat.WEBP), text = null)
        stored.isSuccess
    }.getOrDefault(false)

    /** meta：source 与 tags 均带「整页拆题」；ocr=pending 与录入流程一致 */
    private fun buildMeta(): MisoupMeta {
        val now = com.misoup.app.review.fsrs.FsrsTime.toIso(System.currentTimeMillis()) ?: ""
        return MisoupMeta(
            id = UUID.randomUUID().toString(),
            subject = subject,
            questionType = "其他",
            errorReasons = emptyList(),
            tags = listOf(SplitDefaults.SOURCE_TAG),
            knowledgePoints = emptyList(),
            note = "",
            source = SplitDefaults.SOURCE_TAG,
            createdAt = now,
            updatedAt = now,
            images = Images(original = MisoupConfigConst.ORIGINAL_IMAGE_NAME),
            textFile = MisoupConfigConst.TEXT_FILE_NAME,
            ocr = OcrState(engine = "paddle", status = "pending"),
            fsrs = FsrsState(),
            reviews = emptyList(),
        )
    }
}

/** 逐题入库汇总 */
data class SplitSubmitSummary(
    val ok: Int,
    val failed: List<Int>,
) {
    val allSucceeded: Boolean get() = failed.isEmpty()
}

/** 模块 06 默认值（文档未定稿处裁决） */
object SplitDefaults {
    /** 来源 tag（模块 06 数据流：每题自动带「整页拆题」） */
    const val SOURCE_TAG = "整页拆题"
    /** 默认科目：拆题页未选科目时入「未分类」（同模块 01 CaptureDefaults 裁决），编辑页可改 */
    const val DEFAULT_SUBJECT = "未分类"
}
