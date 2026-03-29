package dev.cannoli.scorza.ui.screens

interface KeyboardInputState {
    val currentName: String
    val cursorPos: Int
    val keyRow: Int
    val keyCol: Int
    val caps: Boolean
    val symbols: Boolean
}

data class CoreMappingEntry(val tag: String, val platformName: String, val coreDisplayName: String, val runnerLabel: String)
data class CorePickerOption(val coreId: String, val displayName: String, val runnerLabel: String, val appPackage: String? = null, val raPackage: String? = null)
data class ColorEntry(val key: String, @androidx.annotation.StringRes val labelRes: Int, val hex: String, val color: Long)

sealed interface DialogState {
    data object None : DialogState
    data class MissingCore(val coreName: String) : DialogState
    data class MissingApp(val appName: String, val packageName: String) : DialogState
    data class LaunchError(val message: String) : DialogState
    data class ContextMenu(val gameName: String, val selectedOption: Int = 0, val options: List<String>) : DialogState
    data class BulkContextMenu(val gamePaths: List<String>, val selectedOption: Int = 0, val options: List<String>) : DialogState
    data class DeleteConfirm(val gameName: String, val bulkPaths: List<String>? = null) : DialogState
    data class RenameInput(val gameName: String, override val currentName: String, override val cursorPos: Int = 0, override val keyRow: Int = 2, override val keyCol: Int = 0, override val caps: Boolean = false, override val symbols: Boolean = false) : DialogState, KeyboardInputState
    data class NewCollectionInput(val gamePaths: List<String> = emptyList(), override val currentName: String = "", override val cursorPos: Int = 0, override val keyRow: Int = 2, override val keyCol: Int = 0, override val caps: Boolean = false, override val symbols: Boolean = false) : DialogState, KeyboardInputState
    data class CollectionRenameInput(val oldName: String, override val currentName: String, override val cursorPos: Int = 0, override val keyRow: Int = 2, override val keyCol: Int = 0, override val caps: Boolean = false, override val symbols: Boolean = false) : DialogState, KeyboardInputState
    data class DeleteCollectionConfirm(val collectionName: String) : DialogState
    data class RenameResult(val success: Boolean, val message: String) : DialogState
    data class CollectionCreated(val collectionName: String) : DialogState
    data class ColorPicker(val settingKey: String, val currentColor: Long, val selectedRow: Int = 0, val selectedCol: Int = 0) : DialogState
    data class HexColorInput(val settingKey: String, val currentHex: String = "", val selectedIndex: Int = 0) : DialogState
    data object About : DialogState
    data class Kitchen(val url: String, val pin: String) : DialogState
    data class RAAccount(val username: String, val score: Int = 0) : DialogState
    data class RALoggingIn(val message: String = "Logging in...") : DialogState
    data class ProfileNameInput(val isNew: Boolean, val originalName: String = "", override val currentName: String = "", override val cursorPos: Int = 0, override val keyRow: Int = 2, override val keyCol: Int = 0, override val caps: Boolean = false, override val symbols: Boolean = false) : DialogState, KeyboardInputState
    data class DeleteProfileConfirm(val profileName: String) : DialogState
}

fun DialogState.asKeyboardState(): KeyboardInputState? = this as? KeyboardInputState

fun DialogState.withKeyboard(row: Int, col: Int): DialogState = when (this) {
    is DialogState.RenameInput -> copy(keyRow = row, keyCol = col)
    is DialogState.NewCollectionInput -> copy(keyRow = row, keyCol = col)
    is DialogState.CollectionRenameInput -> copy(keyRow = row, keyCol = col)
    is DialogState.ProfileNameInput -> copy(keyRow = row, keyCol = col)
    else -> this
}

fun DialogState.withCursor(pos: Int): DialogState = when (this) {
    is DialogState.RenameInput -> copy(cursorPos = pos)
    is DialogState.NewCollectionInput -> copy(cursorPos = pos)
    is DialogState.CollectionRenameInput -> copy(cursorPos = pos)
    is DialogState.ProfileNameInput -> copy(cursorPos = pos)
    else -> this
}

fun DialogState.withCaps(caps: Boolean): DialogState = when (this) {
    is DialogState.RenameInput -> copy(caps = caps)
    is DialogState.NewCollectionInput -> copy(caps = caps)
    is DialogState.CollectionRenameInput -> copy(caps = caps)
    is DialogState.ProfileNameInput -> copy(caps = caps)
    else -> this
}

fun DialogState.withSymbols(symbols: Boolean): DialogState = when (this) {
    is DialogState.RenameInput -> copy(symbols = symbols)
    is DialogState.NewCollectionInput -> copy(symbols = symbols)
    is DialogState.CollectionRenameInput -> copy(symbols = symbols)
    is DialogState.ProfileNameInput -> copy(symbols = symbols)
    else -> this
}

fun DialogState.withNameAndCursor(name: String, pos: Int): DialogState = when (this) {
    is DialogState.RenameInput -> copy(currentName = name, cursorPos = pos)
    is DialogState.NewCollectionInput -> copy(currentName = name, cursorPos = pos)
    is DialogState.CollectionRenameInput -> copy(currentName = name, cursorPos = pos)
    is DialogState.ProfileNameInput -> copy(currentName = name, cursorPos = pos)
    else -> this
}

fun DialogState.withMenuDelta(delta: Int): DialogState? = when (this) {
    is DialogState.ContextMenu -> {
        val newIdx = (selectedOption + delta).mod(options.size)
        copy(selectedOption = newIdx)
    }
    is DialogState.BulkContextMenu -> {
        val newIdx = (selectedOption + delta).mod(options.size)
        copy(selectedOption = newIdx)
    }
    else -> null
}

fun DialogState.withBackspace(): DialogState? {
    val ks = asKeyboardState() ?: return null
    if (ks.cursorPos <= 0) return null
    val newName = ks.currentName.removeRange(ks.cursorPos - 1, ks.cursorPos)
    return withNameAndCursor(newName, ks.cursorPos - 1)
}

fun DialogState.withInsertedChar(char: String): DialogState? {
    val ks = asKeyboardState() ?: return null
    val newName = ks.currentName.substring(0, ks.cursorPos) + char + ks.currentName.substring(ks.cursorPos)
    return withNameAndCursor(newName, ks.cursorPos + 1)
}

val DialogState.isFullScreen: Boolean
    get() = when (this) {
        is DialogState.ContextMenu,
        is DialogState.BulkContextMenu,
        is DialogState.ColorPicker,
        is DialogState.HexColorInput,
        is DialogState.RenameInput,
        is DialogState.NewCollectionInput,
        is DialogState.CollectionRenameInput,
        is DialogState.ProfileNameInput,
        is DialogState.About,
        is DialogState.Kitchen,
        is DialogState.RAAccount,
        is DialogState.RALoggingIn -> true
        else -> false
    }
