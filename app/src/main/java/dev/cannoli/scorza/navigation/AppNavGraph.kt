package dev.cannoli.scorza.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.cannoli.scorza.settings.TextSize
import dev.cannoli.scorza.ui.components.StatusBar
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.GameListScreen
import dev.cannoli.scorza.ui.screens.SettingsScreen
import dev.cannoli.scorza.ui.screens.SystemListScreen
import dev.cannoli.scorza.ui.theme.CannoliColors
import dev.cannoli.scorza.ui.theme.LocalCannoliColors
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
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
        TextSize.MEDIUM -> 4.dp
        TextSize.LARGE -> 6.dp
    }

    val cannoliColors = CannoliColors(
        highlight = appSettings.colorHighlight,
        text = appSettings.colorText,
        highlightText = appSettings.colorHighlightText,
        accent = appSettings.colorAccent
    )

    CompositionLocalProvider(LocalCannoliColors provides cannoliColors) {
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
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    dialogState = dialog
                )
            }

            composable(Routes.GAME_LIST) {
                GameListScreen(
                    viewModel = gameListViewModel,
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
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
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    listVerticalPadding = listVerticalPadding,
                    dialogState = dialog,
                    onBack = { }
                )
            }
        }

        val statusBarVisible = appSettings.showWifi || appSettings.showBluetooth || appSettings.showClock || appSettings.showBattery
        if (statusBarVisible) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
        ) {
            StatusBar(
                use24hTime = appSettings.use24h,
                showWifi = appSettings.showWifi,
                showBluetooth = appSettings.showBluetooth,
                showClock = appSettings.showClock,
                showBattery = appSettings.showBattery,
                lineHeight = listLineHeight,
                verticalPadding = listVerticalPadding
            )
        }
        }
    }
    }
}
