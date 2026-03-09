package dev.cannoli.scorza.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.settings.TextSize
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.components.List
import dev.cannoli.scorza.ui.components.ListDialogScreen
import dev.cannoli.scorza.ui.components.MessageOverlay
import dev.cannoli.scorza.ui.components.PillRowKeyValue
import dev.cannoli.scorza.ui.components.PillRowText
import dev.cannoli.scorza.ui.components.StatusBar
import dev.cannoli.scorza.ui.components.pillItemHeight
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.CoreMappingEntry
import dev.cannoli.scorza.ui.screens.CorePickerOption
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.GameListScreen
import dev.cannoli.scorza.ui.screens.SettingsScreen
import dev.cannoli.scorza.ui.screens.SystemListScreen
import dev.cannoli.scorza.ui.screens.isFullScreen
import dev.cannoli.scorza.ui.theme.CannoliColors
import dev.cannoli.scorza.ui.theme.GrayText
import dev.cannoli.scorza.ui.theme.LocalCannoliColors
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import kotlinx.coroutines.flow.StateFlow

sealed class LauncherScreen {
    data object SystemList : LauncherScreen()
    data object GameList : LauncherScreen()
    data object Settings : LauncherScreen()
    data class CoreMapping(val mappings: List<CoreMappingEntry>, val selectedIndex: Int = 0, val scrollTarget: Int = 0) : LauncherScreen()
    data class CorePicker(val tag: String, val platformName: String, val cores: List<CorePickerOption>, val selectedIndex: Int = 0, val gamePath: String? = null, val scrollTarget: Int = 0) : LauncherScreen()
    data class ColorList(val colors: List<ColorEntry>, val selectedIndex: Int = 0, val scrollTarget: Int = 0) : LauncherScreen()
    data class CollectionPicker(val gamePaths: List<String>, val title: String, val collections: List<String>, val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), val scrollTarget: Int = 0) : LauncherScreen()
    data class AppPicker(val type: String, val title: String, val apps: List<String>, val packages: List<String>, val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet(), val scrollTarget: Int = 0) : LauncherScreen()
}

