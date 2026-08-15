package com.misoup.app.ui.screens.library

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.misoup.app.AppGraph
import com.misoup.app.data.index.IndexedEntry
import com.misoup.app.data.store.MisoupConfig
import com.misoup.app.ui.library.DebounceScheduler
import com.misoup.app.ui.library.FilterDim
import com.misoup.app.ui.library.LibraryViewModel
import com.misoup.app.ui.library.SubjectGroup
import androidx.lifecycle.ViewModelProvider

/**
 * 错题库列表（T01-T04）：
 * - 科目分组 + 缩略图 + 摘要 + 标签 + 识别徽标（T01）
 * - 筛选 chips 多选叠加（T02）
 * - 搜索 300ms 防抖（T03）
 * - 列表 ⇄ 瀑布流切换（T04）
 */
@Composable
fun LibraryScreen(
    onOpen: (String) -> Unit = {},
    vm: LibraryViewModel = viewModel(factory = libraryViewModelFactory())
) {
    val state by vm.uiState.collectAsState()

    // 从详情/编辑返回时重新查询（编辑保存后即时刷新）
    LaunchedEffect(Unit) { vm.refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(state.keywordInput, state.searching, vm::onSearchInput, vm::clearSearch)
        FilterBar(
            state = state,
            onToggle = vm::toggleFilter,
            onCycleRepair = vm::cycleRepairFilter,
            onClear = vm::clearFilters
        )
        // 结果数量行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (state.keyword.isNotBlank() || state.filterState.isActive) {
                    "共 ${state.total} 题（筛选/搜索中）"
                } else {
                    "共 ${state.total} 题"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            // 视图切换（T04）
            IconButton(onClick = vm::toggleViewMode) {
                Icon(
                    imageVector = if (state.viewMode == LibraryViewModel.ViewMode.LIST) {
                        Icons.Filled.GridView
                    } else {
                        Icons.AutoMirrored.Filled.ViewList
                    },
                    contentDescription = if (state.viewMode == LibraryViewModel.ViewMode.LIST) "切换瀑布流" else "切换列表",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        when {
            state.total == 0 -> EmptyLibrary(
                hasFilter = state.filterState.isActive || state.keyword.isNotBlank(),
                onClearAll = {
                    vm.clearSearch()
                    vm.clearFilters()
                }
            )

            state.viewMode == LibraryViewModel.ViewMode.LIST -> ListView(
                groups = state.groups,
                onOpen = onOpen,
                onRetry = vm::retryOcr
            )

            else -> StaggeredView(
                groups = state.groups,
                onOpen = onOpen,
                onRetry = vm::retryOcr
            )
        }
    }
}

/** 顶部搜索框（T03） */
@Composable
private fun SearchBar(
    value: String,
    searching: Boolean,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("搜索题干全文或标签…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            when {
                searching -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                value.isNotEmpty() -> IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Clear, contentDescription = "清空搜索")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

/** 筛选栏（T02）：科目/题型/错因/标签 四组横向 chips，多选叠加 */
@Composable
private fun FilterBar(
    state: LibraryViewModel.UiState,
    onToggle: (FilterDim, String) -> Unit,
    onCycleRepair: () -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        FilterChipRow(
            label = "科目",
            options = state.availableSubjects,
            selected = state.filterState.subjects,
            onToggle = { onToggle(FilterDim.SUBJECT, it) }
        )
        FilterChipRow(
            label = "题型",
            options = MisoupConfig.presetQuestionTypes,
            selected = state.filterState.questionTypes,
            onToggle = { onToggle(FilterDim.QUESTION_TYPE, it) }
        )
        FilterChipRow(
            label = "错因",
            options = MisoupConfig.presetErrorReasons,
            selected = state.filterState.errorReasons,
            onToggle = { onToggle(FilterDim.ERROR_REASON, it) }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChipRow(
                label = "标签",
                options = state.availableTags.take(20),
                selected = state.filterState.tags,
                onToggle = { onToggle(FilterDim.TAG, it) },
                modifier = Modifier.weight(1f)
            )
            // 待修复筛选 + 清除
            TextButton(onClick = onCycleRepair) {
                Text(
                    text = when (state.filterState.needsRepair) {
                        true -> "✓ 仅待修复"
                        false -> "仅正常"
                        null -> "待修复"
                    },
                    color = if (state.filterState.needsRepair == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            if (state.filterState.isActive) {
                TextButton(onClick = onClear) { Text("清除") }
            }
        }
    }
}

/** 单组横向 chips（可滚动） */
@Composable
private fun FilterChipRow(
    label: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        item {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp, top = 8.dp)
            )
        }
        items(options) { option ->
            FilterChip(
                selected = option in selected,
                onClick = { onToggle(option) },
                label = { Text(option) }
            )
        }
    }
}

/** 空态 */
@Composable
private fun EmptyLibrary(hasFilter: Boolean, onClearAll: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text("没有找到错题", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hasFilter) "试试调整筛选条件或清空搜索" else "去首页拍照 / 选图收录第一道错题吧",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hasFilter) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onClearAll) { Text("清空筛选与搜索") }
        }
    }
}

