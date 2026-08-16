package com.misoup.app.ui.screens.mine

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.misoup.app.settings.review.PrefsFsrsSettingsStore

/**
 * 复习设置（模块 10 T04）：FSRS 参数高级编辑（17 个 w + short-term）+ 复习提醒（V2 占位）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSettingsScreen(
    onBack: () -> Unit,
    vm: ReviewSettingsViewModel = viewModel(factory = reviewSettingsViewModelFactory()),
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("复习设置") },
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
            // ---- FSRS 参数（高级） ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("FSRS 参数（高级）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "FSRS-4.5 共 17 个权重 w[0..16]，逗号分隔。默认值为官方初始参数，一般无需修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = state.wText,
                        onValueChange = { vm.onWTextChanged(it) },
                        label = { Text("w[0..16]（17 个数值）") },
                        isError = !state.wValid,
                        supportingText = { Text(state.wError ?: "例如：0.40255, 1.18385, …") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("short-term 调度", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "开：短间隔保留小数天（重学约数小时）；关：四舍五入到整天",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = state.enableShortTerm, onCheckedChange = { vm.setEnableShortTerm(it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.save() },
                            enabled = state.wValid,
                            modifier = Modifier.weight(1f)
                        ) { Text("保存参数") }
                        OutlinedButton(
                            onClick = { vm.resetDefaults() },
                            modifier = Modifier.weight(1f)
                        ) { Text("恢复默认") }
                    }
                    state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            // ---- 复习提醒（V2 占位） ----
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("复习提醒（V2 占位）", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = state.reminderTime,
                            onValueChange = { vm.setReminderTime(it) },
                            label = { Text("提醒时间（HH:mm）") },
                            enabled = state.reminderEnabled,
                            modifier = Modifier.weight(1f)
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
                        Switch(checked = state.reminderEnabled, onCheckedChange = { vm.setReminderEnabled(it) })
                    }
                    Text(
                        "每日提醒功能将在 V2 接入（WorkManager + POST_NOTIFICATIONS，API 33+ 需授予通知权限）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 复习设置工厂 */
@Composable
private fun reviewSettingsViewModelFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return viewModelFactory {
        initializer {
            ReviewSettingsViewModel(store = PrefsFsrsSettingsStore(context))
        }
    }
}
