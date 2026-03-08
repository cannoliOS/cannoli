package dev.cannoli.scorza.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.scanner.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SystemListViewModel(
    private val scanner: FileScanner
) : ViewModel() {

    sealed class ListItem {
        data class FavoritesItem(val count: Int) : ListItem()
        data class CollectionsFolder(val count: Int) : ListItem()
        data class PlatformItem(val platform: Platform) : ListItem()
        data class CollectionItem(val name: String, val count: Int) : ListItem()
        data class ToolsFolder(val name: String, val count: Int) : ListItem()
        data class PortsFolder(val name: String, val count: Int) : ListItem()
        data class Divider(val label: String? = null) : ListItem()
    }

    data class State(
        val items: List<ListItem> = emptyList(),
        val platforms: List<Platform> = emptyList(),
        val selectedIndex: Int = 0,
        val scrollTarget: Int = 0,
        val isLoading: Boolean = true,
        val reorderMode: Boolean = false,
        val reorderOriginalIndex: Int = -1,
        val multiSelectMode: Boolean = false,
        val checkedIndices: Set<Int> = emptySet()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    var pageSize: Int = 10
    var firstVisibleIndex: Int = 0

    fun scan(showTools: Boolean = false, showPorts: Boolean = false, toolsName: String = "Tools", portsName: String = "Ports") {
        val prev = _state.value
        val prevItemCount = prev.items.size
        val prevSelectedIndex = prev.selectedIndex
        val prevFirstVisible = firstVisibleIndex

        viewModelScope.launch(Dispatchers.IO) {
            val platforms = scanner.scanPlatforms()
            val collections = scanner.scanCollections().map { it.name to it.entries.size }
            val tools = scanner.scanTools()
            val ports = scanner.scanPorts()

            val items = mutableListOf<ListItem>()

            val favorites = collections.find { it.first.equals("Favorites", ignoreCase = true) }
            val otherCollections = collections.filter { !it.first.equals("Favorites", ignoreCase = true) }

            if (favorites != null && favorites.second > 0) {
                items.add(ListItem.FavoritesItem(favorites.second))
            }

            if (otherCollections.isNotEmpty()) {
                items.add(ListItem.CollectionsFolder(otherCollections.size))
            }

            val reorderableItems = mutableListOf<ListItem>()
            platforms.forEach { reorderableItems.add(ListItem.PlatformItem(it)) }
            if (showPorts && ports.isNotEmpty()) {
                reorderableItems.add(ListItem.PortsFolder(portsName, ports.size))
            }
            if (showTools && tools.isNotEmpty()) {
                reorderableItems.add(ListItem.ToolsFolder(toolsName, tools.size))
            }
            val ordered = applyCustomOrder(reorderableItems, scanner.loadPlatformOrder())
            items.addAll(ordered)

            val sameSize = items.size == prevItemCount && prevItemCount > 0
            val selectableIndices = items.indices.filter { items[it] !is ListItem.Divider }
            val (safeIndex, scrollTo) = if (sameSize) {
                val idx = when {
                    selectableIndices.isEmpty() -> 0
                    prevSelectedIndex in selectableIndices -> prevSelectedIndex
                    else -> selectableIndices.firstOrNull { it >= prevSelectedIndex } ?: selectableIndices.last()
                }
                idx to prevFirstVisible.coerceAtMost(items.lastIndex.coerceAtLeast(0))
            } else {
                val idx = if (selectableIndices.isNotEmpty()) selectableIndices.first() else 0
                idx to 0
            }
            _state.value = State(
                items = items,
                platforms = platforms,
                selectedIndex = safeIndex,
                scrollTarget = scrollTo,
                isLoading = false
            )
        }
    }

    fun moveSelection(delta: Int) {
        val current = _state.value
        val selectableIndices = current.items.indices.filter { current.items[it] !is ListItem.Divider }
        if (selectableIndices.isEmpty()) return

        val currentPos = selectableIndices.indexOf(current.selectedIndex)
        val raw = if (currentPos == -1) 0 else currentPos + delta
        val targetPos = ((raw % selectableIndices.size) + selectableIndices.size) % selectableIndices.size

        _state.value = current.copy(selectedIndex = selectableIndices[targetPos])
    }

    fun pageJump(delta: Int) {
        val current = _state.value
        val selectableIndices = current.items.indices.filter { current.items[it] !is ListItem.Divider }
        if (selectableIndices.isEmpty()) return

        val currentPos = selectableIndices.indexOf(current.selectedIndex)
        val lastItemIndex = selectableIndices.last()

        if (delta > 0) {
            val lastVisible = firstVisibleIndex + pageSize - 1
            if (lastVisible >= lastItemIndex) {
                if (current.selectedIndex < lastItemIndex) {
                    _state.value = current.copy(selectedIndex = lastItemIndex)
                }
            } else {
                val targetPos = (if (currentPos == -1) 0 else currentPos + delta).coerceAtMost(selectableIndices.lastIndex)
                _state.value = current.copy(selectedIndex = selectableIndices[targetPos])
            }
        } else {
            val firstItemIndex = selectableIndices.first()
            if (firstVisibleIndex <= firstItemIndex) {
                if (current.selectedIndex > firstItemIndex) {
                    _state.value = current.copy(selectedIndex = firstItemIndex)
                }
            } else {
                val targetPos = (if (currentPos == -1) 0 else currentPos + delta).coerceAtLeast(0)
                _state.value = current.copy(selectedIndex = selectableIndices[targetPos])
            }
        }
    }

    fun getSelectedItem(): ListItem? {
        val current = _state.value
        return current.items.getOrNull(current.selectedIndex)
    }

    fun getSelectedPlatformTag(): String? {
        return (getSelectedItem() as? ListItem.PlatformItem)?.platform?.tag
    }

    fun getPlatformTags(): List<String> =
        _state.value.items.filterIsInstance<ListItem.PlatformItem>().map { it.platform.tag }

    fun enterReorderMode() {
        val current = _state.value
        val item = current.items.getOrNull(current.selectedIndex) ?: return
        if (!item.isReorderable()) return
        _state.value = current.copy(reorderMode = true, reorderOriginalIndex = current.selectedIndex)
    }

    fun isReorderMode(): Boolean = _state.value.reorderMode

    fun reorderMoveUp() {
        val current = _state.value
        if (!current.reorderMode) return
        val idx = current.selectedIndex
        val items = current.items.toMutableList()
        val prevSelectable = (idx - 1 downTo 0).firstOrNull { items[it].isReorderable() }
            ?: return
        items[idx] = items[prevSelectable].also { items[prevSelectable] = items[idx] }
        _state.value = current.copy(items = items, selectedIndex = prevSelectable)
    }

    fun reorderMoveDown() {
        val current = _state.value
        if (!current.reorderMode) return
        val idx = current.selectedIndex
        val items = current.items.toMutableList()
        val nextSelectable = (idx + 1..items.lastIndex).firstOrNull { items[it].isReorderable() }
            ?: return
        items[idx] = items[nextSelectable].also { items[nextSelectable] = items[idx] }
        _state.value = current.copy(items = items, selectedIndex = nextSelectable)
    }

    fun confirmReorder() {
        val current = _state.value
        if (!current.reorderMode) return
        val tags = current.items.mapNotNull { it.orderTag() }
        viewModelScope.launch(Dispatchers.IO) {
            scanner.savePlatformOrder(tags)
        }
        _state.value = current.copy(reorderMode = false, reorderOriginalIndex = -1)
    }

    fun cancelReorder(showTools: Boolean = false, showPorts: Boolean = false, toolsName: String = "Tools", portsName: String = "Ports") {
        val current = _state.value
        if (!current.reorderMode) return
        scan(showTools, showPorts, toolsName, portsName)
    }

    fun enterMultiSelect() {
        val current = _state.value
        if (current.reorderMode || current.multiSelectMode) return
        _state.value = current.copy(
            multiSelectMode = true,
            checkedIndices = setOf(current.selectedIndex)
        )
    }

    fun isMultiSelectMode(): Boolean = _state.value.multiSelectMode

    fun toggleChecked() {
        val current = _state.value
        if (!current.multiSelectMode) return
        val idx = current.selectedIndex
        val newChecked = if (idx in current.checkedIndices) {
            current.checkedIndices - idx
        } else {
            current.checkedIndices + idx
        }
        _state.value = current.copy(checkedIndices = newChecked)
    }

    fun confirmMultiSelect(): Set<Int> {
        val current = _state.value
        val checked = current.checkedIndices
        _state.value = current.copy(multiSelectMode = false, checkedIndices = emptySet())
        return checked
    }

    fun cancelMultiSelect() {
        val current = _state.value
        _state.value = current.copy(multiSelectMode = false, checkedIndices = emptySet())
    }

    private fun ListItem.isReorderable(): Boolean = this is ListItem.PlatformItem || this is ListItem.ToolsFolder || this is ListItem.PortsFolder

    private fun ListItem.orderTag(): String? = when (this) {
        is ListItem.PlatformItem -> platform.tag
        is ListItem.ToolsFolder -> TAG_TOOLS
        is ListItem.PortsFolder -> TAG_PORTS
        else -> null
    }

    private fun applyCustomOrder(items: List<ListItem>, order: List<String>): List<ListItem> {
        if (order.isEmpty()) return items
        val byTag = items.associateBy { it.orderTag() }
        val ordered = mutableListOf<ListItem>()
        for (tag in order) {
            byTag[tag]?.let { ordered.add(it) }
        }
        val remaining = items.filter { it.orderTag() !in order }
        return ordered + remaining
    }

    companion object {
        const val TAG_TOOLS = "__tools__"
        const val TAG_PORTS = "__ports__"
    }
}
