package com.misoup.app.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.misoup.app.ai.AiError
import com.misoup.app.ui.library.VariantViewModel
import com.misoup.app.ui.library.VariantViewModel.SavedVariant

/**
 * 变式弹窗（模块 07 T03）：数量 1-3 选择 → 生成 → 保存。
 * 失败语义（T07）：未配置 → 引导文案；限流/断网 → 「AI 服务不可用」明确提示。
 */
@Composable
fun VariantDialog(
    state: VariantViewModel.VariantUiState,
    onCount: (Int) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("变式出题") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "围绕本错题考点生成变式题（难度递增），保存到「变式/」目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 数量选择 1-3（文档定稿）
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..3).forEach { n ->
                        OutlinedButton(
                            onClick = { onCount(n) },
                            enabled = !state.generating,
                            colors = if (n == state.count) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            }
                        ) {
                            Text("$n 道")
                        }
                    }
                }
                if (state.generating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("AI 生成中…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.notReady) {
                    AiGuideHint()
                }
                state.error?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onGenerate,
                enabled = !state.generating && !state.notReady
            ) {
                Text(if (state.generating) "生成中…" else "生成")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 无 Key 引导（T07）：复用模块 05 引导语义，不当作错误 */
@Composable
fun AiGuideHint() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(AiError.NOT_READY_MESSAGE, style = MaterialTheme.typography.bodyMedium)
            Text(
                "在「我的 → 设置 → AI 增强」粘贴 Key（BYOK 或免费引导）后回来重试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 变式列表卡片（T03）：题干常显，答案/提示点击展开。
 */
@Composable
fun VariantCard(
    saved: SavedVariant,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = saved.variant.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (expanded) "收起" else "展开答案",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(saved.variant.question, style = MaterialTheme.typography.bodyMedium)
            if (expanded) {
                HorizontalDivider()
                Text("答案", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(saved.variant.answer, style = MaterialTheme.typography.bodyMedium)
                saved.variant.hint?.let { hint ->
                    Spacer(Modifier.height(6.dp))
                    Text("提示", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    Text(hint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
