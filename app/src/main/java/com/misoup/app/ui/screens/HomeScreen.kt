package com.misoup.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.misoup.app.R

/** 首页：录入入口 + 今日队列（模块 01/03 填充） */
@Composable
fun HomeScreen() {
    PlaceholderPage(
        icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = stringResource(R.string.tab_home),
        subtitle = "拍照 / 选图收录错题，识别后自动归档"
    )
}

/** 通用空态占位页 */
@Composable
fun PlaceholderPage(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        icon()
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
