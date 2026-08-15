package com.misoup.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.misoup.app.R

/** 复习：今日队列 + FSRS 统计（模块 03 填充） */
@Composable
fun ReviewScreen() {
    PlaceholderPage(
        icon = { Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = stringResource(R.string.tab_review),
        subtitle = "按遗忘曲线安排复习，遮罩重做巩固记忆"
    )
}
