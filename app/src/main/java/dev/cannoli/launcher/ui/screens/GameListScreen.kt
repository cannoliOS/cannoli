package dev.cannoli.launcher.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import dev.cannoli.launcher.R
import dev.cannoli.launcher.model.Game
import dev.cannoli.launcher.settings.ScrollSpeed
import dev.cannoli.launcher.ui.components.BottomBar
import dev.cannoli.launcher.ui.components.ScreenBackground
import dev.cannoli.launcher.ui.components.CollectionPickerOverlay
import dev.cannoli.launcher.ui.components.ContextMenuOverlay
import dev.cannoli.launcher.ui.components.DeleteConfirmOverlay
import dev.cannoli.launcher.ui.components.MissingAppDialog
import dev.cannoli.launcher.ui.components.MissingCoreDialog
import dev.cannoli.launcher.ui.components.RenameOverlay
import dev.cannoli.launcher.ui.components.RenameResultOverlay
import dev.cannoli.launcher.ui.theme.GrayText
import dev.cannoli.launcher.ui.viewmodel.GameListViewModel

private val screenPadding = 20.dp
private val pillInternalH = 14.dp

@Composable
fun GameListScreen(
    viewModel: GameListViewModel,
    backgroundImagePath: String? = null,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    boxArtEnabled: Boolean = true,
    scrollSpeed: ScrollSpeed = ScrollSpeed.NORMAL,
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

    val selectedGame = state.games.getOrNull(state.selectedIndex)
    val selectedArt: ImageBitmap? = if (boxArtEnabled && selectedGame != null && !selectedGame.isSubfolder) {
        remember(selectedGame.artFile?.absolutePath) {
            selectedGame.artFile?.let { file ->
                try {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } catch (_: Exception) { null }
            }
        }
    } else null

    ScreenBackground(backgroundImagePath = backgroundImagePath) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenPadding)
    ) {
        val showArt = boxArtEnabled && selectedArt != null
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp, bottom = 48.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .then(if (showArt) Modifier.fillMaxWidth(0.6f) else Modifier.fillMaxWidth())
            ) {
                itemsIndexed(state.games) { index, game ->
                    GameRow(
                        game = game,
                        isSelected = state.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        scrollSpeed = scrollSpeed
                    )
                }
            }

            if (showArt) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth()
                        .padding(start = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = selectedArt!!,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }

        val actionLabel = if (selectedGame?.isSubfolder == true) {
            stringResource(R.string.label_open)
        } else {
            stringResource(R.string.label_play)
        }
        BottomBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            leftItems = listOf("B" to stringResource(R.string.label_back)),
            rightItems = listOf("A" to actionLabel)
        )

        when (dialogState) {
            is DialogState.MissingCore -> MissingCoreDialog(dialogState.coreName)
            is DialogState.MissingApp -> MissingAppDialog(dialogState.packageName)
            is DialogState.ContextMenu -> ContextMenuOverlay(dialogState)
            is DialogState.DeleteConfirm -> DeleteConfirmOverlay(dialogState.gameName)
            is DialogState.CollectionPicker -> CollectionPickerOverlay(dialogState)
            is DialogState.RenameInput -> RenameOverlay(dialogState)
            is DialogState.RenameResult -> RenameResultOverlay(dialogState)
            DialogState.None -> {}
        }
    }
    }
}

sealed class DialogState {
    object None : DialogState()
    data class MissingCore(val coreName: String) : DialogState()
    data class MissingApp(val packageName: String) : DialogState()
    data class ContextMenu(val gameName: String, val selectedOption: Int = 0, val options: List<String> = listOf("Rename", "Delete", "Add to Collection")) : DialogState()
    data class DeleteConfirm(val gameName: String) : DialogState()
    data class CollectionPicker(val gamePath: String, val collections: List<String>, val selectedIndex: Int = 0, val showNewInput: Boolean = false) : DialogState()
    data class RenameInput(val gameName: String, val currentName: String, val cursorPos: Int = 0) : DialogState()
    data class RenameResult(val success: Boolean, val message: String) : DialogState()
}

@Composable
private fun GameRow(
    game: Game,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
    scrollSpeed: ScrollSpeed = ScrollSpeed.NORMAL
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSize,
        lineHeight = lineHeight
    )
    val scrollState = rememberScrollState()

    val pxPerMs = when (scrollSpeed) {
        ScrollSpeed.SLOW -> 8
        ScrollSpeed.NORMAL -> 12
        ScrollSpeed.FAST -> 18
    }

    LaunchedEffect(isSelected, scrollSpeed) {
        scrollState.scrollTo(0)
        if (isSelected) {
            delay(600)
            while (true) {
                val max = scrollState.maxValue
                if (max <= 0) break
                val duration = (max * pxPerMs).coerceIn(500, 8000)
                scrollState.animateScrollTo(
                    max,
                    animationSpec = tween(durationMillis = duration, easing = LinearEasing)
                )
                delay(800)
                scrollState.animateScrollTo(
                    0,
                    animationSpec = tween(durationMillis = duration, easing = LinearEasing)
                )
                delay(800)
            }
        }
    }

    val content = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(scrollState)
        ) {
            if (game.isSubfolder) {
                Text(
                    text = "/",
                    style = textStyle,
                    color = if (isSelected) Color.Black else GrayText
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = game.displayName,
                style = textStyle,
                color = if (isSelected) Color.Black else Color.White,
                maxLines = 1,
                softWrap = false
            )
        }
    }

    if (isSelected) {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = pillInternalH, vertical = verticalPadding)
        ) {
            content()
        }
    } else {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .padding(start = pillInternalH, top = verticalPadding, bottom = verticalPadding)
        ) {
            content()
        }
    }
}
