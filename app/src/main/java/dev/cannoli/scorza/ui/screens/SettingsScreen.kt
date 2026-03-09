package dev.cannoli.scorza.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import dev.cannoli.scorza.ui.components.DialogOverlay
import dev.cannoli.scorza.ui.components.List
import dev.cannoli.scorza.ui.components.PillRowKeyValue
import dev.cannoli.scorza.ui.components.PillRowText
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.components.ScreenTitle
import dev.cannoli.scorza.ui.components.pillItemHeight
import dev.cannoli.scorza.ui.components.screenPadding
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    backgroundImagePath: String? = null,
    backgroundTint: Int = 0,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
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

    ScreenBackground(backgroundImagePath = backgroundImagePath, backgroundTint = backgroundTint) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(screenPadding)
    ) {
        if (state.inSubList) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp)
            ) {
                val categoryLabel = state.categories.getOrNull(state.categoryIndex)?.labelRes
                if (categoryLabel != null) {
                    ScreenTitle(
                        text = stringResource(categoryLabel),
                        fontSize = listFontSize,
                        lineHeight = listLineHeight
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                List(
                    items = state.items,
                    selectedIndex = state.selectedIndex,
                    itemHeight = itemHeight
                ) { index, item ->
                    PillRowKeyValue(
                        label = stringResource(item.labelRes),
                        value = item.valueText ?: item.valueRes?.let { stringResource(it) } ?: "",
                        isSelected = state.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding,
                        swatchColor = item.swatchColor
                    )
                }
            }

            val selectedItem = state.items.getOrNull(state.selectedIndex)
            val isColorItem = selectedItem?.key?.startsWith("color_") == true
            val isEditableItem = selectedItem?.isEditable == true
            val leftItems = if (isEditableItem) {
                listOf("B" to stringResource(R.string.label_back))
            } else {
                listOf("B" to stringResource(R.string.label_back), "◀▶" to stringResource(R.string.label_change))
            }
            val rightItems = if (isColorItem) {
                listOf("A" to stringResource(R.string.label_select), "\uDB81\uDC0A" to stringResource(R.string.label_save))
            } else {
                listOf("\uDB81\uDC0A" to stringResource(R.string.label_save))
            }
            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = leftItems,
                rightItems = rightItems
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp)
            ) {
                ScreenTitle(
                    text = stringResource(R.string.settings_title),
                    fontSize = listFontSize,
                    lineHeight = listLineHeight
                )
                Spacer(modifier = Modifier.height(8.dp))
                List(
                    items = state.categories,
                    selectedIndex = state.categoryIndex,
                    itemHeight = itemHeight
                ) { index, category ->
                    PillRowText(
                        label = stringResource(category.labelRes),
                        isSelected = state.categoryIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf("B" to stringResource(R.string.label_back)),
                rightItems = listOf("A" to stringResource(R.string.label_select))
            )
        }
    }
    }
}
