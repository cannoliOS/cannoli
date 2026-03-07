package dev.cannoli.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.launcher.ui.components.BottomBar
import dev.cannoli.launcher.ui.components.ScreenBackground
import dev.cannoli.launcher.ui.viewmodel.SystemListViewModel
import dev.cannoli.launcher.ui.viewmodel.SystemListViewModel.ListItem

private val screenPadding = 20.dp
private val pillInternalH = 14.dp

@Composable
fun SystemListScreen(
    viewModel: SystemListViewModel,
    backgroundImagePath: String? = null,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    onPlatformSelected: (String) -> Unit,
    onCollectionSelected: (String) -> Unit,
    onToolSelected: (String) -> Unit,
    onPortSelected: (String) -> Unit,
    onSettingsRequested: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.selectedIndex) {
        if (state.items.isNotEmpty()) {
            listState.animateScrollToItem(state.selectedIndex.coerceAtLeast(0))
        }
    }

    ScreenBackground(backgroundImagePath = backgroundImagePath) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenPadding)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth(0.65f)
                .padding(top = 4.dp, bottom = 48.dp)
        ) {
            itemsIndexed(state.items) { index, item ->
                when (item) {
                    is ListItem.PlatformItem -> MenuRow(
                        label = item.platform.displayName,
                        isSelected = state.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                    is ListItem.CollectionItem -> MenuRow(
                        label = item.name,
                        isSelected = state.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                    is ListItem.ToolItem -> MenuRow(
                        label = item.name,
                        isSelected = state.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                    is ListItem.PortItem -> MenuRow(
                        label = item.name,
                        isSelected = state.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                    is ListItem.Divider -> {}
                }
            }
        }

        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf("\uDB81\uDC25" to stringResource(dev.cannoli.launcher.R.string.label_sleep)),
            rightItems = listOf("A" to stringResource(dev.cannoli.launcher.R.string.label_select))
        )
    }
    }
}

@Composable
private fun MenuRow(
    label: String,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSize,
        lineHeight = lineHeight
    )

    if (isSelected) {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = pillInternalH, vertical = verticalPadding)
        ) {
            Text(
                text = label,
                style = textStyle,
                color = Color.Black
            )
        }
    } else {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .padding(start = pillInternalH, top = verticalPadding, bottom = verticalPadding)
        ) {
            Text(
                text = label,
                style = textStyle,
                color = Color.White
            )
        }
    }
}
