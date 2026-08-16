package com.misoup.app.splitting.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.misoup.app.AppGraph
import com.misoup.app.data.store.StoreService
import com.misoup.app.recognition.coordinator.CaptureSource
import com.misoup.app.recognition.paddle.PaddleOcrEngine
import com.misoup.app.splitting.candidates.TextLine
import com.misoup.app.ui.capture.AndroidImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T03/T04 拆题入口页：相册选整页 → 模块 04 det（PaddleOCR）产出行 →
 * [SplitPageViewModel] 候选框 → [SplitPageScreen] 人工确认 → 逐题入库。
 *
 * 数据流（模块 06 文档）：相册选整页 → 拆题页（预览+确认）→ 提交。
 * - OCR 失败（模型缺失/烂图）→ 明确提示，不崩溃（T05：建议手动裁剪）
 * - 识别行坐标经 det 行框直接映射为 [TextLine]（原图坐标系）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitPageEntryScreen(
    onBack: () -> Unit,
    store: StoreService = AppGraph.store!!,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var lines by remember { mutableStateOf<List<TextLine>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                loading = true
                error = null
                ready = false
                val loader = AndroidImageLoader(context)
                val bmp = loader.load(CaptureSource(uri.toString()))
                if (bmp == null) {
                    error = "图片加载失败，请重试"
                } else {
                    bitmap = bmp
                    imageBitmap = bmp.asImageBitmap()
                    // 模块 04 det：整图识别产出行（text+box）→ TextLine 列表
                    val ocrLines = withContext(Dispatchers.Default) {
                        runCatching {
                            PaddleOcrEngine(context).recognize(bmp).lines
                        }.getOrNull().orEmpty()
                    }
                    lines = ocrLines.map { line ->
                        TextLine(
                            y = line.box.top,
                            height = line.box.height,
                            x = line.box.left,
                            width = line.box.width,
                            text = line.text,
                        )
                    }
                    ready = true
                }
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("整页拆题") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (imageBitmap != null && ready) {
                // 进入拆题编辑页（候选框叠加/手势编辑/逐题录入）
                SplitPageScreen(
                    image = imageBitmap!!,
                    vm = remember(bitmap) { SplitPageViewModel(lines, bitmap!!.width) },
                    onBack = onBack,
                    submitter = { boxes ->
                        SplitEntrySubmitter(
                            store = store,
                            // FINDING 3：拆题入库后裁剪图入队识别（识别队列在 CaptureCoordinator 内）
                            onRecognize = { id, bmp -> AppGraph.captureCoordinator?.enqueueRecognition(id, bmp) }
                        ).submitAll(bitmap!!, boxes)
                    },
                )
            } else {
                Button(
                    onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("选择整页试卷图片")
                }
                Spacer(Modifier.size(16.dp))
                if (loading) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(8.dp))
                    Text("识别中…（模块 04 离线 det）", style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Spacer(Modifier.size(16.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = "识别失败可手动裁剪单题后录入（烂图不崩溃）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
