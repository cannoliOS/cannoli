package dev.cannoli.scorza.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ListScrollEffect(
    listState: LazyListState,
    selectedIndex: Int,
    itemCount: Int,
    onVisibleRangeChanged: ((firstVisible: Int, visibleCount: Int) -> Unit)? = null
) {
    var previousIndex by remember { mutableIntStateOf(selectedIndex) }

    LaunchedEffect(itemCount) {
        previousIndex = selectedIndex
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
        val delta = index - previousIndex

        val fullyVisibleCount = listState.layoutInfo.visibleItemsInfo.count { info ->
            info.offset >= 0 &&
                info.offset + info.size <= listState.layoutInfo.viewportEndOffset
        }.coerceAtLeast(1)

        if (delta == 0) {
            // No movement
        } else if (delta == 1 || delta == -1) {
            // Single step
            if (delta > 0) {
                val targetFirst = (index - fullyVisibleCount + 1).coerceAtLeast(0)
                if (targetFirst > listState.firstVisibleItemIndex) {
                    listState.scrollToItem(targetFirst)
                }
            } else {
                if (index < listState.firstVisibleItemIndex) {
                    listState.scrollToItem(index)
                }
            }
        } else {
            // Jump (page or wrap) — only scroll if enough off-screen items exist
            val lastVisibleIdx = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val firstVisibleIdx = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: 0

            if (delta > 0) {
                val offScreenBelow = (itemCount - 1) - lastVisibleIdx
                if (offScreenBelow >= fullyVisibleCount) {
                    listState.scrollToItem(index)
                }
            } else {
                val offScreenAbove = firstVisibleIdx
                if (offScreenAbove >= fullyVisibleCount) {
                    listState.scrollToItem(index)
                }
            }
        }

        previousIndex = index
    }
}
