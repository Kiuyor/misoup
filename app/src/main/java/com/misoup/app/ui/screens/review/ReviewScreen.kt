package com.misoup.app.ui.screens.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider
import com.misoup.app.AppGraph
import com.misoup.app.R
import com.misoup.app.ai.mastery.WeakKpAggregator
import com.misoup.app.review.queue.QueueLoader
import com.misoup.app.review.stats.Mastery
import com.misoup.app.review.stats.Stats
import java.time.format.DateTimeFormatter

/**
 * 复习 tab（T07/T09）：今日待复习横幅 + 学情统计卡片（展开详情）+ 队列列表 + 开始复习。
 *
 * - 横幅："今日待复习 N 题"（T07）
 * - 统计：三数字（总数/复习率/正确率）+ 近 30 天趋势 + 科目分布 + 知识点红黄绿（T08/T09/T10）
 * - 队列：due 升序 + stability 升序（T03），点击"开始复习"进入重做卡片页
 */
@Composable
fun ReviewScreen(
    onStartRedo: () -> Unit = {},
    onOpenKp: (String) -> Unit = {},
    vm: ReviewViewModel = viewModel(factory = reviewViewModelFactory())
) {
    val state by vm.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---------- 今日待复习横幅（T07） ----------
        item {
            TodayBanner(count = state.todayCount, loading = state.loading)
        }

        // ---------- 学情统计卡片（T09） ----------
        state.stats?.let { stats ->
            item { StatsCard(stats = stats) }
        }

        // ---------- 薄弱知识点卡片（T06，模块 07） ----------
        item { WeakKpCard(weakKps = state.weakKps, onOpenKp = onOpenKp) }

        // ---------- 队列列表（T03 UI） ----------
        item {
            Text(
                text = "今日队列",
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (state.queue.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.review_queue_empty))
                    }
                }
            }
        } else {
            items(state.queue, key = { it.id }) { item ->
                QueueRow(item = item)
            }
            item {
                Button(
                    onClick = onStartRedo,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.review_start))
                }
            }
        }

        state.error?.let { msg ->
            item { Text(msg, color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** 今日待复习横幅 */
@Composable
private fun TodayBanner(count: Int, loading: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (count > 0) {
                    stringResource(R.string.review_today_banner, count)
                } else {
                    stringResource(R.string.review_today_banner_done)
                },
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

/** 学情统计卡片：三数字 + 展开详情（趋势/科目/知识点） */
@Composable
private fun StatsCard(stats: Stats) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 三数字
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell(label = stringResource(R.string.review_stats_total), value = "${stats.total}", modifier = Modifier.weight(1f))
                StatCell(
                    label = stringResource(R.string.review_stats_review_rate),
                    value = "%.0f%%".format(stats.reviewRate * 100),
                    modifier = Modifier.weight(1f)
                )
                StatCell(
                    label = stringResource(R.string.review_stats_accuracy),
                    value = "%.0f%%".format(stats.accuracy * 100),
                    modifier = Modifier.weight(1f)
                )
            }
            // 展开/收起
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(top = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(if (expanded) R.string.review_stats_collapse else R.string.review_stats_expand),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                // 近 30 天趋势
                Text(stringResource(R.string.review_stats_trend), style = MaterialTheme.typography.labelLarge)
                TrendBar(stats)
                Spacer(Modifier.height(12.dp))
                // 科目分布
                Text(stringResource(R.string.review_stats_subject), style = MaterialTheme.typography.labelLarge)
                stats.bySubject.entries.sortedByDescending { it.value }.forEach { (subject, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(subject, style = MaterialTheme.typography.bodySmall)
                        Text("$count 题", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                // 知识点掌握度（红黄绿）
                Text(stringResource(R.string.review_stats_mastery), style = MaterialTheme.typography.labelLarge)
                if (stats.mastery.isEmpty()) {
                    Text(
                        stringResource(R.string.review_stats_mastery_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    stats.mastery.entries.sortedBy { it.key }.forEach { (kp, score) ->
                        MasteryRow(kp = kp, score = score)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 近 30 天趋势：简化柱状（最高值归一化） */
@Composable
private fun TrendBar(stats: Stats) {
    val max = stats.trend30d.maxOfOrNull { it.second } ?: 0
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        stats.trend30d.forEach { (date, count) ->
            val fraction = if (max == 0) 0f else count.toFloat() / max
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((36 * fraction).dp + 1.dp)
                    .background(
                        color = if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)
                    )
            )
        }
    }
    // 起止日期标注
    val fmt = DateTimeFormatter.ofPattern("MM-dd")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            stats.trend30d.first().first.format(fmt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stats.trend30d.last().first.format(fmt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 知识点掌握度行：名称 + 分数 + 红黄绿圆点 */
@Composable
private fun MasteryRow(kp: String, score: Double) {
    val level = Mastery.levelOf(score)
    val color = Color(level.color)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(10.dp).background(color = color, shape = RoundedCornerShape(50))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = kp,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${level.displayName} ${"%.0f%%".format(score * 100)}",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** 队列行：题干摘要 + 状态标签 */
@Composable
private fun QueueRow(item: com.misoup.app.review.queue.ReviewQueue.QueueItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.subject.ifBlank { "未分科目" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (item.isNew) "新卡 · 首次复习" else "到期复习",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "S=${"%.1f".format(item.stability)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 薄弱知识点卡片（模块 07 T06）：按掌握度升序 top5，红黄绿三档，点击进知识点详情 */
@Composable
private fun WeakKpCard(
    weakKps: List<WeakKpAggregator.WeakKp>,
    onOpenKp: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "薄弱知识点",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "掌握度升序 Top${weakKps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (weakKps.isEmpty()) {
                Text(
                    "暂无知识点数据，先给错题打上知识点标签",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                weakKps.forEach { kp ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenKp(kp.knowledgePoint) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(color = Color(kp.level.color), shape = RoundedCornerShape(50))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = kp.knowledgePoint,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${kp.level.displayName} ${"%.0f%%".format(kp.score * 100)} · ${kp.entryIds.size} 题",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(kp.level.color)
                        )
                    }
                }
            }
        }
    }
}

/** 默认工厂：AppGraph 单例注入（与错题库页同模式） */
@Composable
private fun reviewViewModelFactory(): ViewModelProvider.Factory {
    val context = androidx.compose.ui.platform.LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            val store = checkNotNull(AppGraph.store) { "AppGraph 未初始化" }
            ReviewViewModel(
                queueLoader = QueueLoader(store, AppGraph.index),
                store = store,
                index = AppGraph.index
            )
        }
    }
}
