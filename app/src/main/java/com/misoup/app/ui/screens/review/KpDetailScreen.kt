package com.misoup.app.ui.screens.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider
import com.misoup.app.AppGraph

/**
 * 知识点详情页（模块 07 T06）：
 * 掌握度头部（红黄绿 + 分数）+ 关联错题列表（点击直达详情）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KpDetailScreen(
    knowledgePoint: String,
    onBack: () -> Unit = {},
    onOpenEntry: (String) -> Unit = {},
    vm: KpDetailViewModel = viewModel(factory = kpDetailViewModelFactory())
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(knowledgePoint) { vm.load(knowledgePoint) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.kp.ifBlank { "知识点" }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.loading) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("加载中…")
                    }
                }
            }

            // 掌握度头部
            state.mastery?.let { m ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .background(color = Color(m.level.color), shape = RoundedCornerShape(50))
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = m.level.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(m.level.color)
                                )
                                Text(
                                    text = "掌握度 ${"%.0f%%".format(m.score * 100)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "${m.totalReviews} 次复习",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "关联错题（${state.entries.size}）",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (state.entries.isEmpty() && !state.loading) {
                item {
                    Text(
                        "暂无关联错题",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(state.entries, key = { it.id }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenEntry(entry.id) }
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = entry.dirName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = entry.summary.ifBlank { "（无题干摘要）" },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = entry.subject,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${entry.reviewCount} 次复习",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 知识点详情 VM 工厂（AppGraph 注入） */
@Composable
private fun kpDetailViewModelFactory(): ViewModelProvider.Factory {
    val context = androidx.compose.ui.platform.LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            KpDetailViewModel(
                store = checkNotNull(AppGraph.store) { "AppGraph 未初始化" },
                index = AppGraph.index
            )
        }
    }
}
