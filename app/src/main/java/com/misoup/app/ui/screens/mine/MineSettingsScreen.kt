package com.misoup.app.ui.screens.mine

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.misoup.app.data.store.LibraryRoot

/**
 * 「我的」设置页框架（模块 10 T01）：统计概览 + 分组列表（AI 增强/识别模型/复习/数据/关于）
 *
 * 组内导航用 rememberSaveable 状态切换（不引导航路由，避免改动 MisoupApp）。
 */
@Composable
fun MineSettingsScreen(onOpenSync: () -> Unit = {}) {
    var current by rememberSaveable { mutableStateOf<String?>(null) }
    when (current) {
        null -> MineHomeScreen(onOpenGroup = { current = it }, onOpenSync = onOpenSync)
        MineGroup.AI.id -> AiSettingsScreen(onBack = { current = null })
        MineGroup.MODEL.id -> RecognitionModelScreen(onBack = { current = null })
        MineGroup.REVIEW.id -> ReviewSettingsScreen(onBack = { current = null })
        MineGroup.DATA.id -> DataSettingsScreen(onBack = { current = null }, onOpenSync = onOpenSync)
        MineGroup.ABOUT.id -> AboutScreen(onBack = { current = null })
    }
}

/** 首页：统计概览 + ROM 引导 + 五组入口列表 */
@Composable
private fun MineHomeScreen(
    onOpenGroup: (String) -> Unit,
    onOpenSync: () -> Unit,
    vm: MineViewModel = viewModel(factory = mineViewModelFactory()),
) {
    val state by vm.state.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall)
        Text(
            "统计概览 · 模型配置 · 数据管理",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        // ---- 统计概览（复用模块 03） ----
        StatsOverviewCard(
            total = state.stats?.total,
            reviewRate = state.stats?.reviewRate,
            accuracy = state.stats?.accuracy,
            bySubject = state.stats?.bySubject,
        )

        // ---- ROM 白名单引导（T09；vivo/小米/华为 显示对应指引） ----
        state.romGuide?.let { guide ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "后台白名单引导（${state.romKind.displayName}）",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(guide, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---- 分组列表 ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                MineGroup.entries.forEachIndexed { index, group ->
                    GroupRow(group = group, onClick = { onOpenGroup(group.id) })
                    if (index < MineGroup.entries.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }

        // 模块 09：同步与备份入口（数据组内亦有入口）
        androidx.compose.material3.OutlinedButton(
            onClick = onOpenSync,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("同步与备份（WebDAV）")
        }
    }
}

/** 统计概览卡：三数字 + 科目分布（stats 未就绪显示占位） */
@Composable
private fun StatsOverviewCard(
    total: Int?,
    reviewRate: Double?,
    accuracy: Double?,
    bySubject: Map<String, Int>?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("统计概览", style = MaterialTheme.typography.titleSmall)
            if (total == null) {
                Text("数据加载中…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("错题总数", total.toString(), Modifier.weight(1f))
                StatCell("复习率", pct(reviewRate), Modifier.weight(1f))
                StatCell("正确率", pct(accuracy), Modifier.weight(1f))
            }
            if (!bySubject.isNullOrEmpty()) {
                Text(
                    "科目分布：" + bySubject.entries.joinToString(" · ") { "${it.key} ${it.value}题" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun pct(v: Double?): String = if (v == null) "—" else "${(v * 100).toInt()}%"

@Composable
private fun GroupRow(group: MineGroup, onClick: () -> Unit) {
    androidx.compose.material3.Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(group.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    group.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 「我的」页 ViewModel 工厂（AppGraph 单例注入） */
@Composable
private fun mineViewModelFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            MineViewModel(
                statsProvider = { loadMineStats() },
                libraryPathProvider = { AppGraph.libraryRoot?.absolutePath ?: "" },
                libraryCustomizedProvider = { LibraryRoot.isCustomized() },
                versionNameProvider = {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: ""
                },
                manufacturerProvider = { Build.MANUFACTURER },
            )
        }
    }
}
