package com.misoup.app.ui.screens.capture

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * T01 相册选图（Photo Picker）
 *
 * 依据模块 01 文档：相册导入用 `ActivityResultContracts.PickMultipleVisualMedia`，
 * 免存储权限、模拟器可测，是**开发主路径**。
 *
 * - 单张（[launchSingle]）＝单题录入：PickMultipleVisualMedia(maxItems = 1)
 * - 多张（[launchBatch]）＝批量录入：PickMultipleVisualMedia(maxItems = [MAX_BATCH_ITEMS])
 *
 * 数量上限是构建协约时的固定参数，无法随每次 launch 变化，因此内部注册两个 launcher。
 * 系统 Photo Picker 不授予持久读取权限：回调里拿到的是临时授权 Uri，
 * 需立即解码（本模块经 BitmapLoader 立即加载，见 CaptureCoordinator）。
 *
 * @param onPicked 用户选定图片的回调（空选＝用户取消，不回调）
 */
class PhotoPicker(
    /** 单张选图（单题录入） */
    val launchSingle: () -> Unit,
    /** 多张连选（批量录入，最多 [MAX_BATCH_ITEMS] 张） */
    val launchBatch: () -> Unit,
) {
    companion object {
        /** 批量选图上限（连拍上限同值，见 CameraCaptureScreen） */
        const val MAX_BATCH_ITEMS = 30
    }
}

/**
 * 创建并记住 [PhotoPicker]。
 * 内部持有两个 ActivityResult launcher（单张/批量），Compose 生命周期内自动管理回调。
 */
@Composable
fun rememberPhotoPicker(onPicked: (List<Uri>) -> Unit): PhotoPicker {
    // 单张：maxItems = 1 → 单题录入
    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 1)
    ) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    // 批量：maxItems = MAX_BATCH_ITEMS → 批量录入
    val batchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = PhotoPicker.MAX_BATCH_ITEMS)
    ) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    return remember(singleLauncher, batchLauncher) {
        PhotoPicker(
            launchSingle = {
                singleLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            launchBatch = {
                batchLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
        )
    }
}
