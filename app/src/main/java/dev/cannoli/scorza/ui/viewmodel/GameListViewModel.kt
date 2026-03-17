package dev.cannoli.scorza.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cannoli.scorza.model.Game
import dev.cannoli.scorza.scanner.FileScanner
import dev.cannoli.scorza.scanner.PlatformResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GameListViewModel(
    private val scanner: FileScanner,
    private val platformResolver: PlatformResolver
) : ViewModel() {

    data class State(
        val platformTag: String = "",
        val breadcrumb: String = "",
        val games: List<Game> = emptyList(),
        val selectedIndex: Int = 0,
        val scrollTarget: Int = 0,
        val subfolderPath: String? = null,
        val isLoading: Boolean = true,
        val isCollection: Boolean = false,
        val collectionName: String? = null,
        val isCollectionsList: Boolean = false,
        val reorderMode: Boolean = false,
        val reorderOriginalIndex: Int = -1,
        val multiSelectMode: Boolean = false,
        val checkedIndices: Set<Int> = emptySet()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    var firstVisibleIndex: Int = 0

    private val breadcrumbStack = mutableListOf<String>()
    private val indexStack = mutableListOf<Pair<Int, Int>>() // selectedIndex to firstVisibleIndex
    private var collectionsListSaved: Pair<Int, Int> = 0 to 0 // selectedIndex to firstVisibleIndex
    private var collectionsListItemCount: Int = 0

    fun loadPlatform(tag: String, onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        viewModelScope.launch(Dispatchers.IO) {
            val games = scanner.scanGames(tag, null)
            val displayName = platformResolver.getDisplayName(tag)
            _state.value = State(
                platformTag = tag,
                breadcrumb = displayName,
                games = games,
                selectedIndex = 0,
                isLoading = false
            )
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    fun loadCollection(collectionName: String, onReady: () -> Unit = {}) {
        val current = _state.value
        if (current.isCollectionsList) {
            collectionsListSaved = current.selectedIndex to firstVisibleIndex
            collectionsListItemCount = current.games.size
        }
        breadcrumbStack.clear()
        indexStack.clear()
        viewModelScope.launch(Dispatchers.IO) {
            val games = scanner.scanCollectionGames(collectionName)
            _state.value = State(
                breadcrumb = collectionName,
                games = games,
                selectedIndex = 0,
                isLoading = false,
                isCollection = true,
                collectionName = collectionName
            )
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    fun loadApkList(type: String, displayName: String, onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        viewModelScope.launch(Dispatchers.IO) {
            val entries = if (type == "tools") scanner.scanTools() else scanner.scanPorts()
            val games = entries.map { (name, launch) ->
                Game(
                    file = java.io.File(name),
                    displayName = name,
                    platformTag = type,
                    launchTarget = launch
                )
            }
            _state.value = State(
                platformTag = type,
                breadcrumb = displayName,
                games = games,
                selectedIndex = 0,
                isLoading = false
            )
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    fun loadCollectionsList(restoreIndex: Boolean = false, onReady: () -> Unit = {}) {
        breadcrumbStack.clear()
        indexStack.clear()
        viewModelScope.launch(Dispatchers.IO) {
            val collections = scanner.scanCollections()
                .filter { !it.name.equals("Favorites", ignoreCase = true) && it.entries.isNotEmpty() }
            val order = scanner.loadCollectionOrder()
            val ordered = if (order.isEmpty()) collections else {
                val byName = collections.associateBy { it.name }
                val result = mutableListOf<dev.cannoli.scorza.model.Collection>()
                for (name in order) {
                    byName[name]?.let { result.add(it) }
                }
                result.addAll(collections.filter { it.name !in order })
                result
            }
            val games = ordered.map { coll ->
                Game(
                    file = coll.file,
                    displayName = coll.name,
                    platformTag = ""
                )
            }
            val (idx, scroll) = if (restoreIndex && games.size == collectionsListItemCount && collectionsListItemCount > 0) {
                val maxIdx = games.lastIndex.coerceAtLeast(0)
                collectionsListSaved.first.coerceAtMost(maxIdx) to collectionsListSaved.second.coerceAtMost(maxIdx)
            } else {
                0 to 0
            }
            _state.value = State(
                breadcrumb = "Collections",
                games = games,
                selectedIndex = idx,
                scrollTarget = scroll,
                isLoading = false,
                isCollectionsList = true
            )
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    fun reload() {
        val current = _state.value
        val preserveIndex = current.selectedIndex
        val preserveScroll = firstVisibleIndex
        val prevCount = current.games.size
        if (current.isCollectionsList) {
            collectionsListSaved = preserveIndex to preserveScroll
            collectionsListItemCount = prevCount
            loadCollectionsList(restoreIndex = true)
        } else if (current.isCollection && current.collectionName != null) {
            loadCollection(current.collectionName) {
                val s = _state.value
                if (s.games.size == prevCount && prevCount > 0) {
                    _state.value = s.copy(
                        selectedIndex = preserveIndex.coerceAtMost(s.games.lastIndex.coerceAtLeast(0)),
                        scrollTarget = preserveScroll.coerceAtMost(s.games.lastIndex.coerceAtLeast(0))
                    )
                } else {
                    _state.value = s.copy(selectedIndex = 0, scrollTarget = 0)
                }
            }
        } else if (current.platformTag == "tools" || current.platformTag == "ports") {
            loadApkList(current.platformTag, current.breadcrumb) {
                val s = _state.value
                _state.value = s.copy(
                    selectedIndex = preserveIndex.coerceAtMost(s.games.lastIndex.coerceAtLeast(0)),
                    scrollTarget = preserveScroll.coerceAtMost(s.games.lastIndex.coerceAtLeast(0))
                )
            }
        } else if (current.platformTag.isNotEmpty()) {
            loadGames(current.platformTag, current.subfolderPath, preserveIndex, preserveScroll, prevCount)
        }
    }

    fun enterSubfolder(folderName: String) {
        val current = _state.value
        indexStack.add(current.selectedIndex to firstVisibleIndex)
        breadcrumbStack.add(folderName)
        val subPath = breadcrumbStack.joinToString("/")
        loadGames(current.platformTag, subPath)
    }

    fun exitSubfolder(): Boolean {
        if (breadcrumbStack.isEmpty()) return false
        breadcrumbStack.removeAt(breadcrumbStack.lastIndex)
        val (parentIndex, parentScroll) = if (indexStack.isNotEmpty()) indexStack.removeAt(indexStack.lastIndex) else (0 to 0)
        val subPath = if (breadcrumbStack.isEmpty()) null else breadcrumbStack.joinToString("/")
        loadGames(_state.value.platformTag, subPath, parentIndex, parentScroll)
        return true
    }

    fun moveSelection(delta: Int) {
        _state.update { current ->
            if (current.games.isEmpty()) return@update current
            val size = current.games.size
            val raw = current.selectedIndex + delta
            val newIndex = ((raw % size) + size) % size
            current.copy(selectedIndex = newIndex)
        }
    }

    fun jumpToIndex(index: Int, scrollTarget: Int) {
        _state.update { it.copy(selectedIndex = index, scrollTarget = scrollTarget) }
    }

    fun getSelectedGame(): Game? {
        val current = _state.value
        return current.games.getOrNull(current.selectedIndex)
    }

    fun toggleFavorite(onDone: () -> Unit = {}) {
        val current = _state.value
        val game = current.games.getOrNull(current.selectedIndex) ?: return
        if (game.isSubfolder || current.isCollectionsList || current.platformTag in listOf("tools", "ports")) return
        val path = game.file.absolutePath
        val isFav = game.displayName.startsWith("★") ||
            (current.isCollection && current.collectionName == "Favorites")
        val oldIndex = current.selectedIndex
        viewModelScope.launch(Dispatchers.IO) {
            if (isFav) scanner.removeFromCollection("Favorites", path)
            else scanner.addToCollection("Favorites", path)
            val newGames = if (current.isCollection && current.collectionName != null) {
                scanner.scanCollectionGames(current.collectionName)
            } else {
                scanner.scanGames(current.platformTag, current.subfolderPath)
            }
            val newIndex = newGames.indexOfFirst { it.file.absolutePath == path }
                .let { if (it >= 0) it else oldIndex.coerceAtMost(newGames.lastIndex.coerceAtLeast(0)) }
            _state.value = current.copy(games = newGames, selectedIndex = newIndex, scrollTarget = -1)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun enterMultiSelect() {
        _state.update { current ->
            if (current.reorderMode || current.multiSelectMode) return@update current
            val game = current.games.getOrNull(current.selectedIndex)
            val initial = if (game != null && !game.isSubfolder) setOf(current.selectedIndex) else emptySet()
            current.copy(multiSelectMode = true, checkedIndices = initial)
        }
    }

    fun isMultiSelectMode(): Boolean = _state.value.multiSelectMode

    fun toggleChecked() {
        _state.update { current ->
            if (!current.multiSelectMode) return@update current
            val idx = current.selectedIndex
            val game = current.games.getOrNull(idx) ?: return@update current
            if (game.isSubfolder) return@update current
            val newChecked = if (idx in current.checkedIndices) current.checkedIndices - idx else current.checkedIndices + idx
            current.copy(checkedIndices = newChecked)
        }
    }

    fun confirmMultiSelect(): Set<Int> {
        var checked = emptySet<Int>()
        _state.update { current ->
            checked = current.checkedIndices
            current.copy(multiSelectMode = false, checkedIndices = emptySet())
        }
        return checked
    }

    fun cancelMultiSelect() {
        _state.update { it.copy(multiSelectMode = false, checkedIndices = emptySet()) }
    }

    fun enterReorderMode() {
        _state.update { current ->
            if (!current.isCollectionsList || current.games.isEmpty()) return@update current
            current.copy(reorderMode = true, reorderOriginalIndex = current.selectedIndex)
        }
    }

    fun isReorderMode(): Boolean = _state.value.reorderMode

    fun reorderMoveUp() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            if (idx <= 0) return@update current
            val games = current.games.toMutableList()
            games[idx] = games[idx - 1].also { games[idx - 1] = games[idx] }
            current.copy(games = games, selectedIndex = idx - 1)
        }
    }

    fun reorderMoveDown() {
        _state.update { current ->
            if (!current.reorderMode) return@update current
            val idx = current.selectedIndex
            if (idx >= current.games.lastIndex) return@update current
            val games = current.games.toMutableList()
            games[idx] = games[idx + 1].also { games[idx + 1] = games[idx] }
            current.copy(games = games, selectedIndex = idx + 1)
        }
    }

    fun confirmReorder() {
        val current = _state.value
        if (!current.reorderMode) return
        val names = current.games.map { it.displayName }
        viewModelScope.launch(Dispatchers.IO) {
            scanner.saveCollectionOrder(names)
        }
        _state.update { it.copy(reorderMode = false, reorderOriginalIndex = -1) }
    }

    fun cancelReorder() {
        val current = _state.value
        if (!current.reorderMode) return
        loadCollectionsList()
    }

    private fun loadGames(tag: String, subfolder: String?, preserveIndex: Int = 0, preserveScroll: Int = 0, prevCount: Int = -1) {
        viewModelScope.launch(Dispatchers.IO) {
            val games = scanner.scanGames(tag, subfolder)
            val displayName = platformResolver.getDisplayName(tag)

            val breadcrumb = if (breadcrumbStack.isEmpty()) {
                displayName
            } else {
                (listOf(displayName) + breadcrumbStack)
                    .joinToString(" \u203A ")
            }

            val sameSize = prevCount >= 0 && games.size == prevCount && prevCount > 0
            val maxIdx = games.lastIndex.coerceAtLeast(0)
            val (idx, scroll) = if (sameSize || prevCount < 0) {
                preserveIndex.coerceAtMost(maxIdx) to preserveScroll.coerceAtMost(maxIdx)
            } else {
                0 to 0
            }

            _state.value = State(
                platformTag = tag,
                breadcrumb = breadcrumb,
                games = games,
                selectedIndex = idx,
                scrollTarget = scroll,
                subfolderPath = subfolder,
                isLoading = false
            )
        }
    }
}
