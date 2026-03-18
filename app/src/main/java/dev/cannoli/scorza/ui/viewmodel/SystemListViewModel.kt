package dev.cannoli.scorza.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cannoli.scorza.model.Platform
import dev.cannoli.scorza.scanner.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
    }

    data class State(
        val items: List<ListItem> = emptyList(),
        val platforms: List<Platform> = emptyList(),
        val selectedIndex: Int = 0,
        val scrollTarget: Int = 0,
        val isLoading: Boolean = true,
        val reorderMode: Boolean = false,
        val reorderOriginalIndex: Int = -1
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    var firstVisibleIndex: Int = 0
    private var savedPosition: Pair<Int, Int>? = null

    fun savePosition() {
        savedPosition = _state.value.selectedIndex to firstVisibleIndex
    }

    fun scan(showTools: Boolean = false, showPorts: Boolean = false, showEmpty: Boolean = false, toolsName: String = "Tools", portsName: String = "Ports") {
        val prev = _state.value
        val prevItemCount = prev.items.size
        val restored = savedPosition
        savedPosition = null
        val prevSelectedIndex = restored?.first ?: prev.selectedIndex
        val prevFirstVisible = restored?.second ?: firstVisibleIndex

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
            val visiblePlatforms = if (showEmpty) platforms else platforms.filter { it.gameCount > 0 }
            visiblePlatforms.forEach { reorderableItems.add(ListItem.PlatformItem(it)) }
            if (showPorts && ports.isNotEmpty()) {
                reorderableItems.add(ListItem.PortsFolder(portsName, ports.size))
            }
            if (showTools && tools.isNotEmpty()) {
                reorderableItems.add(ListItem.ToolsFolder(toolsName, tools.size))
            }
            val ordered = applyCustomOrder(reorderableItems, scanner.loadPlatformOrder())
            items.addAll(ordered)

            val canRestore = (restored != null || items.size == prevItemCount) && prevItemCount > 0
            val (safeIndex, scrollTo) = if (canRestore) {
                val idx = when {
                    items.isEmpty() -> 0
                    prevSelectedIndex in items.indices -> prevSelectedIndex
                    else -> items.lastIndex
                }
                idx to prevFirstVisible.coerceAtMost(items.lastIndex.coerceAtLeast(0))
            } else {
                0 to 0
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
        _state.update { current ->
            val size = current.items.size
            if (size == 0) return@update current

            val raw = current.selectedIndex + delta
            val target = ((raw % size) + size) % size

            current.copy(selectedIndex = target)
        }
    }

    fun jumpToIndex(index: Int, scrollTarget: Int) {
        _state.update { it.copy(selectedIndex = index, scrollTarget = scrollTarget) }
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

    fun getNavigableItems(): List<ListItem> =
        _state.value.items

    fun enterReorderMode() {
        _state.update { current ->
            val item = current.items.getOrNull(current.selectedIndex) ?: return@update current
            if (!item.isReorderable()) return@update current
            current.copy(reorderMode = true, reorderOriginalIndex = current.selectedIndex)
        }
    }

    fun isReorderMode(): Boolean = _state.value.reorderMode

    fun reorderMoveUp() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            val items = current.items.toMutableList()
            val prevSelectable = (idx - 1 downTo 0).firstOrNull { items[it].isReorderable() }
                ?: return@update current
            items[idx] = items[prevSelectable].also { items[prevSelectable] = items[idx] }
            current.copy(items = items, selectedIndex = prevSelectable)
        }
    }

    fun reorderMoveDown() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            val items = current.items.toMutableList()
            val nextSelectable = (idx + 1..items.lastIndex).firstOrNull { items[it].isReorderable() }
                ?: return@update current
            items[idx] = items[nextSelectable].also { items[nextSelectable] = items[idx] }
            current.copy(items = items, selectedIndex = nextSelectable)
        }
    }

    fun confirmReorder() {
        val current = _state.value
        if (!current.reorderMode) return
        val tags = current.items.mapNotNull { it.orderTag() }
        viewModelScope.launch(Dispatchers.IO) {
            scanner.savePlatformOrder(tags)
        }
        _state.update { it.copy(reorderMode = false, reorderOriginalIndex = -1) }
    }

    fun cancelReorder(showTools: Boolean = false, showPorts: Boolean = false, showEmpty: Boolean = false, toolsName: String = "Tools", portsName: String = "Ports") {
        val current = _state.value
        if (!current.reorderMode) return
        scan(showTools, showPorts, showEmpty, toolsName, portsName)
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
