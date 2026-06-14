package app.coulombmppt.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.coulombmppt.ui.alerts.AlertsScreen
import app.coulombmppt.ui.controllers.ControllersHomeScreen
import app.coulombmppt.ui.diagnostics.DiagnosticsScreen
import app.coulombmppt.ui.info.InfoScreen
import app.coulombmppt.ui.inverter.InverterScreen
import app.coulombmppt.ui.logs.LogsScreen
import app.coulombmppt.ui.pairing.PairingScreen
import app.coulombmppt.ui.settings.AppSettingsScreen
import app.coulombmppt.ui.settings.ControllerSettingsScreen
import app.coulombmppt.ui.startup.StartupScreen
import app.coulombmppt.ui.unit.UnitDetailScreen

object Routes {
    const val STARTUP     = "startup"
    const val CONTROLLERS = "controllers"
    const val UNIT        = "unit"                  // unit/{controllerId}
    const val PAIRING     = "pairing"
    const val CTRL_SETTINGS = "controller_settings"
    const val APP_SETTINGS  = "app_settings"
    const val DIAGNOSTICS   = "diagnostics"
    const val LOGS          = "logs"
    const val INFO          = "info"
    const val ALERTS        = "alerts"
    const val DOCS          = "docs"                // phase-5 placeholder
    const val INVERTER      = "inverter"            // inverter/{controllerId}

    fun unit(controllerId: String) = "$UNIT/$controllerId"
    fun inverter(controllerId: String) = "$INVERTER/$controllerId"
}

// Routes that show the bottom nav bar — top-level screens only.
private val BOTTOM_NAV_ROUTES = setOf(
    Routes.CONTROLLERS, Routes.ALERTS, Routes.PAIRING, Routes.APP_SETTINGS,
)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            if (currentRoute in BOTTOM_NAV_ROUTES) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                        label = { Text("Fleet") },
                        selected = currentRoute == Routes.CONTROLLERS,
                        onClick = {
                            nav.navigate(Routes.CONTROLLERS) { launchSingleTop = true }
                        },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                        label = { Text("Alerts") },
                        selected = currentRoute == Routes.ALERTS,
                        onClick = {
                            nav.navigate(Routes.ALERTS) { launchSingleTop = true }
                        },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        label = { Text("Pair") },
                        selected = currentRoute == Routes.PAIRING,
                        onClick = {
                            nav.navigate(Routes.PAIRING) { launchSingleTop = true }
                        },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                        selected = currentRoute == Routes.APP_SETTINGS,
                        onClick = {
                            nav.navigate(Routes.APP_SETTINGS) { launchSingleTop = true }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        // Always open on the launch chooser (Local Bluetooth vs Remote API). It
        // sets which MpptSource this session uses, then routes onward.
        NavHost(
            navController    = nav,
            startDestination = Routes.STARTUP,
            modifier         = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            composable(Routes.STARTUP) {
                StartupScreen(
                    onLocal = { configured ->
                        nav.navigate(if (configured) Routes.CONTROLLERS else Routes.PAIRING) {
                            popUpTo(Routes.STARTUP) { inclusive = true }
                        }
                    },
                    onRemote = {
                        nav.navigate(Routes.CONTROLLERS) {
                            popUpTo(Routes.STARTUP) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.CONTROLLERS) {
                ControllersHomeScreen(
                    onOpenController = { id -> nav.navigate(Routes.unit(id)) },
                    onOpenPairing    = { nav.navigate(Routes.PAIRING) },
                    onOpenDocs       = { nav.navigate(Routes.INFO) },
                )
            }
            composable(Routes.ALERTS) {
                AlertsScreen(onBack = { nav.popBackStack() })
            }
            composable(
                route     = "${Routes.UNIT}/{controllerId}",
                arguments = listOf(navArgument("controllerId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("controllerId") ?: return@composable
                UnitDetailScreen(
                    controllerId             = id,
                    onBack                   = { nav.popBackStack() },
                    onOpenControllerSettings = { nav.navigate(Routes.CTRL_SETTINGS) },
                    onOpenDiagnostics        = { nav.navigate(Routes.DIAGNOSTICS) },
                    onOpenInverter           = { nav.navigate(Routes.inverter(id)) },
                    onAfterUnpair            = {
                        nav.navigate(Routes.CONTROLLERS) {
                            popUpTo(Routes.CONTROLLERS) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route     = "${Routes.INVERTER}/{controllerId}",
                arguments = listOf(navArgument("controllerId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("controllerId") ?: return@composable
                InverterScreen(
                    controllerId = id,
                    onBack       = { nav.popBackStack() },
                )
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.LOGS) {
                LogsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.INFO) {
                InfoScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.PAIRING) {
                PairingScreen(
                    onPaired = { newControllerId ->
                        val target = if (newControllerId == "demo") Routes.CONTROLLERS
                                     else Routes.unit(newControllerId)
                        nav.navigate(target) { popUpTo(Routes.PAIRING) { inclusive = true } }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.CTRL_SETTINGS) {
                ControllerSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.APP_SETTINGS) {
                AppSettingsScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
