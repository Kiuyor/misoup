package com.misoup.app.ui.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * T02/T03 拍照页（CameraX ImageCapture）
 *
 * - 单拍（mode=single）：点快门拍 1 张 → 直接入管线
 * - 连拍（mode=burst）：自动连续 N 张（默认 [BurstState.DEFAULT_BURST_COUNT]），
 *   每张独立走管线，拍完统一提交
 * - CAMERA 权限动态申请（拒绝 → 展示提示 + 重新申请按钮）
 * - 拍完回调 [onCaptured]（Uri 列表），由导航层转交进度页
 */
@Composable
fun CameraCaptureScreen(
    mode: String,               // "single" / "burst"
    onBack: () -> Unit,
    onCaptured: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    if (!hasPermission) {
        PermissionPrompt(
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onBack = onBack
        )
        return
    }

    val burstTotal = if (mode == "burst") BurstState.DEFAULT_BURST_COUNT else 1
    var burst by remember { mutableStateOf(BurstState.start(burstTotal)) }
    var error by remember { mutableStateOf<String?>(null) }
    var shutterEnabled by remember { mutableStateOf(true) }

    // 已拍文件（连拍收集；单拍即拍即提交）
    val capturedFiles = remember { mutableListOf<File>() }
    val previewView = remember { mutableStateOf<PreviewView?>(null) }

    // CameraX 绑定：Preview + ImageCapture（生命周期跟随本页面）
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val executor: Executor = ContextCompat.getMainExecutor(context)

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build()
                previewView.value?.let { preview.setSurfaceProvider(it.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            }
        }
        future.addListener(listener, executor)
        onDispose {
            runCatching { future.get().unbindAll() }
            previewView.value = null
        }
    }

    // 连拍自动触发：capturing 且未完成 → 逐张拍，每张间隔 BURST_INTERVAL_MS
    LaunchedEffect(burst.capturing, burst.taken) {
        if (!burst.capturing || burst.done) return@LaunchedEffect
        val file = captureToFile(context, imageCapture, executor)
        if (file != null) {
            capturedFiles.add(file)
            burst = BurstState.next(burst)
            if (!burst.done) delay(BURST_INTERVAL_MS)
        } else {
            error = "拍摄失败，请重试"
            burst = BurstState.abort(burst)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 相机预览
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { view -> previewView.value = view }
            },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (mode == "burst") {
                Text(
                    text = "连拍 ${burst.taken}/${burstTotal}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 快门（单拍：拍一张即提交；连拍：启动/重连续拍）
                Button(
                    onClick = {
                        if (mode == "burst") {
                            burst = burst.copy(capturing = true)
                        } else {
                            scope.launch {
                                shutterEnabled = false
                                val file = captureToFile(context, imageCapture, executor)
                                shutterEnabled = true
                                if (file != null) onCaptured(listOf(file.toUri()))
                                else error = "拍摄失败，请重试"
                            }
                        }
                    },
                    enabled = shutterEnabled && (mode != "burst" || !burst.capturing),
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape
                ) {
                    if (burst.capturing && !burst.done) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp))
                    } else {
                        Text("拍", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Spacer(Modifier.width(16.dp))
                // 连拍完成 → 统一提交
                if (mode == "burst" && burst.done && capturedFiles.isNotEmpty()) {
                    Button(onClick = { onCaptured(capturedFiles.map { it.toUri() }) }) {
                        Text("完成（${capturedFiles.size} 张）")
                    }
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onBack) { Text("返回") }
            }
        }
    }
}

/**
 * 拍一张照落盘（cacheDir/capture_<ts>.jpg）。
 * 挂起而非阻塞：CameraX 回调在 [executor] 上恢复协程。
 * 失败（相机错误/磁盘满）→ null。
 */
private suspend fun captureToFile(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor
): File? = suspendCancellableCoroutine { cont ->
    val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    imageCapture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
            if (cont.isActive) cont.resume(file)
        }

        override fun onError(exception: ImageCaptureException) {
            if (cont.isActive) cont.resume(null)
        }
    })
}

/** 权限拒绝提示页 */
@Composable
private fun PermissionPrompt(onRequest: () -> Unit, onBack: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("需要相机权限才能拍题", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(
                "请在系统弹窗中允许「错题汤」使用相机；拒绝后可在系统设置中重新开启。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onRequest) { Text("申请权限") }
                Button(onClick = onBack) { Text("返回") }
            }
        }
    }
}

/** 连拍间隔（ms）：给预览/存储留出余量 */
private const val BURST_INTERVAL_MS = 600L

/** 相机产物 → Uri（file://，AndroidImageLoader 统一解码） */
private fun File.toUri(): Uri = Uri.fromFile(this)
