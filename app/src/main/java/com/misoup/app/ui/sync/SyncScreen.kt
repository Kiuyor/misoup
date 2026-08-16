package com.misoup.app.ui.sync

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.misoup.app.AppGraph

/**
 * 同步设置页（模块 09 T05）：WebDAV 配置 + 手动触发 + 同步报告 + 失败退避状态
 *
 * 入口：设置页「同步与备份」（MineScreen → sync 路由，见 MisoupApp）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onBack: () -> Unit,
    vm: SyncViewModel = viewModel(factory = syncViewModelFactory())
) {
    val state by vm.state.collectAsState()
    var url by remember { mutableStateOf(state.config.url) }
    var username by remember { mutableStateOf(state.config.username) }
    var password by remember { mutableStateOf(state.config.password) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步与备份") },
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
            Text("WebDAV 同步（坚果云等）", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务器地址（https://...）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名 / 邮箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码 / 应用密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (url.isNotBlank() && !url.trim().startsWith("https://")) {
                Text(
                    "⚠ 仅支持 HTTPS：http 地址将被拒绝连接（本地联调请确认已勾选允许不安全连接）",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveConfig(url, username, password) }) {
                    Text("保存配置")
                }
                OutlinedButton(onClick = { vm.clearCredentials() }) {
                    Text("清除凭据")
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("录入后自动同步", style = MaterialTheme.typography.bodyLarge)
                    Text("默认关；开启后每次录入结束自动增量同步", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.autoSync, onCheckedChange = { vm.setAutoSync(it) })
            }

            Button(
                onClick = { vm.syncNow() },
                enabled = state.isConfigured && !state.syncing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.syncing) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text("同步中…")
                } else {
                    Text("立即同步")
                }
            }

            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            state.lastReport?.let { r ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("上次同步报告", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "上传 ${r.uploaded} · 下载 ${r.downloaded} · 冲突 ${r.conflicts} · 删除 ${r.deleted} · 跳过 ${r.skipped}"
                    )
                    if (r.errors.isNotEmpty()) {
                        Text(
                            "错误：\n" + r.errors.joinToString("\n"),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            state.retryInMs?.let {
                Text(
                    "同步失败，${it / 1000}s 后自动重试（第 ${state.failedAttempts} 次）",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 同步设置页默认工厂（AppGraph 单例注入） */
@Composable
private fun syncViewModelFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            SyncViewModel(
                credRepo = AppGraph.syncCredentialRepo,
                syncAction = { AppGraph.syncService.syncIncremental() }
            )
        }
    }
}
