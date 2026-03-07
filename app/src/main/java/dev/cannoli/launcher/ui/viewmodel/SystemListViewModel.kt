package dev.cannoli.launcher.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cannoli.launcher.model.Platform
import dev.cannoli.launcher.scanner.FileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SystemListViewModel(
    private val scanner: FileScanner
) : ViewModel() {

    sealed class ListItem {
        data class PlatformItem(val platform: Platform) : ListItem()
        data class CollectionItem(val name: String, val count: Int) : ListItem()
        data class ToolItem(val name: String, val packageName: String) : ListItem()
        data class PortItem(val name: String, val packageName: String) : ListItem()
        data class Divider(val label: String? = null) : ListItem()
    }

    data class State(
        val items: List<ListItem> = emptyList(),
        val platforms: List<Platform> = emptyList(),
        val selectedIndex: Int = 0,
        val isLoading: Boolean = true
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    fun scan() {
        viewModelScope.launch(Dispatchers.IO) {
            val platforms = scanner.scanPlatforms()
            val collections = scanner.scanCollections().map { it.name to it.entries.size }
            val tools = scanner.scanTools()
            val ports = scanner.scanPorts()

            val items = mutableListOf<ListItem>()

            platforms.forEach { items.add(ListItem.PlatformItem(it)) }

            if (collections.isNotEmpty()) {
                items.add(ListItem.Divider())
                collections.forEach { (name, count) ->
                    items.add(ListItem.CollectionItem(name, count))
                }
            }

            if (tools.isNotEmpty()) {
                items.add(ListItem.Divider("Tools"))
                tools.forEach { (name, launch) ->
                    items.add(ListItem.ToolItem(name, launch.packageName))
                }
            }

            if (ports.isNotEmpty()) {
                items.add(ListItem.Divider("Ports"))
                ports.forEach { (name, launch) ->
                    items.add(ListItem.PortItem(name, launch.packageName))
                }
            }

            val prevIndex = _state.value.selectedIndex
            val selectableIndices = items.indices.filter { items[it] !is ListItem.Divider }
            val safeIndex = when {
                selectableIndices.isEmpty() -> 0
                prevIndex in selectableIndices -> prevIndex
                else -> selectableIndices.firstOrNull { it >= prevIndex } ?: selectableIndices.last()
            }
            _state.value = State(
                items = items,
                platforms = platforms,
                selectedIndex = safeIndex,
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

    fun getSelectedItem(): ListItem? {
        val current = _state.value
        return current.items.getOrNull(current.selectedIndex)
    }

    fun getSelectedPlatformTag(): String? {
        return (getSelectedItem() as? ListItem.PlatformItem)?.platform?.tag
    }

    fun getPlatformTags(): List<String> = _state.value.platforms.map { it.tag }
}
