package dev.cannoli.launcher.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.cannoli.launcher.settings.ScrollSpeed
import dev.cannoli.launcher.settings.TextSize
import dev.cannoli.launcher.ui.components.StatusBar
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
    dialogState: StateFlow<DialogState>
) {
    val dialog by dialogState.collectAsState()
    val appSettings by settingsViewModel.appSettings.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val showStatusBar = currentRoute != Routes.SETTINGS

    val listFontSize = when (appSettings.textSize) {
        TextSize.SMALL -> 16.sp
        TextSize.MEDIUM -> 22.sp
        TextSize.LARGE -> 24.sp
    }
    val listLineHeight = when (appSettings.textSize) {
        TextSize.SMALL -> 22.sp
        TextSize.MEDIUM -> 32.sp
        TextSize.LARGE -> 34.sp
    }
    val listVerticalPadding = when (appSettings.textSize) {
        TextSize.SMALL -> 4.dp
        TextSize.MEDIUM -> 8.dp
        TextSize.LARGE -> 10.dp
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.SYSTEM_LIST,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            composable(Routes.SYSTEM_LIST) {
                SystemListScreen(
                    viewModel = systemListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
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
                    backgroundImagePath = appSettings.backgroundImagePath,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    boxArtEnabled = appSettings.boxArtEnabled,
                    scrollSpeed = appSettings.scrollSpeed,
                    dialogState = dialog,
                    onBack = { },
                    onPreviousPlatform = { },
                    onNextPlatform = { }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    onBack = { }
                )
            }
        }

        if (showStatusBar) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
            ) {
                StatusBar(
                    showBatteryPercentage = appSettings.showBatteryPct,
                    use24hTime = appSettings.use24h
                )
            }
        }
    }
}
