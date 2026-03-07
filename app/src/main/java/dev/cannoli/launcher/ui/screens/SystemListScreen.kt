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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.cannoli.launcher.ui.components.BottomBar
import dev.cannoli.launcher.ui.components.StatusBar
import dev.cannoli.launcher.ui.viewmodel.SystemListViewModel
import dev.cannoli.launcher.ui.viewmodel.SystemListViewModel.ListItem

private val screenPadding = 20.dp
private val pillInternalH = 14.dp

@Composable
fun SystemListScreen(
    viewModel: SystemListViewModel,
    showBatteryPercentage: Boolean = false,
    use24hTime: Boolean = false,
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenPadding)
    ) {
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            StatusBar(
                showBatteryPercentage = showBatteryPercentage,
                use24hTime = use24hTime
            )
        }

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
                        isSelected = state.selectedIndex == index
                    )
                    is ListItem.CollectionItem -> MenuRow(
                        label = item.name,
                        isSelected = state.selectedIndex == index
                    )
                    is ListItem.ToolItem -> MenuRow(
                        label = item.name,
                        isSelected = state.selectedIndex == index
                    )
                    is ListItem.PortItem -> MenuRow(
                        label = item.name,
                        isSelected = state.selectedIndex == index
                    )
                    is ListItem.Divider -> {}
                }
            }
        }

        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf("POWER" to "SLEEP"),
            rightItems = listOf("A" to "OPEN")
        )
    }
}

@Composable
private fun MenuRow(
    label: String,
    isSelected: Boolean
) {
    if (isSelected) {
        // Pill wraps text, text inside aligns with unselected text
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = pillInternalH, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.Black
            )
        }
    } else {
        // Unselected text aligns with text inside the pill
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .padding(start = pillInternalH, top = 8.dp, bottom = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
        }
    }
}
