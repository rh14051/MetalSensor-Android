package com.metalsensor.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.metalsensor.app.ui.screens.ConnectionScreen
import com.metalsensor.app.ui.screens.DashboardScreen
import com.metalsensor.app.ui.screens.TuningScreen

/**
 * Single-activity app with three destinations:
 *  connect -> dashboard -> tuning
 * The whole UI is forced to RTL because the app language is Persian,
 * regardless of the device system language.
 */
@Composable
fun AppRoot(vm: MainViewModel) {
    val navController = rememberNavController()
    val conn by vm.connState.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    LaunchedEffect(conn) {
        when (conn) {
            ConnState.CONNECTED -> {
                if (route != "dashboard" && route != "tuning") {
                    navController.navigate("dashboard") { launchSingleTop = true }
                }
            }

            ConnState.DISCONNECTED -> {
                if (route == "dashboard" || route == "tuning") {
                    navController.navigate("connect") {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            else -> Unit
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = "connect",
            ) {
                composable("connect") {
                    ConnectionScreen(vm)
                }
                composable("dashboard") {
                    DashboardScreen(
                        vm = vm,
                        onOpenTuning = {
                            navController.navigate("tuning") { launchSingleTop = true }
                        },
                    )
                }
                composable("tuning") {
                    TuningScreen(
                        vm = vm,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
