package dev.cannoli.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.cannoli.launcher.R
import dev.cannoli.launcher.ui.components.BottomBar
import dev.cannoli.launcher.ui.theme.GrayText
import dev.cannoli.launcher.ui.viewmodel.SettingsViewModel

private val screenPadding = 20.dp
private val pillInternalH = 14.dp

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    listFontSize: TextUnit = 22.sp,
    listLineHeight: TextUnit = 32.sp,
    listVerticalPadding: Dp = 8.dp,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    val scrollIndex = if (state.inSubList) state.selectedIndex else state.categoryIndex
    LaunchedEffect(scrollIndex, state.inSubList) {
        listState.scrollToItem(scrollIndex.coerceAtLeast(0))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(screenPadding)
    ) {
        if (state.inSubList) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp, bottom = 48.dp)
            ) {
                itemsIndexed(state.items) { index, item ->
                    SettingsRow(
                        label = stringResource(item.labelRes),
                        value = item.valueText ?: item.valueRes?.let { stringResource(it) } ?: "",
                        isSelected = state.selectedIndex == index,
                        fontSize = listFontSize,
                        lineHeight = listLineHeight,
                        verticalPadding = listVerticalPadding
                    )
                }
            }

            BottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                leftItems = listOf("B" to stringResource(R.string.label_back), "◀▶" to stringResource(R.string.label_change)),
                rightItems = listOf("\uDB81\uDC0A" to stringResource(R.string.label_save))
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp, bottom = 48.dp)
            ) {
                itemsIndexed(state.categories) { index, category ->
                    MenuRow(
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
            Text(text = label, style = textStyle, color = Color.Black)
        }
    } else {
        Box(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .padding(start = pillInternalH, top = verticalPadding, bottom = verticalPadding)
        ) {
            Text(text = label, style = textStyle, color = Color.White)
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    isSelected: Boolean,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    verticalPadding: Dp
) {
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = fontSize,
        lineHeight = lineHeight
    )
    val valueStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = (fontSize.value * 0.72f).sp
    )

    if (isSelected) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .padding(horizontal = pillInternalH, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = textStyle,
                color = Color.Black,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = valueStyle,
                color = Color.Black.copy(alpha = 0.5f),
                maxLines = 1
            )
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .padding(start = pillInternalH, top = verticalPadding, bottom = verticalPadding, end = pillInternalH),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = textStyle,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = valueStyle,
                color = GrayText,
                maxLines = 1
            )
        }
    }
}
