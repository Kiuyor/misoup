package com.misoup.app.splitting.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * T03 拆题页 UI：原图 + 叠加候选框。
 *
 * 交互（模块 06 文档 §人工确认）：
 * - 点按框 → 选中（高亮）
 * - 拖拽选中框 → 移动（位移映射回原图坐标）
 * - 底部按钮：新增 / 删除 / 合并 / 撤销全部 / 开始逐题录入（T04）
 * - 烂图（无法拆分）→ 明确提示「建议手动裁剪」，不崩溃
 * - 两栏排版 → 提示建议启用视觉 LLM 修正
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitPageScreen(
    image: ImageBitmap,
    vm: SplitPageViewModel,
    onBack: () -> Unit,
    submitter: (suspend (List<com.misoup.app.splitting.candidates.BoundingBox>) -> SplitSubmitSummary)? = null,
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("整页拆题") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // 提示区：无法拆分 / 两栏 / LLM 降级
            if (state.unsplittable) {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
                Text(
                    text = "烂图不崩溃：请改用相册手动裁剪单题后录入",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                if (state.twoColumnHint) {
                    Text(
                        text = "检测到疑似两栏排版，离线拆分仅作初稿，建议启用视觉 LLM 修正",
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (state.llmDegraded) {
                    Text(
                        text = state.llmMessage,
                        color = MaterialTheme.colorScheme.outline,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                // 画布：原图 + 叠加框
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .aspectRatio(image.width.toFloat() / image.height.toFloat())
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(state.boxes) {
                                detectTapGestures { offset ->
                                    val scaleX = size.width / image.width
                                    val scaleY = size.height / image.height
                                    vm.pickAt(offset.x / scaleX, offset.y / scaleY)?.let { vm.select(it) }
                                }
                            }
                            .pointerInput(state.boxes) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val scaleX = size.width / image.width
                                    val scaleY = size.height / image.height
                                    vm.moveSelected(dragAmount.x / scaleX, dragAmount.y / scaleY)
                                }
                            }
                    ) {
                        drawImage(image)
                        val scaleX = size.width / image.width
                        val scaleY = size.height / image.height
                        state.boxes.forEachIndexed { index, box ->
                            val isSelected = index == state.selected
                            drawRect(
                                color = if (isSelected) Color.Red else Color(0xFF2196F3),
                                topLeft = Offset(box.left * scaleX, box.top * scaleY),
                                size = Size(box.width * scaleX, box.height * scaleY),
                                style = Stroke(width = if (isSelected) 4f else 2f),
                            )
                        }
                    }
                }
                // 操作条
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { vm.addBox(image.width.toFloat(), image.height.toFloat()) }) {
                        Text("新增")
                    }
                    OutlinedButton(onClick = { vm.deleteSelected() }) {
                        Text("删除")
                    }
                    OutlinedButton(onClick = { vm.mergeSelected() }) {
                        Text("合并")
                    }
                    OutlinedButton(onClick = { vm.undoAllToSnapshot() }) {
                        Text("撤销全部")
                    }
                }
                HorizontalDivider()
                // 录入进度
                if (state.importing) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        LinearProgressIndicator(
                            progress = { if (state.importTotal > 0) state.importDone.toFloat() / state.importTotal else 0f },
                            modifier = Modifier.weight(1f),
                        )
                        Text("${state.importDone}/${state.importTotal}")
                    }
                }
                if (state.importSummary.isNotBlank()) {
                    Text(
                        text = state.importSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Button(
                    onClick = {
                        if (submitter != null) {
                            vm.onImportStart(state.boxes.size)
                            scope.launch {
                                val summary = submitter(vm.currentBoxes())
                                vm.onImportFinished(summary)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    enabled = !state.unsplittable && !state.importing && state.boxes.isNotEmpty(),
                ) {
                    Text("开始逐题录入（${state.boxes.size} 题）")
                }
            }
        }
    }
}
