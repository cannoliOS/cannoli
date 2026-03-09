package dev.cannoli.scorza.ui.screens

interface KeyboardInputState {
    val currentName: String
    val cursorPos: Int
    val keyRow: Int
    val keyCol: Int
    val caps: Boolean
    val symbols: Boolean
}

sealed interface DialogState {
    data object None : DialogState
    data class MissingCore(val coreName: String) : DialogState
    data class MissingApp(val packageName: String) : DialogState
    data class ContextMenu(val gameName: String, val selectedOption: Int = 0, val options: List<String> = listOf("Add to Favorites", "Manage Collections", "Rename", "Delete")) : DialogState
    data class BulkContextMenu(val gamePaths: List<String>, val selectedOption: Int = 0, val options: List<String> = listOf("Add to Favorites", "Manage Collections", "Delete")) : DialogState
    data class DeleteConfirm(val gameName: String) : DialogState
    data class CollectionPicker(val gamePaths: List<String>, val title: String, val collections: List<String>, val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet()) : DialogState
    data class RenameInput(val gameName: String, override val currentName: String, override val cursorPos: Int = 0, override val keyRow: Int = 2, override val keyCol: Int = 0, override val caps: Boolean = false, override val symbols: Boolean = false) : DialogState, KeyboardInputState
    data class NewCollectionInput(val gamePaths: List<String> = emptyList(), override val currentName: String = "", override val cursorPos: Int = 0, override val keyRow: Int = 2, override val keyCol: Int = 0, override val caps: Boolean = false, override val symbols: Boolean = false) : DialogState, KeyboardInputState
    data class CollectionRenameInput(val oldName: String, override val currentName: String, override val cursorPos: Int = 0, override val keyRow: Int = 2, override val keyCol: Int = 0, override val caps: Boolean = false, override val symbols: Boolean = false) : DialogState, KeyboardInputState
    data class DeleteCollectionConfirm(val collectionName: String) : DialogState
    data class RenameResult(val success: Boolean, val message: String) : DialogState
    data class CollectionCreated(val collectionName: String) : DialogState
    data class ColorEntry(val key: String, val label: String, val hex: String, val color: Long)
    data class ColorList(val colors: List<ColorEntry>, val selectedIndex: Int = 0) : DialogState
    data class ColorPicker(val settingKey: String, val currentColor: Long, val selectedRow: Int = 0, val selectedCol: Int = 0) : DialogState
    data class HexColorInput(val settingKey: String, val currentHex: String = "", val selectedIndex: Int = 0) : DialogState
    data class CoreMappingEntry(val tag: String, val platformName: String, val coreDisplayName: String, val runnerLabel: String)
    data class CoreMappingList(val mappings: List<CoreMappingEntry>, val selectedIndex: Int = 0) : DialogState
    data class CorePickerOption(val coreId: String, val displayName: String, val runnerLabel: String)
    data class CorePicker(val tag: String, val platformName: String, val cores: List<CorePickerOption>, val selectedIndex: Int = 0, val gamePath: String? = null) : DialogState
    data class AppPicker(val type: String, val title: String, val apps: List<String>, val packages: List<String>, val selectedIndex: Int = 0, val checkedIndices: Set<Int> = emptySet(), val initialChecked: Set<Int> = emptySet()) : DialogState
}

val DialogState.isFullScreen: Boolean
    get() = when (this) {
        is DialogState.ContextMenu,
        is DialogState.BulkContextMenu,
        is DialogState.CollectionPicker,
        is DialogState.CoreMappingList,
        is DialogState.AppPicker,
        is DialogState.ColorList,
        is DialogState.ColorPicker,
        is DialogState.HexColorInput,
        is DialogState.RenameInput,
        is DialogState.NewCollectionInput,
        is DialogState.CollectionRenameInput,
        is DialogState.CorePicker -> true
        else -> false
    }
