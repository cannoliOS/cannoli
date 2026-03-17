package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.components.List
import dev.cannoli.scorza.ui.components.PillRowText
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.pillItemHeight
import dev.cannoli.scorza.ui.components.screenPadding
import dev.cannoli.scorza.ui.theme.LocalCannoliColors
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
    dialogState: DialogState = DialogState.None,
    onVisibleRangeChanged: (Int, Int, Boolean) -> Unit = { _, _, _ -> },
    kitchenRunning: Boolean = false
) {
    val state by viewModel.state.collectAsState()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No content found. Go add some!",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = listFontSize,
                            lineHeight = listLineHeight
                        ),
                        color = LocalCannoliColors.current.text
                    )
                }
            } else {
            List(
                items = state.items,
                selectedIndex = state.selectedIndex,
                itemHeight = itemHeight,
                scrollTarget = state.scrollTarget,
                onVisibleRangeChanged = { first, count, full ->
                    viewModel.firstVisibleIndex = first
                    onVisibleRangeChanged(first, count, full)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 48.dp),
                key = { _, item ->
                    when (item) {
                        is ListItem.FavoritesItem -> "favorites"
                        is ListItem.CollectionsFolder -> "collections"
                        is ListItem.PlatformItem -> item.platform.tag
                        is ListItem.CollectionItem -> "col:${item.name}"
                        is ListItem.ToolsFolder -> "tools"
                        is ListItem.PortsFolder -> "ports"
                        is ListItem.Divider -> "div:${item.label}"
                    }
                }
            ) { index, item ->
                val label = when (item) {
                    is ListItem.FavoritesItem -> "Favorites"
                    is ListItem.CollectionsFolder -> "Collections"
                    is ListItem.PlatformItem -> item.platform.displayName
                    is ListItem.CollectionItem -> item.name
                    is ListItem.ToolsFolder -> item.name
                    is ListItem.PortsFolder -> item.name
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
            }

            val rightItems = if (state.items.isEmpty()) {
                listOf("Y" to "KITCHEN")
            } else if (state.multiSelectMode) {
                listOf("A" to stringResource(R.string.label_toggle), "▶" to stringResource(R.string.label_confirm))
            } else if (kitchenRunning) {
                listOf("Y" to "KITCHEN", "A" to stringResource(R.string.label_select))
            } else {
                listOf("A" to stringResource(R.string.label_select))
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf("X" to stringResource(R.string.label_settings)),
                rightItems = rightItems
            )
        }
    }

    if (dialogState.isFullScreen) {
        DialogOverlay(
            dialogState = dialogState,
            backgroundImagePath = backgroundImagePath,
            backgroundTint = backgroundTint,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            listVerticalPadding = listVerticalPadding
        )
    }
}
