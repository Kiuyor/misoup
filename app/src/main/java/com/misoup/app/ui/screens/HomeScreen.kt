package com.misoup.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider
import com.misoup.app.AppGraph
import com.misoup.app.R
import com.misoup.app.review.queue.QueueLoader
import com.misoup.app.ui.capture.CaptureSession
import com.misoup.app.ui.capture.PhotoPickLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * T10 首页：采集入口（拍题/选图/连拍 3 大按钮 + 悬浮按钮）+ 今日待复习角标（T07）
 *
 * - 拍题 → 相机单拍页；连拍 → 相机连拍页；选图 → Photo Picker（单张=单题，多选=批量）
 * - 选图结果直接提交 CaptureCoordinator（落库+入队），再进全屏进度页
 */
@Composable
fun HomeScreen(
    onCapture: (mode: String) -> Unit = {},
    onProgress: () -> Unit = {},
    vm: HomeBadgeViewModel = viewModel(factory = homeBadgeViewModelFactory())
) {
    val badge by vm.todayCount.collectAsState()

    // T01 Photo Picker：单张=单题（maxItems=1），多选=批量（maxItems=50）
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        val sources = PhotoPickLogic.toSources(uris?.map { it.toString() })
        if (sources.isNotEmpty()) {
            CaptureSession.pendingUris = uris!!
            onProgress()
        }
    }

    Scaffold(
        floatingActionButton = {
            // 悬浮按钮：快速拍题（T10 文档：3 大按钮 + 悬浮按钮）
            FloatingActionButton(onClick = { onCapture("single") }) {
                Icon(Icons.Filled.AddAPhoto, contentDescription = "快速拍题")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.size(24.dp))
            Text(
                text = stringResource(R.string.tab_home),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "拍题 / 选图收录错题，识别后自动归档",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            Spacer(Modifier.size(32.dp))

            // 三大采集按钮
            CaptureEntryButtons(
                onCapture = onCapture,
                onPick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            )

            Spacer(Modifier.size(32.dp))

            // 今日待复习横幅（T07：首页角标，与复习 tab 同口径）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (badge > 0) {
                            stringResource(R.string.home_review_badge, badge)
                        } else {
                            stringResource(R.string.review_today_banner_done)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

/** 采集入口三按钮（拍题/连拍/选图） */
@Composable
private fun CaptureEntryButtons(
    onCapture: (mode: String) -> Unit,
    onPick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { onCapture("single") },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Filled.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text("拍题", style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onCapture("burst") },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.BurstMode, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("连拍", style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(
                onClick = onPick,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("选图", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** 通用空态占位页（我的 tab 等使用） */
@Composable
fun PlaceholderPage(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** 首页角标 ViewModel：仅今日待复习数量（轻量，与复习 tab 同口径） */
class HomeBadgeViewModel(
    private val queueLoader: QueueLoader
) : ViewModel() {

    private val _todayCount = MutableStateFlow(0)
    val todayCount: StateFlow<Int> = _todayCount.asStateFlow()

    init {
        refresh()
    }

    fun refresh(now: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            _todayCount.value = queueLoader.todayCount(now)
        }
    }
}

@Composable
private fun homeBadgeViewModelFactory(): ViewModelProvider.Factory {
    val context = androidx.compose.ui.platform.LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            val store = checkNotNull(AppGraph.store) { "AppGraph 未初始化" }
            HomeBadgeViewModel(QueueLoader(store, AppGraph.index))
        }
    }
}
