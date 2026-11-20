package com.usquebox.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.usquebox.R
import com.usquebox.service.TunnelState
import com.usquebox.viewmodel.TunnelViewModel

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val APPS = "apps"
}

@Composable
fun NavGraph(
    state: TunnelState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    viewModel: TunnelViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        composable(Routes.HOME) {
            Scaffold(
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = { navController.navigate(Routes.SETTINGS) }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            ) { innerPadding ->
                HomeScreen(
                    state = state,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                currentConfig = viewModel.getConfigJson(),
                onSave = { json ->
                    viewModel.saveConfig(json)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                onNavigateToApps = { navController.navigate(Routes.APPS) }
            )
        }
        composable(Routes.APPS) {
            AppListScreen(
                proxyMode = viewModel.getProxyMode(),
                selectedApps = viewModel.getSelectedApps(),
                onModeChanged = { viewModel.setProxyMode(it) },
                onSelectedAppsChanged = { viewModel.setSelectedApps(it) },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
