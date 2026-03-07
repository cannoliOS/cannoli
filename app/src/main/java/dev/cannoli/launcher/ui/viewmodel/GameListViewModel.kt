package dev.cannoli.launcher.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cannoli.launcher.model.Game
import dev.cannoli.launcher.scanner.FileScanner
import dev.cannoli.launcher.scanner.PlatformResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class GameListViewModel(
    private val scanner: FileScanner,
    private val platformResolver: PlatformResolver
) : ViewModel() {

    data class State(
        val platformTag: String = "",
        val breadcrumb: String = "",
        val games: List<Game> = emptyList(),
        val selectedIndex: Int = 0,
        val subfolderPath: String? = null,
        val isLoading: Boolean = true
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val breadcrumbStack = mutableListOf<String>()

    fun loadPlatform(tag: String) {
        breadcrumbStack.clear()
        _state.value = State(platformTag = tag)
        loadGames(tag, null)
    }

    fun enterSubfolder(folderName: String) {
        val current = _state.value
        breadcrumbStack.add(folderName)
        val subPath = breadcrumbStack.joinToString("/")
        loadGames(current.platformTag, subPath)
    }

    fun exitSubfolder(): Boolean {
        if (breadcrumbStack.isEmpty()) return false
        breadcrumbStack.removeAt(breadcrumbStack.lastIndex)
        val subPath = if (breadcrumbStack.isEmpty()) null else breadcrumbStack.joinToString("/")
        loadGames(_state.value.platformTag, subPath)
        return true
    }

    fun moveSelection(delta: Int) {
        val current = _state.value
        if (current.games.isEmpty()) return
        val size = current.games.size
        val raw = current.selectedIndex + delta
        val newIndex = ((raw % size) + size) % size
        _state.value = current.copy(selectedIndex = newIndex)
    }

    fun getSelectedGame(): Game? {
        val current = _state.value
        return current.games.getOrNull(current.selectedIndex)
    }

    private fun loadGames(tag: String, subfolder: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val games = scanner.scanGames(tag, subfolder)
            val displayName = platformResolver.getDisplayName(tag)

            val breadcrumb = if (breadcrumbStack.isEmpty()) {
                displayName.uppercase()
            } else {
                (listOf(displayName.uppercase()) + breadcrumbStack.map { it.uppercase() })
                    .joinToString(" \u203A ")
            }

            _state.value = _state.value.copy(
                platformTag = tag,
                breadcrumb = breadcrumb,
                games = games,
                selectedIndex = 0,
                subfolderPath = subfolder,
                isLoading = false
            )
        }
    }
}
