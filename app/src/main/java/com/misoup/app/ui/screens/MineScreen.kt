package com.misoup.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.misoup.app.R

/**
 * 我的：统计概览 + 设置（模块 10 填充；模块 09 已挂「同步与备份」入口）
 */
@Composable
fun MineScreen(onOpenSync: () -> Unit = {}) {
    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        Text(
            stringResource(R.string.tab_mine),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "统计概览、模型配置、同步与数据管理",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        // 模块 09：同步与备份入口（设置页完整版由模块 10 填充后合并）
        Button(
            onClick = onOpenSync,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Sync, contentDescription = null)
            Text("同步与备份（WebDAV）", modifier = Modifier.padding(start = 8.dp))
        }
    }
}
