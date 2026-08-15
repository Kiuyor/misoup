package com.misoup.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.padding
import com.misoup.app.ui.screens.HomeScreen
import com.misoup.app.ui.screens.LibraryScreen
import com.misoup.app.ui.screens.ReviewScreen
import com.misoup.app.ui.screens.MineScreen
import com.misoup.app.ui.components.MisoupBottomBar

/** 应用导航宿主：底部 4 tab */
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
            composable("library") { LibraryScreen() }
            composable("review") { ReviewScreen() }
            composable("mine") { MineScreen() }
        }
    }
}
