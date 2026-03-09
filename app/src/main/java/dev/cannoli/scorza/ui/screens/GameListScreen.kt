package dev.cannoli.scorza.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.settings.ScrollSpeed
import dev.cannoli.scorza.ui.components.BottomBar
import dev.cannoli.scorza.ui.components.ConfirmOverlay
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.components.List
import dev.cannoli.scorza.ui.components.MessageOverlay
import dev.cannoli.scorza.ui.components.MissingAppDialog
import dev.cannoli.scorza.ui.components.MissingCoreDialog
import dev.cannoli.scorza.ui.components.PillRow
import dev.cannoli.scorza.ui.components.PillRowText
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.ScreenTitle
import dev.cannoli.scorza.ui.components.pillItemHeight
import dev.cannoli.scorza.ui.components.screenPadding
import dev.cannoli.scorza.ui.theme.GrayText
import dev.cannoli.scorza.ui.theme.LocalCannoliColors
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import kotlinx.coroutines.delay

@Composable
fun GameListScreen(
    viewModel: GameListViewModel,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    scrollSpeed: ScrollSpeed = ScrollSpeed.NORMAL,
    dialogState: DialogState = DialogState.None
) {
    val state by viewModel.state.collectAsState()
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)

    if (dialogState.isFullScreen) {
        DialogOverlay(
            dialogState = dialogState,
            backgroundImagePath = backgroundImagePath,
            backgroundTint = backgroundTint,
            listFontSize = listFontSize,
            listLineHeight = listLineHeight,
            listVerticalPadding = listVerticalPadding
        )
        return
    }

    val selectedGame = state.games.getOrNull(state.selectedIndex)
    val selectedArt: ImageBitmap? = if (selectedGame != null && !selectedGame.isSubfolder) {
        remember(selectedGame.artFile?.absolutePath) {
            selectedGame.artFile?.let { file ->
                try {
                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                } catch (_: Exception) { null }
            }
        }
    } else null

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            val showArt = selectedArt != null
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (showArt) Modifier.fillMaxWidth(0.6f) else Modifier.fillMaxWidth())
                ) {
                    ScreenTitle(
                        text = state.breadcrumb,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (state.games.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.empty_list, state.breadcrumb),
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = listFontSize,
                                    lineHeight = listLineHeight
                                ),
                                color = LocalCannoliColors.current.text
                            )
                        }
                    } else {
                    List(
                        items = state.games,
                        selectedIndex = state.selectedIndex,
                        itemHeight = itemHeight,
                        scrollTarget = state.scrollTarget,
                        onVisibleRangeChanged = { first, count ->
                            viewModel.firstVisibleIndex = first
                            viewModel.pageSize = count
                        }
                    ) { index, game ->
                        GameRow(
                            game = game,
                            isSelected = state.selectedIndex == index,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            scrollSpeed = scrollSpeed,
                            showReorderIcon = state.reorderMode && state.selectedIndex == index,
                            checkState = if (state.multiSelectMode) index in state.checkedIndices else null
                        )
                    }
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

            val actionLabel = if (state.multiSelectMode) {
                stringResource(R.string.label_toggle)
            } else if (selectedGame?.isSubfolder == true || state.isCollectionsList) {
                stringResource(R.string.label_open)
            } else if (state.platformTag == "tools") {
                stringResource(R.string.label_launch)
            } else {
                stringResource(R.string.label_play)
            }
            val rightItems = if (state.games.isEmpty()) {
                emptyList()
            } else if (state.multiSelectMode) {
                listOf("A" to actionLabel, "▶" to stringResource(R.string.label_confirm))
            } else {
                listOf("A" to actionLabel)
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf("B" to stringResource(R.string.label_back)),
                rightItems = rightItems
            )

            when (dialogState) {
                is DialogState.MissingCore -> MissingCoreDialog(dialogState.coreName)
                is DialogState.MissingApp -> MissingAppDialog(dialogState.packageName)
                is DialogState.DeleteConfirm -> ConfirmOverlay(
                    message = stringResource(R.string.dialog_delete_confirm, dialogState.gameName)
                )
                is DialogState.DeleteCollectionConfirm -> ConfirmOverlay(
                    message = stringResource(R.string.dialog_delete_confirm, dialogState.collectionName)
                )
                is DialogState.RenameResult -> MessageOverlay(
                    message = if (dialogState.success) {
                        stringResource(R.string.dialog_rename_success)
                    } else {
                        stringResource(R.string.dialog_rename_failed, dialogState.message)
                    }
                )
                is DialogState.CollectionCreated -> MessageOverlay(
                    message = "${dialogState.collectionName} Created"
                )
                else -> {}
            }
        }
    }
}

@Composable
private fun GameRow(
    game: Game,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp,
    scrollSpeed: ScrollSpeed = ScrollSpeed.NORMAL,
    showReorderIcon: Boolean = false,
    checkState: Boolean? = null
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSize,
        lineHeight = lineHeight
    )
    val scrollState = rememberScrollState()

    val pxPerMs = when (scrollSpeed) {
        ScrollSpeed.SLOW -> 8
        ScrollSpeed.NORMAL -> 4
        ScrollSpeed.FAST -> 2
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

    val colors = LocalCannoliColors.current
    PillRow(isSelected = isSelected, verticalPadding = verticalPadding) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(scrollState)
        ) {
            if (checkState != null) {
                Text(
                    text = if (checkState) "☑" else "☐",
                    style = textStyle,
                    color = if (isSelected) colors.highlightText else colors.text
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (showReorderIcon) {
                Text(
                    text = "↕",
                    style = textStyle,
                    color = if (isSelected) colors.highlightText else colors.text
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (game.isSubfolder) {
                Text(
                    text = "/",
                    style = textStyle,
                    color = if (isSelected) colors.highlightText else GrayText
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = game.displayName,
                style = textStyle,
                color = if (isSelected) colors.highlightText else colors.text,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
