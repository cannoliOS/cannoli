package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal var lastVisibleCount = 10
internal var lastFirstVisibleIndex = 0

@Composable
fun ListScrollEffect(
    listState: LazyListState,
    selectedIndex: Int,
    itemCount: Int,
    scrollTarget: Int = -1,
    onVisibleRangeChanged: ((firstVisible: Int, visibleCount: Int) -> Unit)? = null
) {
    LaunchedEffect(itemCount, scrollTarget) {
        if (itemCount > 0 && scrollTarget >= 0) {
            listState.scrollToItem(scrollTarget.coerceIn(0, itemCount - 1))
        }
    }

    if (onVisibleRangeChanged != null) {
        LaunchedEffect(listState) {
            snapshotFlow {
                val fullyVisible = listState.layoutInfo.visibleItemsInfo.filter { info ->
                    info.offset >= 0 &&
                        info.offset + info.size <= listState.layoutInfo.viewportEndOffset
                }
                val first = fullyVisible.firstOrNull()?.index ?: 0
                val count = fullyVisible.size
                first to count
            }.distinctUntilChanged().collect { (first, count) ->
                if (count > 0) onVisibleRangeChanged(first, count)
            }
        }
    }

    LaunchedEffect(selectedIndex) {
        if (itemCount == 0) return@LaunchedEffect
        if (listState.layoutInfo.visibleItemsInfo.isEmpty()) return@LaunchedEffect

        val index = selectedIndex.coerceAtLeast(0)
        val fullyVisible = listState.layoutInfo.visibleItemsInfo.filter { info ->
            info.offset >= 0 &&
                info.offset + info.size <= listState.layoutInfo.viewportEndOffset
        }
        val fullyVisibleCount = fullyVisible.size.coerceAtLeast(1)
        lastVisibleCount = fullyVisibleCount
        val firstFullyVisible = fullyVisible.firstOrNull()?.index ?: 0
        lastFirstVisibleIndex = firstFullyVisible
        val lastFullyVisible = fullyVisible.lastOrNull()?.index ?: 0

        if (index < firstFullyVisible) {
            listState.scrollToItem(index)
        } else if (index > lastFullyVisible) {
            val targetFirst = (index - fullyVisibleCount + 1).coerceAtLeast(0)
            listState.scrollToItem(targetFirst)
        }
    }
}
