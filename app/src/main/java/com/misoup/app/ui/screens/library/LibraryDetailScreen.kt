package com.misoup.app.ui.screens.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.misoup.app.AppGraph
import com.misoup.app.ai.TextLlmClient
import com.misoup.app.ai.variants.VariantGenerator
import com.misoup.app.data.model.Subject
import com.misoup.app.ui.library.DeleteConfirm
import com.misoup.app.ui.library.DeleteEvent
import com.misoup.app.ui.library.LibraryDetailViewModel
import com.misoup.app.ui.library.OcrStatusLogic
import com.misoup.app.ui.library.OcrStatusUi
import com.misoup.app.ui.library.QaViewModel
import com.misoup.app.ui.library.VariantViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * 详情页（T05/T06/T08/T10）：
 * - 大图双指缩放 + 题干/答案/解析（答案解析折叠，LaTeX 先纯文本占位，M5 接 KaTeX）
 * - 遮罩开关（T06）：隐藏答案 → 答案/解析区遮罩 + 动画揭开预览
 * - 移动/删除双确认（T08）
 * - 识别状态条（T10）：pending 识别中 / failed 重试 / done 无
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryDetailScreen(
    id: String,
    onBack: () -> Unit = {},
    onEdit: (String) -> Unit = {},
    onDeleted: () -> Unit = {},
    vm: LibraryDetailViewModel = viewModel(factory = detailViewModelFactory())
) {
    val state by vm.state.collectAsState()
    // 模块 07：变式出题 / 题目答疑（key 含 id，每道题独立 VM 实例）
    val variantVm: VariantViewModel = viewModel(key = "variant-$id", factory = variantViewModelFactory(id))
    val qaVm: QaViewModel = viewModel(key = "qa-$id", factory = qaViewModelFactory(id))
    val variantState by variantVm.state.collectAsState()
    val qaState by qaVm.state.collectAsState()

    LaunchedEffect(id) { vm.load(id) }

    // 删除完成 → 返回列表
    LaunchedEffect(state.deleteConfirm) {
        if (state.deleteConfirm == DeleteConfirm.Done) onDeleted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.entry?.dirName ?: "详情", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.entry != null) {
                        IconButton(onClick = { onEdit(state.id) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑")
                        }
                        IconButton(onClick = vm::showMoveDialog) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "移动科目")
                        }
                        IconButton(onClick = { vm.onDeleteEvent(DeleteEvent.Request) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                        }
                        IconButton(onClick = vm::share) {
                            Icon(Icons.Filled.Share, contentDescription = "分享")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (state.entry == null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        ) {
            // 大图（T05）
            item {
                ZoomableImage(
                    path = state.imagePath ?: state.entry?.thumbPath,
                    contentDescription = "题目原图"
                )
            }
            // 识别状态条（T10）
            if (OcrStatusLogic.needsStatusBar(state.ocrStatus)) {
                item { OcrStatusBar(state.ocrStatus, vm::retryOcr) }
            }
            // 题干（T05）
            item {
                Text(
                    text = state.text?.question?.ifBlank { "（暂无题干，点编辑补充）" } ?: "（暂无题干）",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // 答案 + 解析（遮罩区，T06）
            item {
                Box {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CollapsibleSection(
                            title = "答案",
                            content = state.text?.answer ?: "",
                            initiallyExpanded = false
                        )
                        CollapsibleSection(
                            title = "解析",
                            content = state.text?.explanation ?: "",
                            initiallyExpanded = false
                        )
                    }
                    if (state.mask.maskVisible) {
                        MaskOverlay(
                            revealProgress = state.mask.revealProgress,
                            onPeek = vm::peek,
                            modifier = Modifier.matchParentSize()
                        )
                    }
                }
            }
            // 遮罩开关（T06）
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("隐藏答案", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "开启后答案与解析区域被遮罩覆盖，可用于自测重做",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = state.mask.hidden, onCheckedChange = { vm.toggleMask() })
                    }
                }
            }
            // 模块 07：变式出题 / 题目答疑（M4）
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = variantVm::openDialog,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("变式出题")
                    }
                    OutlinedButton(
                        onClick = qaVm::open,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("题目答疑")
                    }
                }
            }
            // 模块 07：已保存变式列表（答案可展开）
            if (variantState.saved.isNotEmpty()) {
                item {
                    Text(
                        text = "变式练习（${variantState.saved.size} 道）",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(variantState.saved, key = { it.fileName }) { saved ->
                    VariantCard(
                        saved = saved,
                        expanded = saved.fileName in variantState.expanded,
                        onToggle = { variantVm.toggleExpand(saved.fileName) }
                    )
                }
            }
        }
    }

    // 模块 07：变式出题弹窗（数量 1-3 → 生成 → 保存）
    if (variantState.dialogVisible) {
        VariantDialog(
            state = variantState,
            onCount = variantVm::setCount,
            onGenerate = variantVm::generateAndSave,
            onDismiss = variantVm::dismissDialog
        )
    }

    // 模块 07：答疑底部弹层（消息流，会话仅内存）
    if (qaState.sheetVisible) {
        QaBottomSheet(
            state = qaState,
            onSend = qaVm::send,
            onQuickAction = qaVm::quickAction,
            onDismiss = qaVm::close
        )
    }

    // 删除双确认（T08）
    when (val dc = state.deleteConfirm) {
        DeleteConfirm.FirstConfirm -> AlertDialog(
            onDismissRequest = { vm.onDeleteEvent(DeleteEvent.Cancel) },
            title = { Text("删除这道错题？") },
            text = { Text("将删除题目目录、图片与缩略图。") },
            confirmButton = { TextButton(onClick = { vm.onDeleteEvent(DeleteEvent.Confirm) }) { Text("继续") } },
            dismissButton = { TextButton(onClick = { vm.onDeleteEvent(DeleteEvent.Cancel) }) { Text("取消") } }
        )

        DeleteConfirm.SecondConfirm -> AlertDialog(
            onDismissRequest = { vm.onDeleteEvent(DeleteEvent.Cancel) },
            title = { Text("再次确认删除") },
            text = { Text("删除后不可恢复！确定要删除吗？") },
            confirmButton = {
                TextButton(onClick = { vm.onDeleteEvent(DeleteEvent.Confirm) }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { vm.onDeleteEvent(DeleteEvent.Cancel) }) { Text("取消") } }
        )

        is DeleteConfirm.Error -> AlertDialog(
            onDismissRequest = vm::dismissDeleteError,
            title = { Text("删除失败") },
            text = { Text(dc.message) },
            confirmButton = { TextButton(onClick = vm::dismissDeleteError) { Text("知道了") } }
        )

        else -> Unit
    }

    // 移动科目（T08）
    if (state.moveDialogVisible) {
        val subjects = (Subject.presetNames + listOfNotNull(state.entry?.subject)).distinct()
        AlertDialog(
            onDismissRequest = vm::dismissMoveDialog,
            title = { Text("移动到科目") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    subjects.forEach { subject ->
                        TextButton(onClick = { vm.moveTo(subject) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                subject + if (subject == state.entry?.subject) "（当前）" else "",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissMoveDialog) { Text("取消") } }
        )
    }

    // 操作反馈
    state.message?.let { msg ->
        LaunchedEffect(msg) {
            // 短暂展示后自动清除
            kotlinx.coroutines.delay(2500)
            vm.clearMessage()
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
    }

    // 揭开动画（T06）：peeking 时 revealProgress 0→1，遮罩淡出
    val revealTarget = if (state.peeking) 1f else 0f
    val revealProgress by animateFloatAsState(
        targetValue = revealTarget,
        animationSpec = tween(durationMillis = 600),
        label = "maskReveal"
    )
    LaunchedEffect(revealProgress, state.peeking) {
        vm.setRevealProgress(revealProgress)
        if (revealProgress >= 1f && state.peeking) vm.onRevealFinished()
    }
}

/** 识别状态条（T10） */
@Composable
private fun OcrStatusBar(status: OcrStatusUi, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            when (status) {
                OcrStatusUi.Pending -> {
                    CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("识别中…", style = MaterialTheme.typography.bodyMedium)
                }

                OcrStatusUi.Failed -> {
                    Text(
                        "识别失败",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.width(16.dp).height(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重试")
                    }
                }

                OcrStatusUi.Repair -> {
                    Text(
                        "待修复（meta.json 损坏，点编辑查看原始内容）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                }

                OcrStatusUi.Done -> Unit
            }
        }
    }
}

/** 详情页默认工厂（AppGraph 单例注入） */
@Composable
private fun detailViewModelFactory(): ViewModelProvider.Factory {
    val context = androidx.compose.ui.platform.LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            LibraryDetailViewModel(
                index = AppGraph.index,
                store = AppGraph.store,
                ocrRetry = AppGraph.ocrRetry
            )
        }
    }
}

/** 变式出题 VM 工厂（模块 07 T03）：AppGraph 文本通道 + 存储 */
@Composable
private fun variantViewModelFactory(id: String): ViewModelProvider.Factory {
    val context = androidx.compose.ui.platform.LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            val store = checkNotNull(AppGraph.store) { "AppGraph 未初始化" }
            VariantViewModel(
                id = id,
                generator = VariantGenerator(
                    llm = checkNotNull(AppGraph.textLlm) { "文本 LLM 未初始化" },
                    store = store
                ),
                store = store
            )
        }
    }
}

/** 答疑 VM 工厂（模块 07 T04）：AppGraph 文本通道 + 存储 */
@Composable
private fun qaViewModelFactory(id: String): ViewModelProvider.Factory {
    val context = androidx.compose.ui.platform.LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            QaViewModel(
                id = id,
                llm = checkNotNull(AppGraph.textLlm) { "文本 LLM 未初始化" },
                store = checkNotNull(AppGraph.store) { "AppGraph 未初始化" }
            )
        }
    }
}
