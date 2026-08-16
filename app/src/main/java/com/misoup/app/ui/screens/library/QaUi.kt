package com.misoup.app.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.misoup.app.ai.qanda.QaMessage
import com.misoup.app.ui.library.QaViewModel

/**
 * 答疑底部弹层（模块 07 T04）：
 * 消息流（用户/老师气泡）+ 快捷诉求（重讲/举例/换一种解法）+ 输入框。
 * 会话仅内存：关闭弹层即清空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QaBottomSheet(
    state: QaViewModel.QaUiState,
    onSend: (String) -> Unit,
    onQuickAction: (QaViewModel.QuickAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("题目答疑", style = MaterialTheme.typography.titleMedium)

            // 快捷诉求（T05：重讲 / 举例 / 换一种解法）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QaViewModel.QuickAction.entries.forEach { action ->
                    SuggestionChip(
                        onClick = { onQuickAction(action) },
                        label = { Text(action.label) },
                        enabled = !state.sending
                    )
                }
            }

            // 消息流
            if (state.messages.isEmpty()) {
                Text(
                    "AI 老师已就绪：可输入问题，或点上方快捷按钮（重讲/举例/换一种解法）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.messages, key = { it.hashCode() }) { message ->
                        MessageBubble(message)
                    }
                }
            }

            if (state.sending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("思考中…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.notReady) {
                AiGuideHint()
            }
            state.error?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            // 输入行
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入你的问题") },
                    enabled = !state.sending,
                    maxLines = 3
                )
                IconButton(
                    onClick = {
                        onSend(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !state.sending
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

/** 消息气泡：用户右对齐主色，AI 老师左对齐 */
@Composable
private fun MessageBubble(message: QaMessage) {
    val isUser = message.role == QaMessage.Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}
