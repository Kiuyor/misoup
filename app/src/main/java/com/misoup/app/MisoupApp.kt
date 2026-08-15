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
import com.misoup.app.ui.screens.HomeScreen
import com.misoup.app.ui.screens.MineScreen
import com.misoup.app.ui.screens.library.LibraryScreen
import com.misoup.app.ui.screens.library.LibraryDetailScreen
import com.misoup.app.ui.screens.library.LibraryEditScreen
import com.misoup.app.ui.screens.review.ReviewScreen
import com.misoup.app.ui.screens.review.ReviewCardScreen
import com.misoup.app.ui.components.MisoupBottomBar

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
            composable("home") { HomeScreen() }
            composable("library") {
                LibraryScreen(
                    onOpen = { id -> navController.navigate("library/detail/${Uri.encode(id)}") }
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
            composable("review") { ReviewScreen(onStartRedo = { navController.navigate("review/redo") }) }
            composable("review/redo") {
                ReviewCardScreen(onBack = { navController.popBackStack() })
            }
            composable("mine") { MineScreen() }
        }
    }
}
