package dev.cannoli.scorza

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.cannoli.scorza.input.InputHandler
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.EmuLauncher
import dev.cannoli.scorza.launcher.LaunchResult
import dev.cannoli.scorza.launcher.RetroArchLauncher
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.navigation.AppNavGraph
import dev.cannoli.scorza.navigation.Routes
import dev.cannoli.scorza.scanner.FileScanner
import dev.cannoli.scorza.scanner.PlatformResolver
import dev.cannoli.scorza.settings.ScrollSpeed
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.components.KEY_BACKSPACE
import dev.cannoli.scorza.ui.components.KEY_ENTER
import dev.cannoli.scorza.ui.components.KEY_SHIFT
import dev.cannoli.scorza.ui.components.KEY_SPACE
import dev.cannoli.scorza.ui.components.KEY_SYMBOLS
import dev.cannoli.scorza.ui.components.getKeyboardRows
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.theme.CannoliTheme
import dev.cannoli.scorza.ui.theme.initFonts
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.util.AtomicRename
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var platformResolver: PlatformResolver
    private lateinit var scanner: FileScanner
    private lateinit var systemListViewModel: SystemListViewModel
    private lateinit var gameListViewModel: GameListViewModel
    private lateinit var settingsViewModel: SettingsViewModel

    private lateinit var retroArchLauncher: RetroArchLauncher
    private lateinit var emuLauncher: EmuLauncher
    private lateinit var apkLauncher: ApkLauncher

    private lateinit var inputHandler: InputHandler
    private lateinit var atomicRename: AtomicRename
    private var navController: NavHostController? = null

    private val dialogState = MutableStateFlow<DialogState>(DialogState.None)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    @Volatile private var navigating = false

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasStoragePermission()) {
            initializeApp()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = uri.path?.let { raw ->
                val prefix = "/tree/primary:"
                if (raw.startsWith(prefix)) "/storage/emulated/0/" + raw.removePrefix(prefix)
                else raw
            }
            if (path != null) {
                settings.sdCardRoot = if (path.endsWith("/")) path else "$path/"
            }
        }
    }

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            initializeApp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUI()

        settings = SettingsRepository(this)
        initFonts(assets)

        if (hasStoragePermission()) {
            initializeApp()
        } else {
            requestStoragePermission()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (::systemListViewModel.isInitialized) {
            systemListViewModel.scan()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (::inputHandler.isInitialized && inputHandler.handleKeyEvent(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun initializeApp() {
        val root = File(settings.sdCardRoot)
        platformResolver = PlatformResolver(root)
        platformResolver.load()

        scanner = FileScanner(root, platformResolver)
        scanner.ensureDirectories()

        systemListViewModel = SystemListViewModel(scanner)
        gameListViewModel = GameListViewModel(scanner, platformResolver)
        settingsViewModel = SettingsViewModel(settings, root)
        atomicRename = AtomicRename(root)

        retroArchLauncher = RetroArchLauncher(this, settings.retroArchPackage)
        emuLauncher = EmuLauncher(this)
        apkLauncher = ApkLauncher(this)

        inputHandler = InputHandler(
            getButtonLayout = { settings.buttonLayout },
            getSwapStartSelect = { settings.swapStartSelect }
        )
        wireInput()

        systemListViewModel.scan()

        setContent {
            CannoliTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    navController = nav
                    AppNavGraph(
                        navController = nav,
                        systemListViewModel = systemListViewModel,
                        gameListViewModel = gameListViewModel,
                        settingsViewModel = settingsViewModel,
                        dialogState = dialogState
                    )
                }
            }
        }
    }

    private fun wireInput() {
        fun dialogOpen() = dialogState.value != DialogState.None

        inputHandler.onUp = {
            when (val ds = dialogState.value) {
                is DialogState.ContextMenu, is DialogState.BulkContextMenu -> {
                    val menu = ds as? DialogState.ContextMenu
                    val bulk = ds as? DialogState.BulkContextMenu
                    val opts = menu?.options ?: bulk!!.options
                    val cur = menu?.selectedOption ?: bulk!!.selectedOption
                    val newIdx = if (cur <= 0) opts.lastIndex else cur - 1
                    dialogState.value = menu?.copy(selectedOption = newIdx) ?: bulk!!.copy(selectedOption = newIdx)
                }
                is DialogState.CollectionPicker -> {
                    if (ds.collections.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex <= 0) ds.collections.lastIndex else ds.selectedIndex - 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.RenameInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val newRow = if (ds.keyRow <= 0) rows.lastIndex else ds.keyRow - 1
                    val newCol = ds.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.copy(keyRow = newRow, keyCol = newCol)
                }
                is DialogState.NewCollectionInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val newRow = if (ds.keyRow <= 0) rows.lastIndex else ds.keyRow - 1
                    val newCol = ds.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.copy(keyRow = newRow, keyCol = newCol)
                }
                is DialogState.CollectionRenameInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val newRow = if (ds.keyRow <= 0) rows.lastIndex else ds.keyRow - 1
                    val newCol = ds.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.copy(keyRow = newRow, keyCol = newCol)
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveUp()
                        else systemListViewModel.moveSelection(-1)
                    }
                    Screen.GAME_LIST -> {
                        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveUp()
                        else gameListViewModel.moveSelection(-1)
                    }
                    Screen.SETTINGS -> settingsViewModel.moveSelection(-1)
                }
                else -> {}
            }
        }

        inputHandler.onDown = {
            when (val ds = dialogState.value) {
                is DialogState.ContextMenu, is DialogState.BulkContextMenu -> {
                    val menu = ds as? DialogState.ContextMenu
                    val bulk = ds as? DialogState.BulkContextMenu
                    val opts = menu?.options ?: bulk!!.options
                    val cur = menu?.selectedOption ?: bulk!!.selectedOption
                    val newIdx = if (cur >= opts.lastIndex) 0 else cur + 1
                    dialogState.value = menu?.copy(selectedOption = newIdx) ?: bulk!!.copy(selectedOption = newIdx)
                }
                is DialogState.CollectionPicker -> {
                    if (ds.collections.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex >= ds.collections.lastIndex) 0 else ds.selectedIndex + 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.RenameInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val newRow = if (ds.keyRow >= rows.lastIndex) 0 else ds.keyRow + 1
                    val newCol = ds.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.copy(keyRow = newRow, keyCol = newCol)
                }
                is DialogState.NewCollectionInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val newRow = if (ds.keyRow >= rows.lastIndex) 0 else ds.keyRow + 1
                    val newCol = ds.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.copy(keyRow = newRow, keyCol = newCol)
                }
                is DialogState.CollectionRenameInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val newRow = if (ds.keyRow >= rows.lastIndex) 0 else ds.keyRow + 1
                    val newCol = ds.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.copy(keyRow = newRow, keyCol = newCol)
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveDown()
                        else systemListViewModel.moveSelection(1)
                    }
                    Screen.GAME_LIST -> {
                        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveDown()
                        else gameListViewModel.moveSelection(1)
                    }
                    Screen.SETTINGS -> settingsViewModel.moveSelection(1)
                }
                else -> {}
            }
        }

        inputHandler.onLeft = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val rowSize = rows[ds.keyRow.coerceIn(0, rows.lastIndex)].size
                    val newCol = if (ds.keyCol <= 0) rowSize - 1 else ds.keyCol - 1
                    dialogState.value = ds.copy(keyCol = newCol)
                }
                is DialogState.NewCollectionInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val rowSize = rows[ds.keyRow.coerceIn(0, rows.lastIndex)].size
                    val newCol = if (ds.keyCol <= 0) rowSize - 1 else ds.keyCol - 1
                    dialogState.value = ds.copy(keyCol = newCol)
                }
                is DialogState.CollectionRenameInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val rowSize = rows[ds.keyRow.coerceIn(0, rows.lastIndex)].size
                    val newCol = if (ds.keyCol <= 0) rowSize - 1 else ds.keyCol - 1
                    dialogState.value = ds.copy(keyCol = newCol)
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> if (!systemListViewModel.isReorderMode()) systemListViewModel.pageJump(-systemListViewModel.pageSize)
                    Screen.GAME_LIST -> if (!gameListViewModel.isReorderMode()) gameListViewModel.pageJump(-gameListViewModel.pageSize)
                    Screen.SETTINGS -> if (settingsViewModel.state.value.inSubList) settingsViewModel.cycleSelected(-1)
                }
                else -> {}
            }
        }

        inputHandler.onRight = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val rowSize = rows[ds.keyRow.coerceIn(0, rows.lastIndex)].size
                    val newCol = if (ds.keyCol >= rowSize - 1) 0 else ds.keyCol + 1
                    dialogState.value = ds.copy(keyCol = newCol)
                }
                is DialogState.NewCollectionInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val rowSize = rows[ds.keyRow.coerceIn(0, rows.lastIndex)].size
                    val newCol = if (ds.keyCol >= rowSize - 1) 0 else ds.keyCol + 1
                    dialogState.value = ds.copy(keyCol = newCol)
                }
                is DialogState.CollectionRenameInput -> {
                    val rows = getKeyboardRows(ds.caps, ds.symbols)
                    val rowSize = rows[ds.keyRow.coerceIn(0, rows.lastIndex)].size
                    val newCol = if (ds.keyCol >= rowSize - 1) 0 else ds.keyCol + 1
                    dialogState.value = ds.copy(keyCol = newCol)
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> if (!systemListViewModel.isReorderMode()) systemListViewModel.pageJump(systemListViewModel.pageSize)
                    Screen.GAME_LIST -> if (!gameListViewModel.isReorderMode()) gameListViewModel.pageJump(gameListViewModel.pageSize)
                    Screen.SETTINGS -> if (settingsViewModel.state.value.inSubList) settingsViewModel.cycleSelected(1)
                }
                else -> {}
            }
        }

        inputHandler.onConfirm = {
            when (val ds = dialogState.value) {
                is DialogState.ContextMenu -> onContextMenuConfirm(ds)
                is DialogState.BulkContextMenu -> onBulkContextMenuConfirm(ds)
                is DialogState.DeleteConfirm -> onDeleteConfirm()
                is DialogState.CollectionPicker -> {
                    val newChecked = if (ds.selectedIndex in ds.checkedIndices) {
                        ds.checkedIndices - ds.selectedIndex
                    } else {
                        ds.checkedIndices + ds.selectedIndex
                    }
                    dialogState.value = ds.copy(checkedIndices = newChecked)
                }
                is DialogState.RenameInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                    onChar = { name, pos -> dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                    onShift = { dialogState.value = ds.copy(caps = !ds.caps) },
                    onSymbols = { dialogState.value = ds.copy(symbols = !ds.symbols) },
                    onEnter = { onRenameConfirm(ds) }
                )
                is DialogState.NewCollectionInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                    onChar = { name, pos -> dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                    onShift = { dialogState.value = ds.copy(caps = !ds.caps) },
                    onSymbols = { dialogState.value = ds.copy(symbols = !ds.symbols) },
                    onEnter = { onNewCollectionConfirm(ds) }
                )
                is DialogState.CollectionRenameInput -> handleKeyboardConfirm(ds.caps, ds.symbols, ds.keyRow, ds.keyCol, ds.currentName, ds.cursorPos,
                    onChar = { name, pos -> dialogState.value = ds.copy(currentName = name, cursorPos = pos) },
                    onShift = { dialogState.value = ds.copy(caps = !ds.caps) },
                    onSymbols = { dialogState.value = ds.copy(symbols = !ds.symbols) },
                    onEnter = { onCollectionRenameConfirm(ds) }
                )
                is DialogState.DeleteCollectionConfirm -> {
                    val name = ds.collectionName
                    dialogState.value = DialogState.None
                    ioScope.launch {
                        scanner.deleteCollection(name)
                        val remaining = scanner.scanCollections()
                            .filter { !it.name.equals("Favorites", ignoreCase = true) && it.entries.isNotEmpty() }
                        if (remaining.isEmpty()) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                navController?.popBackStack()
                                systemListViewModel.scan()
                            }
                        } else {
                            gameListViewModel.loadCollectionsList()
                        }
                    }
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> {
                        if (systemListViewModel.isMultiSelectMode()) systemListViewModel.toggleChecked()
                        else if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
                        else onSystemListConfirm()
                    }
                    Screen.GAME_LIST -> {
                        if (gameListViewModel.isMultiSelectMode()) gameListViewModel.toggleChecked()
                        else if (gameListViewModel.isReorderMode()) gameListViewModel.confirmReorder()
                        else onGameListConfirm()
                    }
                    Screen.SETTINGS -> {
                        if (!settingsViewModel.state.value.inSubList) {
                            settingsViewModel.enterCategory()
                        } else {
                            val key = settingsViewModel.enterSelected()
                            if (key == "sd_root") {
                                folderPickerLauncher.launch(null)
                            } else if (key != null) {
                                val displayValue = settingsViewModel.getSelectedItemDisplayValue()
                                dialogState.value = DialogState.RenameInput(
                                    gameName = key,
                                    currentName = displayValue,
                                    cursorPos = displayValue.length
                                )
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        inputHandler.onBack = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> {
                    if (ds.cursorPos > 0) {
                        val newName = ds.currentName.removeRange(ds.cursorPos - 1, ds.cursorPos)
                        dialogState.value = ds.copy(currentName = newName, cursorPos = ds.cursorPos - 1)
                    }
                }
                is DialogState.NewCollectionInput -> {
                    if (ds.cursorPos > 0) {
                        val newName = ds.currentName.removeRange(ds.cursorPos - 1, ds.cursorPos)
                        dialogState.value = ds.copy(currentName = newName, cursorPos = ds.cursorPos - 1)
                    }
                }
                is DialogState.CollectionRenameInput -> {
                    if (ds.cursorPos > 0) {
                        val newName = ds.currentName.removeRange(ds.cursorPos - 1, ds.cursorPos)
                        dialogState.value = ds.copy(currentName = newName, cursorPos = ds.cursorPos - 1)
                    }
                }
                is DialogState.ContextMenu, is DialogState.BulkContextMenu,
                is DialogState.DeleteConfirm,
                is DialogState.DeleteCollectionConfirm,
                is DialogState.CollectionPicker,
                is DialogState.CollectionCreated,
                is DialogState.RenameResult, is DialogState.MissingCore,
                is DialogState.MissingApp -> {
                    dialogState.value = DialogState.None
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> {
                        if (systemListViewModel.isMultiSelectMode()) systemListViewModel.cancelMultiSelect()
                        else if (systemListViewModel.isReorderMode()) systemListViewModel.cancelReorder()
                    }
                    Screen.GAME_LIST -> {
                        if (gameListViewModel.isMultiSelectMode()) {
                            gameListViewModel.cancelMultiSelect()
                        } else if (gameListViewModel.isReorderMode()) {
                            gameListViewModel.cancelReorder()
                        } else if (!navigating) {
                            val glState = gameListViewModel.state.value
                            if (!gameListViewModel.exitSubfolder()) {
                                if (glState.isCollection && glState.collectionName != null
                                    && !glState.collectionName.equals("Favorites", ignoreCase = true)) {
                                    gameListViewModel.loadCollectionsList()
                                } else {
                                    navController?.popBackStack()
                                    systemListViewModel.scan()
                                }
                            }
                        }
                    }
                    Screen.SETTINGS -> {
                        if (!settingsViewModel.exitSubList()) {
                            settingsViewModel.cancel()
                            navController?.popBackStack()
                        }
                    }
                }
            }
        }

        inputHandler.onStart = {
            when (val ds = dialogState.value) {
                is DialogState.CollectionPicker -> onCollectionPickerConfirm(ds)
                is DialogState.RenameInput -> onRenameConfirm(ds)
                is DialogState.NewCollectionInput -> onNewCollectionConfirm(ds)
                is DialogState.CollectionRenameInput -> onCollectionRenameConfirm(ds)
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> {
                        if (systemListViewModel.isMultiSelectMode()) {
                            systemListViewModel.confirmMultiSelect()
                        } else if (systemListViewModel.isReorderMode()) {
                            systemListViewModel.confirmReorder()
                        } else {
                            settingsViewModel.load()
                            navController?.navigate(Routes.SETTINGS)
                        }
                    }
                    Screen.GAME_LIST -> {
                        if (gameListViewModel.isMultiSelectMode()) {
                            val glState = gameListViewModel.state.value
                            val checkedGames = glState.checkedIndices
                                .mapNotNull { glState.games.getOrNull(it) }
                                .filter { !it.isSubfolder }
                            if (checkedGames.isNotEmpty()) {
                                val paths = checkedGames.map { it.file.absolutePath }
                                val options = mutableListOf<String>()
                                if (glState.isCollection && glState.collectionName != null) {
                                    options.add("Remove from Collection")
                                }
                                options.addAll(listOf("Add to Favorites", "Manage Collections", "Delete"))
                                gameListViewModel.confirmMultiSelect()
                                dialogState.value = DialogState.BulkContextMenu(
                                    gamePaths = paths,
                                    options = options
                                )
                            } else {
                                gameListViewModel.cancelMultiSelect()
                            }
                        } else if (gameListViewModel.isReorderMode()) {
                            gameListViewModel.confirmReorder()
                        } else {
                        val glState = gameListViewModel.state.value
                        val game = gameListViewModel.getSelectedGame()
                        if (game != null) {
                            if (glState.isCollectionsList) {
                                dialogState.value = DialogState.ContextMenu(
                                    gameName = game.displayName,
                                    options = listOf("Rename", "Delete")
                                )
                            } else if (!game.isSubfolder) {
                                val isFav = scanner.isInCollection("Favorites", game.file.absolutePath)
                                val favOption = if (isFav) "Remove from Favorites" else "Add to Favorites"
                                dialogState.value = DialogState.ContextMenu(
                                    gameName = game.displayName,
                                    options = listOf(favOption, "Manage Collections", "Rename", "Delete")
                                )
                            }
                        }
                        }
                    }
                    Screen.SETTINGS -> {
                        if (settingsViewModel.state.value.inSubList) {
                            navController?.popBackStack()
                            systemListViewModel.scan()
                        }
                    }
                }
                else -> {}
            }
        }

        inputHandler.onSelect = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> dialogState.value = ds.copy(caps = !ds.caps)
                is DialogState.NewCollectionInput -> dialogState.value = ds.copy(caps = !ds.caps)
                is DialogState.CollectionRenameInput -> dialogState.value = ds.copy(caps = !ds.caps)
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> {
                        if (systemListViewModel.isReorderMode()) {
                            systemListViewModel.confirmReorder()
                        } else {
                            systemListViewModel.enterReorderMode()
                        }
                    }
                    Screen.GAME_LIST -> {
                        val glState = gameListViewModel.state.value
                        if (glState.isCollectionsList) {
                            if (gameListViewModel.isReorderMode()) {
                                gameListViewModel.confirmReorder()
                            } else {
                                gameListViewModel.enterReorderMode()
                            }
                        } else if (!glState.isCollectionsList) {
                            if (gameListViewModel.isMultiSelectMode()) {
                                gameListViewModel.confirmMultiSelect()
                            } else {
                                gameListViewModel.enterMultiSelect()
                            }
                        }
                    }
                    else -> {}
                }
                else -> {}
            }
        }

        inputHandler.onX = {
            when (val ds = dialogState.value) {
                is DialogState.CollectionPicker -> {
                    dialogState.value = DialogState.NewCollectionInput(gamePaths = ds.gamePaths)
                }
                else -> {}
            }
        }

        inputHandler.onY = {
            when (dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    dialogState.value = DialogState.None
                }
                else -> {}
            }
        }

        inputHandler.onL1 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> if (ds.cursorPos > 0) dialogState.value = ds.copy(cursorPos = ds.cursorPos - 1)
                is DialogState.NewCollectionInput -> if (ds.cursorPos > 0) dialogState.value = ds.copy(cursorPos = ds.cursorPos - 1)
                is DialogState.CollectionRenameInput -> if (ds.cursorPos > 0) dialogState.value = ds.copy(cursorPos = ds.cursorPos - 1)
                DialogState.None -> if (currentScreen() == Screen.GAME_LIST) switchPlatform(-1)
                else -> {}
            }
        }

        inputHandler.onR1 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> if (ds.cursorPos < ds.currentName.length) dialogState.value = ds.copy(cursorPos = ds.cursorPos + 1)
                is DialogState.NewCollectionInput -> if (ds.cursorPos < ds.currentName.length) dialogState.value = ds.copy(cursorPos = ds.cursorPos + 1)
                is DialogState.CollectionRenameInput -> if (ds.cursorPos < ds.currentName.length) dialogState.value = ds.copy(cursorPos = ds.cursorPos + 1)
                DialogState.None -> if (currentScreen() == Screen.GAME_LIST) switchPlatform(1)
                else -> {}
            }
        }

        inputHandler.onL2 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> dialogState.value = ds.copy(cursorPos = 0)
                is DialogState.NewCollectionInput -> dialogState.value = ds.copy(cursorPos = 0)
                is DialogState.CollectionRenameInput -> dialogState.value = ds.copy(cursorPos = 0)
                else -> {}
            }
        }

        inputHandler.onR2 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> dialogState.value = ds.copy(cursorPos = ds.currentName.length)
                is DialogState.NewCollectionInput -> dialogState.value = ds.copy(cursorPos = ds.currentName.length)
                is DialogState.CollectionRenameInput -> dialogState.value = ds.copy(cursorPos = ds.currentName.length)
                else -> {}
            }
        }
    }

    private fun onSystemListConfirm() {
        if (navigating) return
        when (val item = systemListViewModel.getSelectedItem()) {
            is SystemListViewModel.ListItem.FavoritesItem -> {
                navigating = true
                gameListViewModel.loadCollection("Favorites") {
                    navController?.navigate(Routes.gameList("collection_Favorites"))
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionsFolder -> {
                navigating = true
                gameListViewModel.loadCollectionsList {
                    navController?.navigate(Routes.gameList("collections"))
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.PlatformItem -> {
                navigating = true
                gameListViewModel.loadPlatform(item.platform.tag) {
                    navController?.navigate(Routes.gameList(item.platform.tag))
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                navigating = true
                gameListViewModel.loadCollection(item.name) {
                    navController?.navigate(Routes.gameList("collection_${item.name}"))
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.ToolItem -> {
                val result = apkLauncher.launch(item.packageName)
                if (result is LaunchResult.AppNotInstalled) {
                    dialogState.value = DialogState.MissingApp(item.packageName)
                }
            }
            is SystemListViewModel.ListItem.PortItem -> {
                val result = apkLauncher.launch(item.packageName)
                if (result is LaunchResult.AppNotInstalled) {
                    dialogState.value = DialogState.MissingApp(item.packageName)
                }
            }
            else -> {}
        }
    }

    private fun onGameListConfirm() {
        if (navigating) return
        val game = gameListViewModel.getSelectedGame() ?: return

        if (gameListViewModel.state.value.isCollectionsList) {
            navigating = true
            gameListViewModel.loadCollection(game.displayName) {
                navigating = false
            }
            return
        }

        if (game.isSubfolder) {
            gameListViewModel.enterSubfolder(game.file.name)
            return
        }

        val result = when (val target = game.launchTarget) {
            is LaunchTarget.RetroArch -> {
                val core = platformResolver.getCoreName(game.platformTag)
                if (core != null) {
                    retroArchLauncher.launch(game.file, core)
                } else {
                    LaunchResult.CoreNotInstalled("unknown")
                }
            }
            is LaunchTarget.EmuLaunch -> {
                emuLauncher.launch(game.file, target.packageName, target.activityName, target.action)
            }
            is LaunchTarget.ApkLaunch -> {
                apkLauncher.launch(target.packageName)
            }
        }

        when (result) {
            is LaunchResult.CoreNotInstalled -> {
                dialogState.value = DialogState.MissingCore(result.coreName)
            }
            is LaunchResult.AppNotInstalled -> {
                dialogState.value = DialogState.MissingApp(result.packageName)
            }
            is LaunchResult.Error -> {
                dialogState.value = DialogState.MissingApp(result.message)
            }
            LaunchResult.Success -> {}
        }
    }

    private fun onContextMenuConfirm(state: DialogState.ContextMenu) {
        val game = gameListViewModel.getSelectedGame() ?: return
        val glState = gameListViewModel.state.value
        when (state.options[state.selectedOption]) {
            "Rename" -> {
                if (glState.isCollectionsList) {
                    dialogState.value = DialogState.CollectionRenameInput(
                        oldName = game.displayName,
                        currentName = game.displayName,
                        cursorPos = game.displayName.length
                    )
                } else {
                    dialogState.value = DialogState.RenameInput(
                        gameName = game.displayName,
                        currentName = game.displayName,
                        cursorPos = game.displayName.length
                    )
                }
            }
            "Delete" -> {
                if (glState.isCollectionsList) {
                    dialogState.value = DialogState.DeleteCollectionConfirm(collectionName = game.displayName)
                } else {
                    dialogState.value = DialogState.DeleteConfirm(gameName = game.displayName)
                }
            }
            "Manage Collections" -> {
                openCollectionManager(listOf(game.file.absolutePath), game.displayName)
            }
            "Add to Favorites" -> {
                ioScope.launch {
                    scanner.addToCollection("Favorites", game.file.absolutePath)
                    systemListViewModel.scan()
                }
                dialogState.value = DialogState.None
            }
            "Remove from Favorites" -> {
                ioScope.launch {
                    scanner.removeFromCollection("Favorites", game.file.absolutePath)
                    systemListViewModel.scan()
                }
                dialogState.value = DialogState.None
            }
        }
    }

    private fun onDeleteConfirm() {
        val game = gameListViewModel.getSelectedGame() ?: return
        ioScope.launch {
            scanner.deleteGame(game)
            gameListViewModel.reload()
        }
        dialogState.value = DialogState.None
    }

    private fun onCollectionPickerConfirm(state: DialogState.CollectionPicker) {
        val added = state.checkedIndices - state.initialChecked
        val removed = state.initialChecked - state.checkedIndices
        val toAdd = added.mapNotNull { state.collections.getOrNull(it) }
        val toRemove = removed.mapNotNull { state.collections.getOrNull(it) }
        if (toAdd.isNotEmpty() || toRemove.isNotEmpty()) {
            ioScope.launch {
                for (path in state.gamePaths) {
                    toAdd.forEach { collName -> scanner.addToCollection(collName, path) }
                    toRemove.forEach { collName -> scanner.removeFromCollection(collName, path) }
                }
                gameListViewModel.reload()
                systemListViewModel.scan()
            }
        }
        dialogState.value = DialogState.None
    }

    private fun openCollectionManager(gamePaths: List<String>, title: String) {
        val allCollections = scanner.getCollectionNames()
            .filter { !it.equals("Favorites", ignoreCase = true) }
        val alreadyIn = if (gamePaths.size == 1) {
            scanner.getCollectionsContaining(gamePaths[0])
        } else emptySet()
        val initialChecked = allCollections.indices
            .filter { allCollections[it] in alreadyIn }
            .toSet()
        dialogState.value = DialogState.CollectionPicker(
            gamePaths = gamePaths,
            title = title,
            collections = allCollections,
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        )
    }

    private fun onBulkContextMenuConfirm(state: DialogState.BulkContextMenu) {
        when (state.options[state.selectedOption]) {
            "Add to Favorites" -> {
                ioScope.launch {
                    state.gamePaths.forEach { path ->
                        scanner.addToCollection("Favorites", path)
                    }
                    systemListViewModel.scan()
                }
                dialogState.value = DialogState.None
            }
            "Manage Collections" -> {
                openCollectionManager(state.gamePaths, "${state.gamePaths.size} Selected")
            }
            "Delete" -> {
                dialogState.value = DialogState.DeleteConfirm(gameName = "${state.gamePaths.size} items")
            }
            "Remove from Collection" -> {
                val collName = gameListViewModel.state.value.collectionName ?: return
                ioScope.launch {
                    state.gamePaths.forEach { path ->
                        scanner.removeFromCollection(collName, path)
                    }
                    gameListViewModel.reload()
                }
                dialogState.value = DialogState.None
            }
        }
    }

    private fun handleKeyboardConfirm(
        caps: Boolean, symbols: Boolean, keyRow: Int, keyCol: Int,
        currentName: String, cursorPos: Int,
        onChar: (String, Int) -> Unit,
        onShift: () -> Unit,
        onSymbols: () -> Unit,
        onEnter: () -> Unit
    ) {
        val rows = getKeyboardRows(caps, symbols)
        val row = rows.getOrNull(keyRow) ?: return
        val key = row.getOrNull(keyCol) ?: return

        when (key) {
            KEY_SHIFT -> onShift()
            KEY_SYMBOLS -> onSymbols()
            KEY_ENTER -> onEnter()
            KEY_BACKSPACE -> {
                if (cursorPos > 0) {
                    val newName = currentName.removeRange(cursorPos - 1, cursorPos)
                    onChar(newName, cursorPos - 1)
                }
            }
            KEY_SPACE -> {
                val newName = currentName.substring(0, cursorPos) + " " + currentName.substring(cursorPos)
                onChar(newName, cursorPos + 1)
            }
            else -> {
                val newName = currentName.substring(0, cursorPos) + key + currentName.substring(cursorPos)
                onChar(newName, cursorPos + 1)
            }
        }
    }

    private fun onNewCollectionConfirm(state: DialogState.NewCollectionInput) {
        val name = state.currentName.trim()
        if (name.isEmpty()) {
            dialogState.value = DialogState.None
            return
        }
        ioScope.launch {
            scanner.createCollection(name)
            state.gamePaths.forEach { path ->
                scanner.addToCollection(name, path)
            }
            gameListViewModel.reload()
            systemListViewModel.scan()
        }
        dialogState.value = DialogState.CollectionCreated(collectionName = name)
    }

    private fun onCollectionRenameConfirm(state: DialogState.CollectionRenameInput) {
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == state.oldName) {
            dialogState.value = DialogState.None
            return
        }
        dialogState.value = DialogState.None
        ioScope.launch {
            scanner.renameCollection(state.oldName, newName)
            gameListViewModel.loadCollectionsList()
        }
    }

    private fun onRenameConfirm(state: DialogState.RenameInput) {
        val game = gameListViewModel.getSelectedGame() ?: return
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == game.displayName) {
            dialogState.value = DialogState.None
            return
        }

        dialogState.value = DialogState.None
        ioScope.launch {
            atomicRename.rename(game.file, newName, game.platformTag)
            gameListViewModel.reload()
        }
    }

    private fun switchPlatform(delta: Int) {
        if (navigating) return
        val tags = systemListViewModel.getPlatformTags()
        if (tags.isEmpty()) return

        val currentTag = gameListViewModel.state.value.platformTag
        val currentIndex = tags.indexOf(currentTag)
        if (currentIndex == -1) return

        val newIndex = (currentIndex + delta).let { i ->
            when {
                i < 0 -> tags.size - 1
                i >= tags.size -> 0
                else -> i
            }
        }

        val newTag = tags[newIndex]
        navigating = true
        gameListViewModel.loadPlatform(newTag) {
            navController?.let { nav ->
                nav.popBackStack()
                nav.navigate(Routes.gameList(newTag))
            }
            navigating = false
        }
    }

    private fun currentScreen(): Screen {
        val route = navController?.currentDestination?.route ?: return Screen.SYSTEM_LIST
        return when {
            route.startsWith("game_list") -> Screen.GAME_LIST
            route == Routes.SETTINGS -> Screen.SETTINGS
            else -> Screen.SYSTEM_LIST
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            storagePermissionLauncher.launch(intent)
        } else {
            legacyPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private enum class Screen {
        SYSTEM_LIST, GAME_LIST, SETTINGS
    }
}
