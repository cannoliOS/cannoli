package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun <T> List(
    items: List<T>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    itemHeight: Dp = Dp.Unspecified,
    scrollTarget: Int = 0,
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = scrollTarget.coerceAtLeast(0)),
    reorderMode: Boolean = false,
    onVisibleRangeChanged: ((firstVisible: Int, visibleCount: Int, isViewportFull: Boolean) -> Unit)? = null,
    key: ((index: Int, item: T) -> Any)? = null,
    itemContent: @Composable (index: Int, item: T) -> Unit
) {
    ListScrollEffect(listState, selectedIndex, items.size, scrollTarget, reorderMode, onVisibleRangeChanged)

    val listModifier = if (itemHeight != Dp.Unspecified) {
        modifier.layout { measurable, constraints ->
            val itemPx = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.size > 0 }?.size
                ?: itemHeight.roundToPx()
            val fullItems = constraints.maxHeight / itemPx
            val heightPx = fullItems * itemPx
            val placeable = measurable.measure(
                constraints.copy(maxHeight = heightPx, minHeight = 0)
            )
            layout(placeable.width, heightPx) {
                placeable.place(0, 0)
            }
        }
    } else {
        modifier
    }

    LazyColumn(
        state = listState,
        modifier = listModifier,
        contentPadding = PaddingValues(bottom = 2000.dp)
    ) {
        itemsIndexed(items, key = key) { index, item ->
            itemContent(index, item)
        }
    }
}
