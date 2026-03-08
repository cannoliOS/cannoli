package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.List
import dev.cannoli.scorza.ui.components.PillRowText
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.screenPadding
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel.ListItem

@Composable
fun SystemListScreen(
    viewModel: SystemListViewModel,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    onPlatformSelected: (String) -> Unit,
    onCollectionSelected: (String) -> Unit,
    onSettingsRequested: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            List(
                items = state.items,
                selectedIndex = state.selectedIndex,
                scrollTarget = state.scrollTarget,
                onVisibleRangeChanged = { first, count ->
                    viewModel.firstVisibleIndex = first
                    viewModel.pageSize = count
                },
                modifier = Modifier
                    .fillMaxWidth(0.65f)
                    .padding(top = 4.dp, bottom = 48.dp)
            ) { index, item ->
                val label = when (item) {
                    is ListItem.FavoritesItem -> "Favorites"
                    is ListItem.CollectionsFolder -> "Collections"
                    is ListItem.PlatformItem -> item.platform.displayName
                    is ListItem.CollectionItem -> item.name
                    is ListItem.ToolsFolder -> "Tools"
                    is ListItem.PortsFolder -> "Ports"
                    is ListItem.Divider -> null
                }
                if (label != null) {
                    val showReorder = state.reorderMode && state.selectedIndex == index && (item is ListItem.PlatformItem || item is ListItem.ToolsFolder || item is ListItem.PortsFolder)
                    val check = if (state.multiSelectMode) index in state.checkedIndices else null
                    PillRowText(
                        label = label,
                        isSelected = state.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        showReorderIcon = showReorder,
                        checkState = check
                    )
                }
            }

            val rightItems = if (state.multiSelectMode) {
                listOf("A" to stringResource(R.string.label_toggle), "▶" to stringResource(R.string.label_confirm))
            } else {
                listOf("A" to stringResource(R.string.label_select))
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf("\uDB81\uDC25" to stringResource(R.string.label_sleep)),
                rightItems = rightItems
            )
        }
    }
}
