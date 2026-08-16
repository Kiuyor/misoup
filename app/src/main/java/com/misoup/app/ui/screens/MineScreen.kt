package com.misoup.app.ui.screens

import androidx.compose.runtime.Composable
import com.misoup.app.ui.screens.mine.MineSettingsScreen

/**
 * 我的：统计概览 + 设置页（模块 10 T01）
 *
 * 完整设置页实现见 [MineSettingsScreen]（ui/screens/mine 包）；
 * 本文件保持入口签名不变（MisoupApp 导航引用），仅做转发。
 */
@Composable
fun MineScreen(onOpenSync: () -> Unit = {}) {
    MineSettingsScreen(onOpenSync = onOpenSync)
}