/** 列表视图（T01/T04） */
@Composable
private fun ListView(
    groups: List<SubjectGroup>,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { group ->
            item(key = "header-${group.subject}") {
                GroupHeader(group)
            }
            items(group.entries, key = { it.id }) { entry ->
                EntryCard(entry, onOpen = { onOpen(entry.id) }, onRetry = { onRetry(entry.id) })
            }
        }
    }
}

/** 瀑布流视图（T04）：2 列，科目标题占整行 */
@Composable
private fun StaggeredView(
    groups: List<SubjectGroup>,
    onOpen: (String) -> Unit,
    onRetry: (String) -> Unit
) {
    val flat: List<Any> = groups.flatMap { group ->
        listOf<Any>(group) + group.entries
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp
    ) {
        itemsIndexed(flat) { _, item ->
            when (item) {
                is SubjectGroup -> StaggeredGroupHeader(item)
                is IndexedEntry -> EntryCard(
                    entry = item,
                    onOpen = { onOpen(item.id) },
                    onRetry = { onRetry(item.id) },
                    staggered = true
                )
            }
        }
    }
}

/** 科目组头 */
@Composable
private fun GroupHeader(group: SubjectGroup) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            text = group.subject,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${group.entries.size} 题",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 瀑布流内的整行组头 */
@Composable
private fun StaggeredGroupHeader(group: SubjectGroup) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            text = group.subject,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${group.entries.size} 题",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 条目卡片（T01）：缩略图 + 摘要两行 + 标签 chips + 识别徽标。
 * [staggered] 为 true 时缩略图置顶（瀑布流布局）。
 */
@Composable
private fun EntryCard(
    entry: IndexedEntry,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    staggered: Boolean = false
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (staggered) {
            Column(modifier = Modifier.padding(8.dp)) {
                EntryThumb(entry, Modifier.fillMaxWidth().height(140.dp))
                EntryCardBody(entry, onRetry)
            }
        } else {
            Row(modifier = Modifier.padding(10.dp)) {
                EntryThumb(entry, Modifier.size(92.dp))
                Spacer(Modifier.width(10.dp))
                EntryCardBody(entry, onRetry, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EntryCardBody(
    entry: IndexedEntry,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = entry.summary.ifBlank { entry.dirName },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(6.dp))
        TagChipRow(tags = entry.tags, max = 3)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OcrStatusBadge(entry = entry, onRetry = onRetry)
            Spacer(Modifier.weight(1f))
            Text(
                text = entry.questionType.ifBlank { entry.subject },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 默认工厂：AppGraph 单例注入（无 DI 框架，最保守的构造注入） */
@Composable
private fun libraryViewModelFactory(): ViewModelProvider.Factory {
    val context = androidx.compose.ui.platform.LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            LibraryViewModel(
                index = AppGraph.index,
                ocrRetry = AppGraph.ocrRetry,
                debouncer = DebounceScheduler()
            )
        }
    }
}
