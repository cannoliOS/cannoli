package dev.cannoli.scorza.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardInputState
import dev.cannoli.scorza.ui.theme.GrayText

@Composable
fun DialogOverlay(
    dialogState: DialogState,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    when (dialogState) {
        is DialogState.ContextMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = dialogState.gameName,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = emptyList()
            ) {
                List(
                    items = dialogState.options,
                    selectedIndex = dialogState.selectedOption,
                    itemHeight = itemHeight
                ) { index, option ->
                    PillRowText(
                        label = option,
                        isSelected = dialogState.selectedOption == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.BulkContextMenu -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = "${dialogState.gamePaths.size} Selected",
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = emptyList()
            ) {
                List(
                    items = dialogState.options,
                    selectedIndex = dialogState.selectedOption,
                    itemHeight = itemHeight
                ) { index, option ->
                    PillRowText(
                        label = option,
                        isSelected = dialogState.selectedOption == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.CollectionPicker -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = dialogState.title,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                rightBottomItems = listOf(
                    "X" to stringResource(R.string.label_new),
                    "▶" to stringResource(R.string.label_confirm)
                )
            ) {
                if (dialogState.collections.isEmpty()) {
                    Text(
                        text = "No collections",
                        style = MaterialTheme.typography.bodyLarge,
                        color = GrayText,
                        modifier = Modifier.padding(start = 14.dp)
                    )
                } else {
                    List(
                        items = dialogState.collections,
                        selectedIndex = dialogState.selectedIndex,
                        itemHeight = itemHeight
                    ) { index, collection ->
                        PillRowText(
                            label = collection,
                            isSelected = dialogState.selectedIndex == index,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding,
                            checkState = index in dialogState.checkedIndices
                        )
                    }
                }
            }
        }

        is DialogState.CoreMappingList -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.setting_core_mapping),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = true,
                rightBottomItems = listOf("A" to stringResource(R.string.label_select), "▶" to stringResource(R.string.label_save))
            ) {
                List(
                    items = dialogState.mappings,
                    selectedIndex = dialogState.selectedIndex,
                    itemHeight = itemHeight
                ) { index, entry ->
                    val value = if (entry.runnerLabel.isNotEmpty())
                        "${entry.coreDisplayName} (${entry.runnerLabel})"
                    else entry.coreDisplayName
                    PillRowKeyValue(
                        label = entry.platformName,
                        value = value,
                        isSelected = dialogState.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }
        }

        is DialogState.CorePicker -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = dialogState.platformName,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = true,
                rightBottomItems = listOf("A" to stringResource(R.string.label_select))
            ) {
                if (dialogState.cores.isEmpty()) {
                    Text(
                        text = "No compatible cores found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = GrayText,
                        modifier = Modifier.padding(start = 14.dp)
                    )
                } else {
                    List(
                        items = dialogState.cores,
                        selectedIndex = dialogState.selectedIndex,
                        itemHeight = itemHeight
                    ) { index, option ->
                        PillRowText(
                            label = "${option.displayName} (${option.runnerLabel})",
                            isSelected = dialogState.selectedIndex == index,
                            fontSize = listFontSize,
                            lineHeight = listLineHeight,
                            verticalPadding = listVerticalPadding
                        )
                    }
                }
            }
        }

        is DialogState.AppPicker -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = dialogState.title,
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = true,
                rightBottomItems = listOf("\uDB81\uDC0A" to stringResource(R.string.label_confirm))
            ) {
                List(
                    items = dialogState.apps,
                    selectedIndex = dialogState.selectedIndex,
                    itemHeight = itemHeight
                ) { index, app ->
                    PillRowText(
                        label = app,
                        isSelected = dialogState.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        checkState = index in dialogState.checkedIndices
                    )
                }
            }
        }

        is DialogState.ColorList -> {
            ListDialogScreen(
                backgroundImagePath = backgroundImagePath,
                backgroundTint = backgroundTint,
                title = stringResource(R.string.setting_colors),
                listFontSize = listFontSize,
                listLineHeight = listLineHeight,
                fullWidth = true,
                rightBottomItems = listOf("A" to stringResource(R.string.label_select))
            ) {
                List(
                    items = dialogState.colors,
                    selectedIndex = dialogState.selectedIndex,
                    itemHeight = itemHeight
                ) { index, entry ->
                    PillRowKeyValue(
                        label = entry.label,
                        value = entry.hex.uppercase(),
                        isSelected = dialogState.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        swatchColor = androidx.compose.ui.graphics.Color(entry.color.toInt())
                    )
                }
            }
        }

        is DialogState.ColorPicker -> {
            ColorPickerOverlay(
                selectedRow = dialogState.selectedRow,
                selectedCol = dialogState.selectedCol,
                currentColor = dialogState.currentColor
            )
        }

        is DialogState.HexColorInput -> {
            HexColorInputOverlay(
                currentHex = dialogState.currentHex,
                selectedIndex = dialogState.selectedIndex
            )
        }

        is DialogState.RenameInput,
        is DialogState.NewCollectionInput,
        is DialogState.CollectionRenameInput -> {
            val ks = dialogState as KeyboardInputState
            KeyboardOverlay(
                text = ks.currentName,
                cursorPos = ks.cursorPos,
                keyRow = ks.keyRow,
                keyCol = ks.keyCol,
                caps = ks.caps,
                symbols = ks.symbols
            )
        }

        DialogState.About -> {
            AboutOverlay()
        }

        else -> {}
    }
}

@Composable
private fun ListDialogScreen(
    backgroundImagePath: String?,
    backgroundTint: Int,
    title: String,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    fullWidth: Boolean = false,
    rightBottomItems: List<Pair<String, String>>,
    content: @Composable () -> Unit
) {
    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(screenPadding)
        ) {
            Column(
                modifier = Modifier
                    .then(if (fullWidth) Modifier.fillMaxSize() else Modifier.fillMaxWidth(0.65f))
                    .padding(bottom = 48.dp)
            ) {
                ScreenTitle(
                    text = title,
                    fontSize = listFontSize,
                    lineHeight = listLineHeight
                )
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf("B" to stringResource(R.string.label_back)),
                rightItems = rightBottomItems
            )
        }
    }
}
