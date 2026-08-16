package com.misoup.app.ui.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.misoup.app.AppGraph
import com.misoup.app.recognition.coordinator.StorePhase
import com.misoup.app.ui.sync.PrefsAutoSyncStore

/**
 * T10 全屏采集进度页
 *
 * 顶部：汇总条（"已收录 3/5"，来自 CaptureProgress）
 * 列表：逐张缩略图 + 状态徽标（收录中/收录失败/识别中/已收录/识别失败[重试]）
 * 完成后：留在本页可重试失败项，或返回首页（错题库立即可见新题）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureProgressScreen(
    onBack: () -> Unit,
    vm: CaptureViewModel = viewModel()
) {
    val progress by vm.progress.collectAsState()
    val batch by vm.batch.collectAsState()
    val recognition by vm.recognitionStatus.collectAsState()

    // 进入页面时消费会话桥中的待提交批次（拍照/选图产物）
    LaunchedEffect(Unit) {
        vm.submitPending()
    }

    // F2：录入批次全部落库后，若开启「录入后自动同步」则后台自动增量同步（默认关，静默失败不打扰）
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(batch) {
        val allStored = batch.isNotEmpty() && batch.all { it.storePhase != StorePhase.STORING }
        if (allStored && PrefsAutoSyncStore(context).enabled) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { AppGraph.syncService.syncIncremental() }
            }
        }
    }

    val items = CaptureProgressMapper.map(batch, recognition)
    val total = batch.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("收录进度") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            // 汇总条（顶部）
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (total == 0) {
                        Text("正在准备…", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(
                            CaptureProgressMapper.summaryLabel(progress?.ok ?: 0, total),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "（失败 ${progress?.failed?.size ?: 0}）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (total > 0 && (progress?.ok ?: 0) == total) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "全部完成",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (total == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.index }) { item ->
                    ProgressItemRow(item, onRetry = { item.metaId?.let(vm::retry) })
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** 单行：缩略图 + 来源序号 + 状态徽标 + （识别失败时）重试按钮 */
@Composable
private fun ProgressItemRow(
    item: CaptureProgressMapper.ItemUi,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 缩略图（Coil 按 uri 加载；失败显示占位灰块）
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                AsyncImage(
                    model = item.sourceUri,
                    contentDescription = "第 ${item.index + 1} 张",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "第 ${item.index + 1} 张",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(2.dp))
                StatusBadge(item)
                item.error?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (item.state == CaptureProgressMapper.ItemState.RECOG_FAILED) {
                Button(onClick = onRetry, modifier = Modifier.padding(start = 8.dp)) {
                    Text("重试")
                }
            }
        }
    }
}

/** 状态徽标（图标 + 中文文案） */
@Composable
private fun StatusBadge(item: CaptureProgressMapper.ItemUi) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (item.state) {
            CaptureProgressMapper.ItemState.STORING,
            CaptureProgressMapper.ItemState.RECOGNIZING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            }

            CaptureProgressMapper.ItemState.DONE -> {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(16.dp)
                )
            }

            CaptureProgressMapper.ItemState.STORE_FAILED,
            CaptureProgressMapper.ItemState.RECOG_FAILED -> {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            item.state.label,
            style = MaterialTheme.typography.bodyMedium,
            color = when (item.state) {
                CaptureProgressMapper.ItemState.DONE -> Color(0xFF2E7D32)
                CaptureProgressMapper.ItemState.STORE_FAILED,
                CaptureProgressMapper.ItemState.RECOG_FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
