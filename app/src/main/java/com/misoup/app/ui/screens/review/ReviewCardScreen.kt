package com.misoup.app.ui.screens.review

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider
import coil.compose.AsyncImage
import com.misoup.app.AppGraph
import com.misoup.app.R
import com.misoup.app.review.mask.MaskRect
import com.misoup.app.review.mask.MaskStore
import com.misoup.app.review.queue.QueueLoader
import com.misoup.app.review.scheduler.ReviewSubmitter
import com.misoup.app.settings.review.PrefsFsrsSettingsStore

/**
 * 重做卡片页（T04/T05/T06 UI）：
 * 题干 + 原图（答案区遮罩）→ [显示答案] 动画揭开 → 4 档自评（Again/Hard/Good/Easy）→ 下一张。
 * 长按图片进入框选模式，拖动框选自定义答案区（未结构拆分时兜底，T05）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewCardScreen(
    onBack: () -> Unit = {},
    vm: ReviewCardViewModel = viewModel(factory = reviewCardViewModelFactory())
) {
    val state by vm.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.review_redo_back))
            }
            Text(
                text = if (state.done) stringResource(R.string.review_redo_done) else "复习 ${state.queueProgress}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (state.mask.selecting) {
                TextButtonCompat(
                    text = stringResource(R.string.review_redo_cancel_select),
                    onClick = vm::cancelSelection
                )
            }
        }

        when {
            state.done -> DoneView(onBack = onBack)
            state.current == null && state.queue.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.review_queue_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> CardContent(state = state, vm = vm)
        }
    }
}

@Composable
private fun CardContent(state: ReviewCardViewModel.UiState, vm: ReviewCardViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // ---------- 题干 ----------
        SectionTitle(stringResource(R.string.review_redo_question))
        Text(
            text = state.text.question.ifBlank { "（暂无题干文本）" },
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(12.dp))

        // ---------- 原图 + 答案区遮罩（T04/T05） ----------
        val maskRect = state.mask.effective()
        val revealProgress by animateFloatAsState(
            targetValue = if (state.revealed) 1f else 0f,
            animationSpec = tween(durationMillis = 400),
            label = "reveal"
        )
        // 动画进度回传 VM（钳制）
        androidx.compose.runtime.LaunchedEffect(revealProgress) { vm.onRevealProgress(revealProgress) }

        var imageSize by remember { mutableStateOf(IntSize.Zero) }
        var dragStart by remember { mutableStateOf<Offset?>(null) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { imageSize = it }
                .combinedClickable(
                    onClick = { /* 单击无操作 */ },
                    onLongClick = { if (!state.revealed && !state.mask.selecting) vm.enterSelectMode() }
                )
                .pointerInput(state.mask.selecting) {
                    if (state.mask.selecting) {
                        detectDragGestures(
                            onDragStart = { offset -> dragStart = offset },
                            onDrag = { change, _ ->
                                change.consume()
                                val start = dragStart ?: return@detectDragGestures
                                val rect = MaskRect.fromPixels(
                                    start.x, start.y, change.position.x, change.position.y,
                                    imageSize.width, imageSize.height
                                )
                                vm.updateSelection(rect)
                            },
                            onDragEnd = { vm.commitSelection(); dragStart = null },
                            onDragCancel = { dragStart = null }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // 原图（Coil 本地文件）
            AsyncImage(
                model = state.imagePath,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth()
            )

            // 遮罩层：未完全揭开且非框选模式时盖住答案区
            if (!state.mask.selecting && revealProgress < 1f) {
                val alpha = (1f - revealProgress).coerceIn(0f, 1f)
                val rect = maskRect
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRect(
                        color = Color(0xFF1C1B1F).copy(alpha = alpha),
                        topLeft = Offset(rect.left * size.width, rect.top * size.height),
                        size = androidx.compose.ui.geometry.Size(
                            (rect.right - rect.left) * size.width,
                            (rect.bottom - rect.top) * size.height
                        )
                    )
                }
            }

            // 框选模式：半透明选择层 + 拖动中的矩形高亮
            if (state.mask.selecting) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRect(color = Color.Black.copy(alpha = 0.35f))
                    state.mask.current?.let { cur ->
                        drawRect(
                            color = Color(0x8042A5F5),
                            topLeft = Offset(cur.left * size.width, cur.top * size.height),
                            size = androidx.compose.ui.geometry.Size(
                                (cur.right - cur.left) * size.width,
                                (cur.bottom - cur.top) * size.height
                            )
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(
                if (state.mask.selecting) R.string.review_redo_selecting_hint
                else R.string.review_redo_long_press_hint
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))

        // ---------- 答案/解析（显示答案后） ----------
        if (state.revealed) {
            SectionTitle(stringResource(R.string.review_redo_answer))
            Text(
                text = state.text.answer.ifBlank { "（暂无答案文本）" },
                style = MaterialTheme.typography.bodyMedium
            )
            if (state.text.explanation.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                SectionTitle(stringResource(R.string.review_redo_explanation))
                Text(state.text.explanation, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))

            // ---------- 4 档自评按钮（T04/T06） ----------
            GradeButtons(
                submitting = state.submitting,
                onGrade = vm::submitGrade
            )
        } else {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = vm::reveal,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(stringResource(R.string.review_redo_show_answer))
            }
        }

        state.error?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/** 4 档自评：Again / Hard / Good / Easy（提交中禁用防重复） */
@Composable
private fun GradeButtons(submitting: Boolean, onGrade: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GradeButton(
            label = stringResource(R.string.review_redo_grade_again),
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            enabled = !submitting,
            modifier = Modifier.weight(1f)
        ) { onGrade(1) }
        GradeButton(
            label = stringResource(R.string.review_redo_grade_hard),
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            enabled = !submitting,
            modifier = Modifier.weight(1f)
        ) { onGrade(2) }
        GradeButton(
            label = stringResource(R.string.review_redo_grade_good),
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            enabled = !submitting,
            modifier = Modifier.weight(1f)
        ) { onGrade(3) }
        GradeButton(
            label = stringResource(R.string.review_redo_grade_easy),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            enabled = !submitting,
            modifier = Modifier.weight(1f)
        ) { onGrade(4) }
    }
    if (submitting) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("提交中…", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun GradeButton(
    label: String,
    container: Color,
    content: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(64.dp)
                .combinedClickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = content,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** 复习完成视图 */
@Composable
private fun DoneView(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🎉", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.review_redo_done), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack) {
            Text(stringResource(R.string.review_redo_back))
        }
    }
}

/** 顶栏小按钮（避免引入 TextButton 与 Row 权重冲突） */
@Composable
private fun TextButtonCompat(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** 默认工厂（与其余页面同模式：AppGraph 单例注入） */
@Composable
private fun reviewCardViewModelFactory(): ViewModelProvider.Factory {
    val context = LocalContext.current
    return viewModelFactory {
        initializer {
            AppGraph.init(context)
            val store = checkNotNull(AppGraph.store) { "AppGraph 未初始化" }
            ReviewCardViewModel(
                queueLoader = QueueLoader(store, AppGraph.index),
                submitter = ReviewSubmitter(store),
                store = store,
                maskStore = MaskStore(checkNotNull(AppGraph.libraryRoot) { "库根未初始化" }, AppGraph.index),
                // 模块 10 T04：FSRS 调度参数接线（提交时实时读取设置页参数）
                fsrsSettings = PrefsFsrsSettingsStore(context)
            )
        }
    }
}