@Composable
fun AppNavGraph(
    currentScreen: LauncherScreen,
    systemListViewModel: SystemListViewModel,
    gameListViewModel: GameListViewModel,
    settingsViewModel: SettingsViewModel,
    dialogState: StateFlow<DialogState>
) {
    val dialog by dialogState.collectAsState()
    val appSettings by settingsViewModel.appSettings.collectAsState()

    val listFontSize = when (appSettings.textSize) {
        TextSize.COMPACT -> 16.sp
        TextSize.DEFAULT -> 22.sp
    }
    val listLineHeight = when (appSettings.textSize) {
        TextSize.COMPACT -> 22.sp
        TextSize.DEFAULT -> 32.sp
    }
    val listVerticalPadding = when (appSettings.textSize) {
        TextSize.COMPACT -> 4.dp
        TextSize.DEFAULT -> 4.dp
    }

    val cannoliColors = CannoliColors(
        highlight = appSettings.colorHighlight,
        text = appSettings.colorText,
        highlightText = appSettings.colorHighlightText,
        accent = appSettings.colorAccent
    )

    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)

    CompositionLocalProvider(LocalCannoliColors provides cannoliColors) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            is LauncherScreen.SystemList -> SystemListScreen(
                viewModel = systemListViewModel,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                dialogState = dialog
            )
            is LauncherScreen.GameList -> GameListScreen(
                viewModel = gameListViewModel,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                scrollSpeed = appSettings.scrollSpeed,
                dialogState = dialog
            )
            is LauncherScreen.Settings -> SettingsScreen(
                viewModel = settingsViewModel,
                backgroundImagePath = appSettings.backgroundImagePath,
                backgroundTint = appSettings.backgroundTint,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                listVerticalPadding = listVerticalPadding,
                dialogState = dialog
            )
            is LauncherScreen.CoreMapping -> {
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.setting_core_mapping),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf("A" to stringResource(R.string.label_select))
                ) {
                    List(
                        items = currentScreen.mappings,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget
                    ) { index, entry ->
                        val value = if (entry.runnerLabel.isNotEmpty())
                            "${entry.coreDisplayName} (${entry.runnerLabel})"
                        else entry.coreDisplayName
                        PillRowKeyValue(
                            label = entry.platformName,
                            value = value,
                            isSelected = currentScreen.selectedIndex == index,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
            is LauncherScreen.CorePicker -> {
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.platformName,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf("A" to stringResource(R.string.label_select))
                ) {
                    if (currentScreen.cores.isEmpty()) {
                        Text(
                            text = "No compatible cores found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = GrayText,
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.cores,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget
                        ) { index, option ->
                            PillRowText(
                                label = "${option.displayName} (${option.runnerLabel})",
                                isSelected = currentScreen.selectedIndex == index,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding
                            )
                        }
                    }
                }
            }
            is LauncherScreen.ColorList -> {
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = stringResource(R.string.setting_colors),
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = listOf("A" to stringResource(R.string.label_select))
                ) {
                    List(
                        items = currentScreen.colors,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget
                    ) { index, entry ->
                        PillRowKeyValue(
                            label = entry.label,
                            value = entry.hex.uppercase(),
                            isSelected = currentScreen.selectedIndex == index,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            swatchColor = androidx.compose.ui.graphics.Color(entry.color.toInt())
                        )
                    }
                }
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding
                    )
                }
            }
            is LauncherScreen.CollectionPicker -> {
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    rightBottomItems = listOf(
                        "X" to stringResource(R.string.label_new)
                    )
                ) {
                    if (currentScreen.collections.isEmpty()) {
                        Text(
                            text = "No collections",
                            style = MaterialTheme.typography.bodyLarge,
                            color = GrayText,
                            modifier = Modifier.padding(start = 14.dp)
                        )
                    } else {
                        List(
                            items = currentScreen.collections,
                            selectedIndex = currentScreen.selectedIndex,
                            itemHeight = itemHeight,
                            scrollTarget = currentScreen.scrollTarget
                        ) { index, collection ->
                            PillRowText(
                                label = collection,
                                isSelected = currentScreen.selectedIndex == index,
                                fontSize = listFontSize,
                                lineHeight = listLineHeight,
                                verticalPadding = listVerticalPadding,
                                checkState = index in currentScreen.checkedIndices
                            )
                        }
                    }
                }
                if (dialog.isFullScreen) {
                    DialogOverlay(
                        dialogState = dialog,
                        backgroundImagePath = appSettings.backgroundImagePath,
                        backgroundTint = appSettings.backgroundTint,
                        listFontSize = listFontSize,
                        listLineHeight = listLineHeight,
                        listVerticalPadding = listVerticalPadding
                    )
                } else {
                    val d = dialog
                    if (d is DialogState.CollectionCreated) {
                        MessageOverlay(message = "${d.collectionName} Created")
                    }
                }
            }
            is LauncherScreen.AppPicker -> {
                ListDialogScreen(
                    backgroundImagePath = appSettings.backgroundImagePath,
                    backgroundTint = appSettings.backgroundTint,
                    title = currentScreen.title,
                    listFontSize = listFontSize,
                    listLineHeight = listLineHeight,
                    fullWidth = true,
                    rightBottomItems = emptyList()
                ) {
                    List(
                        items = currentScreen.apps,
                        selectedIndex = currentScreen.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = currentScreen.scrollTarget
                    ) { index, app ->
                        PillRowText(
                            label = app,
                            isSelected = currentScreen.selectedIndex == index,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            checkState = index in currentScreen.checkedIndices
                        )
                    }
                }
            }
        }

        val statusBarVisible = dialog !is DialogState.About && (appSettings.showWifi || appSettings.showBluetooth || appSettings.showClock || appSettings.showBattery)
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
