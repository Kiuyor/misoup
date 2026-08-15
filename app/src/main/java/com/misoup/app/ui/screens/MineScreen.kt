package com.misoup.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.misoup.app.R

/** 我的：统计概览 + 设置（模块 10 填充） */
@Composable
fun MineScreen() {
    PlaceholderPage(
        icon = { Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = stringResource(R.string.tab_mine),
        subtitle = "统计概览、模型配置、同步与数据管理"
    )
}
