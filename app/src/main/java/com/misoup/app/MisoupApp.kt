package com.misoup.app

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.padding
import com.misoup.app.ui.capture.CameraCaptureScreen
import com.misoup.app.ui.capture.CaptureProgressScreen
import com.misoup.app.ui.screens.HomeScreen
import com.misoup.app.ui.screens.MineScreen
import com.misoup.app.ui.screens.library.LibraryScreen
import com.misoup.app.ui.screens.library.LibraryDetailScreen
import com.misoup.app.ui.screens.library.LibraryEditScreen
import com.misoup.app.ui.screens.review.ReviewScreen
import com.misoup.app.ui.screens.review.ReviewCardScreen
import com.misoup.app.ui.screens.review.KpDetailScreen
import com.misoup.app.ui.components.MisoupBottomBar
import com.misoup.app.ui.sync.SyncScreen

/** 应用导航宿主：底部 4 tab + 错题库详情/编辑页 */
@Composable
fun MisoupApp() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = { MisoupBottomBar(navController) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = androidx.compose.ui.Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    onCapture = { mode -> navController.navigate("capture?mode=$mode") },
                    onProgress = { navController.navigate("capture/progress") },
                    onSplit = { navController.navigate("split") }
                )
            }
            // 模块 01：相机拍照/连拍页（mode=single|burst）
            composable(
                route = "capture?mode={mode}",
                arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = "single" })
            ) { backStackEntry ->
                val mode = backStackEntry.arguments?.getString("mode") ?: "single"
                CameraCaptureScreen(
                    mode = mode,
                    onBack = { navController.popBackStack() },
                    onCaptured = { uris ->
                        navController.popBackStack()
                        // 相机产物经会话桥交给进度页统一提交（连拍一批）
                        com.misoup.app.ui.capture.CaptureSession.pendingUris = uris
                        navController.navigate("capture/progress")
                    }
                )
            }
            // 模块 01：全屏收录进度页（选图/拍照/连拍共用）
            composable("capture/progress") {
                CaptureProgressScreen(onBack = { navController.popBackStack() })
            }
            composable("library") {
                LibraryScreen(
                    onOpen = { id -> navController.navigate("library/detail/${Uri.encode(id)}") },
                    onExport = { navController.navigate("export") }
                )
            }
            // 模块 08：导出入口（选错题 → PDF/Markdown/CSV/分享）
            composable("export") {
                com.misoup.app.ui.export.ExportEntryScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "library/detail/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id") ?: ""
                LibraryDetailScreen(
                    id = id,
                    onBack = { navController.popBackStack() },
                    onEdit = { eid -> navController.navigate("library/edit/${Uri.encode(eid)}") },
                    onDeleted = { navController.popBackStack() }
                )
            }
            composable(
                route = "library/edit/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { backStackEntry ->
                LibraryEditScreen(
                    id = backStackEntry.arguments?.getString("id") ?: "",
                    onDone = { navController.popBackStack() }
                )
            }
            composable("review") {
                ReviewScreen(
                    onStartRedo = { navController.navigate("review/redo") },
                    onOpenKp = { kp -> navController.navigate("review/kp/${Uri.encode(kp)}") }
                )
            }
            composable(
                route = "review/kp/{kp}",
                arguments = listOf(navArgument("kp") { type = NavType.StringType })
            ) { backStackEntry ->
                KpDetailScreen(
                    knowledgePoint = backStackEntry.arguments?.getString("kp") ?: "",
                    onBack = { navController.popBackStack() },
                    onOpenEntry = { id -> navController.navigate("library/detail/${Uri.encode(id)}") }
                )
            }
            composable("review/redo") {
                ReviewCardScreen(onBack = { navController.popBackStack() })
            }
            composable("mine") {
                MineScreen(onOpenSync = { navController.navigate("sync") })
            }
            // 模块 09：同步与备份设置页（WebDAV 配置/手动触发/报告）
            composable("sync") {
                SyncScreen(onBack = { navController.popBackStack() })
            }
            // 模块 06：整页拆题入口（相册选整页 → 候选框 → 逐题入库）
            composable("split") {
                com.misoup.app.splitting.ui.SplitPageEntryScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
