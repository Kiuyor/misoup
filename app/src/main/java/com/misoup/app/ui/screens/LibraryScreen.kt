package com.misoup.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.misoup.app.R

/** 错题库：列表/筛选/搜索（模块 02 填充） */
@Composable
fun LibraryScreen() {
    PlaceholderPage(
        icon = { Icon(Icons.Filled.Book, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = stringResource(R.string.tab_library),
        subtitle = "按科目分组浏览，支持筛选、搜索与编辑"
    )
}
