package dev.cannoli.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import dev.cannoli.launcher.model.Game
import dev.cannoli.launcher.ui.components.BottomBar
import dev.cannoli.launcher.ui.components.MissingAppDialog
import dev.cannoli.launcher.ui.components.MissingCoreDialog
import dev.cannoli.launcher.ui.components.StatusBar
import dev.cannoli.launcher.ui.theme.GrayText
import dev.cannoli.launcher.ui.viewmodel.GameListViewModel

private val screenPadding = 20.dp
private val pillInternalH = 14.dp

@Composable
fun GameListScreen(
    viewModel: GameListViewModel,
    showBatteryPercentage: Boolean = false,
    use24hTime: Boolean = false,
    dialogState: DialogState = DialogState.None,
    onBack: () -> Unit,
    onPreviousPlatform: () -> Unit,
    onNextPlatform: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(state.selectedIndex) {
        if (state.games.isNotEmpty()) {
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
                .fillMaxSize()
                .padding(top = 4.dp, bottom = 48.dp)
        ) {
            itemsIndexed(state.games) { index, game ->
                GameRow(
                    game = game,
                    isSelected = state.selectedIndex == index
                )
            }
        }

        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf("B" to "BACK"),
            rightItems = listOf("A" to "OPEN")
        )

        when (dialogState) {
            is DialogState.MissingCore -> MissingCoreDialog(dialogState.coreName)
            is DialogState.MissingApp -> MissingAppDialog(dialogState.packageName)
            DialogState.None -> {}
        }
    }
}

sealed class DialogState {
    object None : DialogState()
    data class MissingCore(val coreName: String) : DialogState()
    data class MissingApp(val packageName: String) : DialogState()
}

@Composable
private fun GameRow(
    game: Game,
    isSelected: Boolean
) {
    val content = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (game.isSubfolder) {
                Text(
                    text = "/",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) Color.Black else GrayText
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = game.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isSelected) Color.Black else Color.White
            )
        }
    }

    if (isSelected) {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = pillInternalH, vertical = 8.dp)
        ) {
            content()
        }
    } else {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .padding(start = pillInternalH, top = 8.dp, bottom = 8.dp)
        ) {
            content()
        }
    }
}
