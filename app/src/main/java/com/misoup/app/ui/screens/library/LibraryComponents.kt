package com.misoup.app.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.misoup.app.data.index.IndexedEntry
import com.misoup.app.ui.library.OcrStatusLogic
import com.misoup.app.ui.library.OcrStatusUi
import java.io.File

/**
 * 识别状态徽标（T01/T10）：
 * - pending：灰点 + 「识别中…」
 * - failed：红点 + 「识别失败」，整条可点击重试
 * - repair：黄点 + 「待修复」（索引损坏条目）
 * - done：不显示
 */
@Composable
fun OcrStatusBadge(
    entry: IndexedEntry,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = OcrStatusLogic.of(entry.ocrStatus, entry.needsRepair)
    if (!OcrStatusLogic.needsStatusBar(status)) return

    val dotColor = when (status) {
        OcrStatusUi.Pending -> Color(0xFF9E9E9E)
        OcrStatusUi.Failed -> Color(0xFFE53935)
        OcrStatusUi.Repair -> Color(0xFFF9A825)
        OcrStatusUi.Done -> Color.Transparent
    }
    val clickable = status == OcrStatusUi.Failed

    val surface: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = OcrStatusLogic.label(status) + if (clickable) " · 点击重试" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (clickable) {
        Surface(
            onClick = onRetry,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier
        ) { surface() }
    } else {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = modifier
        ) { surface() }
    }
}

/** 缩略图（T01）：Coil 256px 磁盘缓存；无缩略图时显示占位 */
@Composable
fun EntryThumb(
    entry: IndexedEntry,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val model = entry.thumbPath?.let { path ->
        ImageRequest.Builder(context)
            .data(File(path))
            .size(256)
            .build()
    }
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = entry.summary,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp))
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(
                Icons.Filled.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 标签小 chips（T01/T02）：最多显示 [max] 个，超出折叠为 "+N" */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChipRow(
    tags: List<String>,
    modifier: Modifier = Modifier,
    max: Int = 3
) {
    if (tags.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
    ) {
        tags.take(max).forEach { tag ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        if (tags.size > max) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "+${tags.size - max}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/** 可双指缩放的大图（T05）：缩放 1..5 倍 + 平移；双击重置由后续打磨 */
@Composable
fun ZoomableImage(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val model = path?.let {
        ImageRequest.Builder(context).data(File(it)).build()
    }
    if (model == null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = Offset(offset.x + pan.x, offset.y + pan.y)
                }
            }
    )
}

/**
 * 遮罩层（T06）：覆盖答案/解析区，半透明 + 「揭开预览」按钮。
 * [revealProgress] 0→1 时遮罩淡出（动画揭开预览），完全揭开后整层隐藏。
 * 调用方需在 BoxScope 内传入 `Modifier.matchParentSize()` 以铺满答案/解析区。
 */
@Composable
fun MaskOverlay(
    revealProgress: Float,
    onPeek: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha = (1f - revealProgress).coerceIn(0f, 1f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f * alpha))
            .graphicsLayer { this.alpha = alpha }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "答案已隐藏",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (revealProgress < 1f) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPeek) { Text("揭开预览") }
            }
        }
    }
}

/** 折叠区（T05）：标题 + 展开/收起 */
@Composable
fun CollapsibleSection(
    title: String,
    content: String,
    initiallyExpanded: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(4.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            if (expanded) {
                Text(
                    text = content.ifBlank { "（无内容）" },
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    text = content.ifBlank { "（无内容）" }.lineSequence().first().take(40),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
