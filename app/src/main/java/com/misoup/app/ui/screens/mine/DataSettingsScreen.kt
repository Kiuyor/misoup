package com.misoup.app.ui.screens.mine

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.misoup.app.data.store.LibraryRoot
import java.io.File

/**
 * 数据设置（模块 10 T05）：库目录（路径 + SAF 迁移）/ WebDAV / 备份恢复导入导出 / 清理缓存
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    onOpenSync: () -> Unit,
    vm: DataSettingsViewModel = viewModel(factory = dataSettingsViewModelFactory()),
) {
    val state by vm.state.collectAsState()

    // SAF 选目录（库迁移）；OpenDocumentTree 在 API 26-36 均可用
    val migrateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> vm.migrateLibrary(uri?.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据") },
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
            // ---- 库目录 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("库目录", style = MaterialTheme.typography.titleSmall)
                    Text(
                        state.libraryPath,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = { migrateLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("选择新目录（SAF 迁移）") }
                }
            }

            // ---- WebDAV ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("WebDAV 同步", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (state.webdavConfigured) "已配置（HTTPS 强制）" else "未配置（坚果云等，仅支持 HTTPS）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = onOpenSync) { Text("配置") }
                    }
                }
            }

            // ---- 备份 / 恢复 / 导入 / 导出 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("备份与迁移", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "整库备份（zip）/ 恢复 / 单题导入导出 / 导出错题到 PDF·Markdown·CSV",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.showMessage("备份：SAF 保存文件选择将在后续版本接入（模块 09 服务已就绪）") },
                            modifier = Modifier.weight(1f)
                        ) { Text("备份") }
                        OutlinedButton(
                            onClick = { vm.showMessage("恢复：SAF 文件选择将在后续版本接入（模块 09 服务已就绪）") },
                            modifier = Modifier.weight(1f)
                        ) { Text("恢复") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.showMessage("导入：SAF 文件选择将在后续版本接入（模块 09 服务已就绪）") },
                            modifier = Modifier.weight(1f)
                        ) { Text("导入") }
                        OutlinedButton(
                            onClick = { vm.showMessage("导出：请到「错题库 → 导出」选择题目导出 PDF/Markdown/CSV") },
                            modifier = Modifier.weight(1f)
                        ) { Text("导出") }
                    }
                }
            }

            // ---- 清理缓存 ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("清理缓存", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "缩略图缓存 + 识别缓存（不影响错题数据）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { vm.cleanCache() },
                        enabled = !state.cleaning,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.cleaning) "清理中…" else "清理缓存") }
                    state.cleanResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** 数据设置工厂（库目录/缓存目录来自 AppGraph 与 context.cacheDir） */
@Composable
private fun dataSettingsViewModelFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            DataSettingsViewModel(
                libraryPathProvider = { AppGraph.libraryRoot?.absolutePath ?: "" },
                libraryCustomizedProvider = { LibraryRoot.isCustomized() },
                webdavConfiguredProvider = { AppGraph.syncCredentialRepo.isConfigured },
                cacheDirsProvider = {
                    val cache = context.cacheDir
                    listOf(
                        File(cache, "thumbs"),          // 缩略图缓存（ThumbService）
                        File(cache, "ocr_cache"),       // 识别缓存
                    )
                },
            )
        }
    }
}
