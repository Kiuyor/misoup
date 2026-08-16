package com.misoup.app.ui.screens.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.misoup.app.AppGraph
import com.misoup.app.recognition.paddle.PaddleOcrEngine
import com.misoup.app.recognition.paddle.download.HttpModelDownloadService
import com.misoup.app.recognition.paddle.download.ModelMirror
import com.misoup.app.recognition.paddle.model.PaddleModels
import com.misoup.app.settings.model.ModelStatusFormatter
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 识别模型设置（模块 10 T03）：OCR 引擎状态/版本 + 检查更新 + 下载入口 + 自换模型说明
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionModelScreen(
    onBack: () -> Unit,
    vm: RecognitionModelViewModel = viewModel(factory = recognitionModelViewModelFactory()),
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("识别模型") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("OCR 引擎（PaddleOCR PP-OCRv4 mobile）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.statusText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (state.healthy) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    state.version?.let { v ->
                        Text("模型版本：v$v", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            OutlinedButton(
                onClick = { vm.checkUpdate() },
                enabled = !state.checking,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.checking) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text("检查中…")
                } else {
                    Text("检查更新（国内镜像）")
                }
            }
            state.updateText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Button(
                onClick = { vm.download() },
                enabled = !state.downloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.downloading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text("下载中…")
                } else {
                    Text("下载最新模型")
                }
            }
            state.downloadResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("自换模型说明", style = MaterialTheme.typography.titleSmall)
                    Text(
                        ModelStatusFormatter.SWAP_MODEL_GUIDE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 识别模型设置工厂（模型状态 = 文件盘点，下载 = HttpModelDownloadService） */
@Composable
private fun recognitionModelViewModelFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            val engine = PaddleOcrEngine(context)
            val modelsDir = PaddleModels.modelsDir(context.filesDir)
            val downloader = HttpModelDownloadService()
            val http = OkHttpClient()
            // 版本清单拉取（HttpModelDownloadService 内部私有，此处直接以 OkHttp 拉取）
            val fetchManifest: suspend () -> String? = {
                runCatching {
                    val request = Request.Builder().url(ModelMirror.manifestUrl()).build()
                    http.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) null else resp.body?.string()
                    }
                }.getOrNull()
            }
            RecognitionModelViewModel(
                statusProvider = { engine.health() },
                manifestProvider = fetchManifest,
                downloadAction = { downloader.downloadLatest(modelsDir) },
            )
        }
    }
}
