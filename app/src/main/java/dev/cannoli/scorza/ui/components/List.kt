package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun <T> List(
    items: kotlin.collections.List<T>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onVisibleRangeChanged: ((firstVisible: Int, visibleCount: Int) -> Unit)? = null,
    itemContent: @Composable (index: Int, item: T) -> Unit
) {
    ListScrollEffect(listState, selectedIndex, items.size, onVisibleRangeChanged)

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 2000.dp)
    ) {
        itemsIndexed(items) { index, item ->
            itemContent(index, item)
        }
    }
}
