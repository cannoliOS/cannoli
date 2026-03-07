package dev.cannoli.launcher.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.cannoli.launcher.settings.SettingsRepository
import dev.cannoli.launcher.settings.TimeFormat
import dev.cannoli.launcher.ui.screens.DialogState
import dev.cannoli.launcher.ui.screens.GameListScreen
import dev.cannoli.launcher.ui.screens.SettingsScreen
import dev.cannoli.launcher.ui.screens.SystemListScreen
import dev.cannoli.launcher.ui.viewmodel.GameListViewModel
import dev.cannoli.launcher.ui.viewmodel.SettingsViewModel
import dev.cannoli.launcher.ui.viewmodel.SystemListViewModel
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val SYSTEM_LIST = "system_list"
    const val GAME_LIST = "game_list/{tag}"
    const val SETTINGS = "settings"

    fun gameList(tag: String) = "game_list/$tag"
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    systemListViewModel: SystemListViewModel,
    gameListViewModel: GameListViewModel,
    settingsViewModel: SettingsViewModel,
    settings: SettingsRepository,
    dialogState: StateFlow<DialogState>
) {
    val dialog by dialogState.collectAsState()
    val showBatteryPct = settings.batteryPercentage
    val use24h = settings.timeFormat == TimeFormat.TWENTY_FOUR_HOUR

    NavHost(
        navController = navController,
        startDestination = Routes.SYSTEM_LIST
    ) {
        composable(Routes.SYSTEM_LIST) {
            SystemListScreen(
                viewModel = systemListViewModel,
                showBatteryPercentage = showBatteryPct,
                use24hTime = use24h,
                onPlatformSelected = { },
                onCollectionSelected = { },
                onToolSelected = { },
                onPortSelected = { },
                onSettingsRequested = { }
            )
        }

        composable(Routes.GAME_LIST) {
            GameListScreen(
                viewModel = gameListViewModel,
                showBatteryPercentage = showBatteryPct,
                use24hTime = use24h,
                dialogState = dialog,
                onBack = { },
                onPreviousPlatform = { },
                onNextPlatform = { }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = { }
            )
        }
    }
}
