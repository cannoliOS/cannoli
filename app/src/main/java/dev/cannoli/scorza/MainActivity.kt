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
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.components.COLOR_GRID_COLS
import dev.cannoli.scorza.ui.components.HEX_KEYS
import dev.cannoli.scorza.ui.components.KEY_BACKSPACE
import dev.cannoli.scorza.ui.components.KEY_ENTER
import dev.cannoli.scorza.ui.components.KEY_SHIFT
import dev.cannoli.scorza.ui.components.KEY_SPACE
import dev.cannoli.scorza.ui.components.KEY_SYMBOLS
import dev.cannoli.scorza.ui.components.getKeyboardRows
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardInputState
import dev.cannoli.scorza.ui.theme.COLOR_PRESETS
import dev.cannoli.scorza.ui.theme.CannoliTheme
import dev.cannoli.scorza.ui.theme.colorToArgbLong
import dev.cannoli.scorza.ui.theme.hexToColor
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

    // Tracks context menu to return to after sub-dialog actions
    private sealed interface ContextReturn {
        data class Single(val gameName: String, val options: List<String>) : ContextReturn
        data class Bulk(val gamePaths: List<String>, val options: List<String>) : ContextReturn
    }
    private var pendingContextReturn: ContextReturn? = null

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
            rescanSystemList()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN &&
            intent.categories?.contains(Intent.CATEGORY_HOME) == true &&
            ::inputHandler.isInitialized
        ) {
            inputHandler.onStart()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (::inputHandler.isInitialized && inputHandler.handleKeyEvent(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun initializeApp() {
        val root = File(settings.sdCardRoot)
        val coreInfo = dev.cannoli.scorza.scanner.CoreInfoRepository(assets)
        coreInfo.load()
        platformResolver = PlatformResolver(root, coreInfo)
        platformResolver.load()

        scanner = FileScanner(root, platformResolver)
        scanner.ensureDirectories()
        ensureRetroArchConfig(root)

        systemListViewModel = SystemListViewModel(scanner)
        gameListViewModel = GameListViewModel(scanner, platformResolver)
        settingsViewModel = SettingsViewModel(settings, root)
        atomicRename = AtomicRename(root)

        retroArchLauncher = RetroArchLauncher(this, settings.retroArchPackage, root.absolutePath)
        emuLauncher = EmuLauncher(this)
        apkLauncher = ApkLauncher(this)

        inputHandler = InputHandler(
            getButtonLayout = { settings.buttonLayout },
            getSwapStartSelect = { settings.swapStartSelect }
        )
        wireInput()

        rescanSystemList()

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
        inputHandler.onUp = {
            when (val ds = dialogState.value) {
                is DialogState.ContextMenu,
                is DialogState.BulkContextMenu -> {
                    ds.withMenuDelta(-1)?.let { dialogState.value = it }
                }
                is DialogState.CollectionPicker -> {
                    if (ds.collections.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex <= 0) ds.collections.lastIndex else ds.selectedIndex - 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    val ks = ds.asKeyboardState()!!
                    val rows = getKeyboardRows(ks.caps, ks.symbols)
                    val newRow = if (ks.keyRow <= 0) rows.lastIndex else ks.keyRow - 1
                    val newCol = ks.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.withKeyboard(newRow, newCol)
                }
                is DialogState.CoreMappingList -> {
                    if (ds.mappings.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex <= 0) ds.mappings.lastIndex else ds.selectedIndex - 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.CorePicker -> {
                    if (ds.cores.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex <= 0) ds.cores.lastIndex else ds.selectedIndex - 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.AppPicker -> {
                    if (ds.apps.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex <= 0) ds.apps.lastIndex else ds.selectedIndex - 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.ColorList -> {
                    if (ds.colors.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex <= 0) ds.colors.lastIndex else ds.selectedIndex - 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.ColorPicker -> {
                    val totalRows = (COLOR_PRESETS.size + COLOR_GRID_COLS - 1) / COLOR_GRID_COLS
                    val newRow = if (ds.selectedRow <= 0) totalRows - 1 else ds.selectedRow - 1
                    dialogState.value = ds.copy(selectedRow = newRow)
                }
                is DialogState.HexColorInput -> {
                    val rowSize = 9
                    val curRow = ds.selectedIndex / rowSize
                    val col = ds.selectedIndex % rowSize
                    val totalRows = (HEX_KEYS.size + rowSize - 1) / rowSize
                    val newRow = if (curRow <= 0) totalRows - 1 else curRow - 1
                    val newIdx = (newRow * rowSize + col).coerceAtMost(HEX_KEYS.lastIndex)
                    dialogState.value = ds.copy(selectedIndex = newIdx)
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
                is DialogState.ContextMenu,
                is DialogState.BulkContextMenu -> {
                    ds.withMenuDelta(1)?.let { dialogState.value = it }
                }
                is DialogState.CollectionPicker -> {
                    if (ds.collections.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex >= ds.collections.lastIndex) 0 else ds.selectedIndex + 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    val ks = ds.asKeyboardState()!!
                    val rows = getKeyboardRows(ks.caps, ks.symbols)
                    val newRow = if (ks.keyRow >= rows.lastIndex) 0 else ks.keyRow + 1
                    val newCol = ks.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.withKeyboard(newRow, newCol)
                }
                is DialogState.CoreMappingList -> {
                    if (ds.mappings.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex >= ds.mappings.lastIndex) 0 else ds.selectedIndex + 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.CorePicker -> {
                    if (ds.cores.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex >= ds.cores.lastIndex) 0 else ds.selectedIndex + 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.AppPicker -> {
                    if (ds.apps.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex >= ds.apps.lastIndex) 0 else ds.selectedIndex + 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.ColorList -> {
                    if (ds.colors.isNotEmpty()) {
                        val newIdx = if (ds.selectedIndex >= ds.colors.lastIndex) 0 else ds.selectedIndex + 1
                        dialogState.value = ds.copy(selectedIndex = newIdx)
                    }
                }
                is DialogState.ColorPicker -> {
                    val totalRows = (COLOR_PRESETS.size + COLOR_GRID_COLS - 1) / COLOR_GRID_COLS
                    val newRow = if (ds.selectedRow >= totalRows - 1) 0 else ds.selectedRow + 1
                    dialogState.value = ds.copy(selectedRow = newRow)
                }
                is DialogState.HexColorInput -> {
                    val rowSize = 9
                    val curRow = ds.selectedIndex / rowSize
                    val col = ds.selectedIndex % rowSize
                    val totalRows = (HEX_KEYS.size + rowSize - 1) / rowSize
                    val newRow = if (curRow >= totalRows - 1) 0 else curRow + 1
                    val newIdx = (newRow * rowSize + col).coerceAtMost(HEX_KEYS.lastIndex)
                    dialogState.value = ds.copy(selectedIndex = newIdx)
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
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    val ks = ds.asKeyboardState()!!
                    val rows = getKeyboardRows(ks.caps, ks.symbols)
                    val rowSize = rows[ks.keyRow.coerceIn(0, rows.lastIndex)].size
                    val newCol = if (ks.keyCol <= 0) rowSize - 1 else ks.keyCol - 1
                    dialogState.value = ds.withKeyboard(ks.keyRow, newCol)
                }
                is DialogState.ColorPicker -> {
                    val newCol = if (ds.selectedCol <= 0) COLOR_GRID_COLS - 1 else ds.selectedCol - 1
                    dialogState.value = ds.copy(selectedCol = newCol)
                }
                is DialogState.HexColorInput -> {
                    val rowSize = 9
                    val curRow = ds.selectedIndex / rowSize
                    val col = ds.selectedIndex % rowSize
                    val newCol = if (col <= 0) rowSize - 1 else col - 1
                    dialogState.value = ds.copy(selectedIndex = curRow * rowSize + newCol)
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
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    val ks = ds.asKeyboardState()!!
                    val rows = getKeyboardRows(ks.caps, ks.symbols)
                    val rowSize = rows[ks.keyRow.coerceIn(0, rows.lastIndex)].size
                    val newCol = if (ks.keyCol >= rowSize - 1) 0 else ks.keyCol + 1
                    dialogState.value = ds.withKeyboard(ks.keyRow, newCol)
                }
                is DialogState.ColorPicker -> {
                    val newCol = if (ds.selectedCol >= COLOR_GRID_COLS - 1) 0 else ds.selectedCol + 1
                    dialogState.value = ds.copy(selectedCol = newCol)
                }
                is DialogState.HexColorInput -> {
                    val rowSize = 9
                    val curRow = ds.selectedIndex / rowSize
                    val col = ds.selectedIndex % rowSize
                    val newCol = if (col >= rowSize - 1) 0 else col + 1
                    dialogState.value = ds.copy(selectedIndex = curRow * rowSize + newCol)
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
                is DialogState.AppPicker -> {
                    val newChecked = if (ds.selectedIndex in ds.checkedIndices) {
                        ds.checkedIndices - ds.selectedIndex
                    } else {
                        ds.checkedIndices + ds.selectedIndex
                    }
                    dialogState.value = ds.copy(checkedIndices = newChecked)
                }
                is DialogState.CoreMappingList -> {
                    val entry = ds.mappings[ds.selectedIndex]
                    val options = platformResolver.getCorePickerOptions(entry.tag)
                    val currentCore = platformResolver.getCoreMapping(entry.tag)
                    val currentRunner = entry.runnerLabel
                    val selectedIdx = options.indexOfFirst { it.coreId == currentCore && it.runnerLabel == currentRunner }
                        .coerceAtLeast(options.indexOfFirst { it.coreId == currentCore }.coerceAtLeast(0))
                    dialogState.value = DialogState.CorePicker(
                        tag = entry.tag,
                        platformName = entry.platformName,
                        cores = options,
                        selectedIndex = selectedIdx
                    )
                }
                is DialogState.CorePicker -> onCorePickerConfirm(ds)
                is DialogState.ColorList -> {
                    val entry = ds.colors[ds.selectedIndex]
                    openColorPicker(entry.key)
                }
                is DialogState.ColorPicker -> {
                    val idx = ds.selectedRow * COLOR_GRID_COLS + ds.selectedCol
                    val preset = COLOR_PRESETS.getOrNull(idx)
                    if (preset != null) {
                        val hex = "#%06X".format(preset.color and 0xFFFFFF)
                        settingsViewModel.setColor(ds.settingKey, hex)
                        val entries = settingsViewModel.getColorEntries()
                        dialogState.value = DialogState.ColorList(
                            colors = entries,
                            selectedIndex = entries.indexOfFirst { it.key == ds.settingKey }.coerceAtLeast(0)
                        )
                    }
                }
                is DialogState.HexColorInput -> {
                    val key = HEX_KEYS.getOrNull(ds.selectedIndex) ?: ""
                    when (key) {
                        "" -> {}
                        "←" -> {
                            if (ds.currentHex.isNotEmpty()) {
                                dialogState.value = ds.copy(currentHex = ds.currentHex.dropLast(1))
                            }
                        }
                        "↵" -> {
                            if (ds.currentHex.length == 6) {
                                settingsViewModel.setColor(ds.settingKey, "#${ds.currentHex}")
                                dialogState.value = DialogState.None
                            }
                        }
                        else -> {
                            if (ds.currentHex.length < 6) {
                                dialogState.value = ds.copy(currentHex = ds.currentHex + key)
                            }
                        }
                    }
                }
                is DialogState.DeleteCollectionConfirm -> {
                    val name = ds.collectionName
                    pendingContextReturn = null
                    dialogState.value = DialogState.None
                    ioScope.launch {
                        scanner.deleteCollection(name)
                        val remaining = scanner.scanCollections()
                            .filter { !it.name.equals("Favorites", ignoreCase = true) && it.entries.isNotEmpty() }
                        if (remaining.isEmpty()) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                navController?.popBackStack()
                                rescanSystemList()
                            }
                        } else {
                            gameListViewModel.loadCollectionsList(restoreIndex = true)
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
                            val cat = settingsViewModel.state.value.categories.getOrNull(settingsViewModel.state.value.categoryIndex)
                            if (cat?.key == "about") {
                                dialogState.value = DialogState.About
                            } else {
                                settingsViewModel.enterCategory()
                            }
                        } else {
                            val key = settingsViewModel.enterSelected()
                            if (key == "sd_root") {
                                folderPickerLauncher.launch(null)
                            } else if (key == "colors") {
                                dialogState.value = DialogState.ColorList(
                                    colors = settingsViewModel.getColorEntries()
                                )
                            } else if (key != null && key.startsWith("color_")) {
                                openColorPicker(key)
                            } else if (key == "core_mapping") {
                                dialogState.value = DialogState.CoreMappingList(
                                    mappings = platformResolver.getDetailedMappings()
                                )
                            } else if (key == "manage_tools") {
                                openAppPicker("tools")
                            } else if (key == "manage_ports") {
                                openAppPicker("ports")
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
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    ds.withBackspace()?.let { dialogState.value = it }
                }
                is DialogState.CorePicker -> {
                    if (ds.gamePath != null) {
                        restoreContextMenu()
                    } else {
                        val mappings = platformResolver.getDetailedMappings()
                        val idx = mappings.indexOfFirst { it.tag == ds.tag }.coerceAtLeast(0)
                        dialogState.value = DialogState.CoreMappingList(mappings = mappings, selectedIndex = idx)
                    }
                }
                is DialogState.CoreMappingList -> {
                    platformResolver.reloadCoreMappings()
                    dialogState.value = DialogState.None
                }
                is DialogState.AppPicker -> {
                    dialogState.value = DialogState.None
                }
                is DialogState.ColorList -> {
                    dialogState.value = DialogState.None
                }
                is DialogState.ColorPicker -> {
                    val entries = settingsViewModel.getColorEntries()
                    dialogState.value = DialogState.ColorList(
                        colors = entries,
                        selectedIndex = entries.indexOfFirst { it.key == ds.settingKey }.coerceAtLeast(0)
                    )
                }
                is DialogState.HexColorInput -> {
                    openColorPicker(ds.settingKey)
                }
                is DialogState.ContextMenu, is DialogState.BulkContextMenu -> {
                    pendingContextReturn = null
                    dialogState.value = DialogState.None
                }
                is DialogState.DeleteConfirm,
                is DialogState.DeleteCollectionConfirm -> {
                    restoreContextMenu()
                }
                is DialogState.CollectionPicker -> {
                    restoreContextMenu()
                }
                is DialogState.CollectionCreated -> {
                    // Return to collection picker, which will return to context menu on its own dismiss
                    val ret = pendingContextReturn
                    val gamePaths = when (ret) {
                        is ContextReturn.Single -> {
                            val game = gameListViewModel.getSelectedGame()
                            if (game != null) listOf(game.file.absolutePath) else emptyList()
                        }
                        is ContextReturn.Bulk -> ret.gamePaths
                        null -> emptyList()
                    }
                    val title = when (ret) {
                        is ContextReturn.Single -> ret.gameName
                        is ContextReturn.Bulk -> "${ret.gamePaths.size} Selected"
                        null -> ""
                    }
                    if (gamePaths.isNotEmpty()) {
                        openCollectionManager(gamePaths, title)
                    } else {
                        restoreContextMenu()
                    }
                }
                is DialogState.RenameResult -> {
                    restoreContextMenu()
                }
                is DialogState.MissingCore,
                is DialogState.MissingApp -> {
                    dialogState.value = DialogState.None
                }
                DialogState.About -> {
                    dialogState.value = DialogState.None
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> {
                        if (systemListViewModel.isMultiSelectMode()) systemListViewModel.cancelMultiSelect()
                        else if (systemListViewModel.isReorderMode()) systemListViewModel.cancelReorder(showTools = settings.showTools, showPorts = settings.showPorts, showEmpty = settings.showEmpty, toolsName = settings.toolsName, portsName = settings.portsName)
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
                                    gameListViewModel.loadCollectionsList(restoreIndex = true)
                                } else {
                                    navController?.popBackStack()
                                    rescanSystemList()
                                }
                            }
                        }
                    }
                    Screen.SETTINGS -> {
                        if (settingsViewModel.state.value.inSubList) {
                            settingsViewModel.cancel()
                            settingsViewModel.exitSubList()
                        } else {
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
                is DialogState.CorePicker -> onCorePickerConfirm(ds)
                is DialogState.AppPicker -> onAppPickerConfirm(ds)
                is DialogState.CoreMappingList -> {
                    platformResolver.saveCoreMappings()
                    dialogState.value = DialogState.None
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> {
                        if (systemListViewModel.isMultiSelectMode()) {
                            systemListViewModel.confirmMultiSelect()
                        } else if (systemListViewModel.isReorderMode()) {
                            systemListViewModel.confirmReorder()
                        } else {
                            onSystemListContextMenu()
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
                        if (glState.platformTag == "tools" || glState.platformTag == "ports") {
                            // No context menu for tools/ports
                        } else {
                        val game = gameListViewModel.getSelectedGame()
                        if (game != null) {
                            if (glState.isCollectionsList) {
                                dialogState.value = DialogState.ContextMenu(
                                    gameName = game.displayName,
                                    options = listOf("Rename", "Delete")
                                )
                            } else if (!game.isSubfolder) {
                                dialogState.value = DialogState.ContextMenu(
                                    gameName = game.displayName,
                                    options = buildGameContextOptions(game)
                                )
                            }
                        }
                        }
                        }
                    }
                    Screen.SETTINGS -> {
                        if (settingsViewModel.state.value.inSubList) {
                            settingsViewModel.save()
                            settingsViewModel.exitSubList()
                            rescanSystemList()
                        }
                    }
                }
                else -> {}
            }
        }

        inputHandler.onSelect = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    val ks = ds.asKeyboardState()!!
                    dialogState.value = ds.withCaps(!ks.caps)
                }
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
                        } else if (!glState.isCollectionsList && glState.subfolderPath == null && glState.platformTag != "tools" && glState.platformTag != "ports") {
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
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    ds.withInsertedChar(" ")?.let { dialogState.value = it }
                }
                is DialogState.ColorPicker -> {
                    val currentHex = settingsViewModel.getColorHex(ds.settingKey).removePrefix("#")
                    dialogState.value = DialogState.HexColorInput(
                        settingKey = ds.settingKey,
                        currentHex = currentHex
                    )
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> {
                        settingsViewModel.load()
                        navController?.navigate(Routes.SETTINGS)
                    }
                    else -> {}
                }
                else -> {}
            }
        }

        inputHandler.onY = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.CollectionRenameInput -> {
                    restoreContextMenu()
                }
                is DialogState.NewCollectionInput -> {
                    // Return to collection picker
                    val ret = pendingContextReturn
                    val gamePaths = when (ret) {
                        is ContextReturn.Single -> {
                            val game = gameListViewModel.getSelectedGame()
                            if (game != null) listOf(game.file.absolutePath) else emptyList()
                        }
                        is ContextReturn.Bulk -> ret.gamePaths
                        null -> emptyList()
                    }
                    val title = when (ret) {
                        is ContextReturn.Single -> ret.gameName
                        is ContextReturn.Bulk -> "${ret.gamePaths.size} Selected"
                        null -> ""
                    }
                    if (gamePaths.isNotEmpty()) {
                        openCollectionManager(gamePaths, title)
                    } else {
                        restoreContextMenu()
                    }
                }
                else -> {}
            }
        }

        inputHandler.onL1 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    val ks = ds.asKeyboardState()!!
                    if (ks.cursorPos > 0) dialogState.value = ds.withCursor(ks.cursorPos - 1)
                }
                DialogState.None -> if (currentScreen() == Screen.GAME_LIST && settings.platformSwitching) switchPlatform(-1)
                else -> {}
            }
        }

        inputHandler.onR1 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    val ks = ds.asKeyboardState()!!
                    if (ks.cursorPos < ks.currentName.length) dialogState.value = ds.withCursor(ks.cursorPos + 1)
                }
                DialogState.None -> if (currentScreen() == Screen.GAME_LIST && settings.platformSwitching) switchPlatform(1)
                else -> {}
            }
        }

        inputHandler.onL2 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    dialogState.value = ds.withCursor(0)
                }
                else -> {}
            }
        }

        inputHandler.onR2 = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    val ks = ds.asKeyboardState()!!
                    dialogState.value = ds.withCursor(ks.currentName.length)
                }
                else -> {}
            }
        }
    }

    private fun getInstalledLauncherApps(): List<Pair<String, String>> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        return resolveInfos
            .mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) return@mapNotNull null
                val label = ri.loadLabel(packageManager).toString()
                label to pkg
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    private fun openAppPicker(type: String) {
        val allApps = getInstalledLauncherApps()
        val dir = if (type == "tools") scanner.tools else scanner.ports
        val existing = scanner.scanApkLaunches(dir).map { it.packageName }.toSet()
        val initialChecked = allApps.indices.filter { allApps[it].second in existing }.toSet()
        val title = if (type == "tools") "Manage Tools" else "Manage Ports"
        dialogState.value = DialogState.AppPicker(
            type = type,
            title = title,
            apps = allApps.map { it.first },
            packages = allApps.map { it.second },
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        )
    }

    private fun onAppPickerConfirm(state: DialogState.AppPicker) {
        val selected = state.checkedIndices.mapNotNull { idx ->
            val name = state.apps.getOrNull(idx) ?: return@mapNotNull null
            val pkg = state.packages.getOrNull(idx) ?: return@mapNotNull null
            name to pkg
        }
        val dir = if (state.type == "tools") scanner.tools else scanner.ports
        ioScope.launch {
            scanner.syncApkLaunches(dir, selected)
            rescanSystemList()
        }
        dialogState.value = DialogState.None
    }

    private fun rescanSystemList() {
        systemListViewModel.scan(
            showTools = settings.showTools,
            showPorts = settings.showPorts,
            showEmpty = settings.showEmpty,
            toolsName = settings.toolsName,
            portsName = settings.portsName
        )
    }

    private fun openColorPicker(settingKey: String) {
        val hex = settingsViewModel.getColorHex(settingKey)
        val color = hexToColor(hex) ?: androidx.compose.ui.graphics.Color.White
        val argb = colorToArgbLong(color)
        val idx = COLOR_PRESETS.indexOfFirst { it.color == argb }
        val row = if (idx >= 0) idx / COLOR_GRID_COLS else 0
        val col = if (idx >= 0) idx % COLOR_GRID_COLS else 0
        dialogState.value = DialogState.ColorPicker(
            settingKey = settingKey,
            currentColor = argb,
            selectedRow = row,
            selectedCol = col
        )
    }

    private fun onSystemListContextMenu() {
        val item = systemListViewModel.getSelectedItem() ?: return
        val name = when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> item.platform.displayName
            is SystemListViewModel.ListItem.ToolsFolder -> item.name
            is SystemListViewModel.ListItem.PortsFolder -> item.name
            else -> return
        }
        dialogState.value = DialogState.ContextMenu(
            gameName = name,
            options = listOf("Rename")
        )
    }

    private fun onSystemListRename(state: DialogState.RenameInput) {
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == state.gameName) {
            dialogState.value = DialogState.None
            return
        }
        val item = systemListViewModel.getSelectedItem()
        when (item) {
            is SystemListViewModel.ListItem.PlatformItem -> {
                ioScope.launch {
                    platformResolver.setDisplayName(item.platform.tag, newName)
                    rescanSystemList()
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                settings.toolsName = newName
                rescanSystemList()
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                settings.portsName = newName
                rescanSystemList()
            }
            else -> {}
        }
        dialogState.value = DialogState.None
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
            is SystemListViewModel.ListItem.ToolsFolder -> {
                navigating = true
                gameListViewModel.loadApkList("tools", item.name) {
                    navController?.navigate(Routes.gameList("tools"))
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                navigating = true
                gameListViewModel.loadApkList("ports", item.name) {
                    navController?.navigate(Routes.gameList("ports"))
                    navigating = false
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
                val gameOverride = platformResolver.getGameOverride(game.file.absolutePath)
                val core = gameOverride?.coreId ?: platformResolver.getCoreName(game.platformTag)
                if (core != null) {
                    val runnerPref = gameOverride?.runner ?: platformResolver.getRunnerPreference(game.platformTag)
                    if (runnerPref != "RetroArch") {
                        val embeddedCorePath = findEmbeddedCore(core)
                        if (embeddedCorePath != null) {
                            launchEmbedded(game, embeddedCorePath)
                            return
                        }
                    }
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
            is LaunchTarget.Embedded -> {
                launchEmbedded(game, target.corePath)
                return
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

    private fun ensureRetroArchConfig(root: File) {
        val configFile = File(root, "Config/retroarch.cfg")
        if (configFile.exists()) return
        configFile.parentFile?.mkdirs()
        val rootPath = root.absolutePath
        configFile.writeText(
            """
            savefile_directory = "$rootPath/Saves"
            savestate_directory = "$rootPath/Save States"
            system_directory = "$rootPath/BIOS"
            sort_savefiles_by_content_enable = "true"
            sort_savestates_by_content_enable = "true"
            """.trimIndent() + "\n"
        )
    }

    private fun findEmbeddedCore(coreName: String): String? {
        val coresDir = java.io.File(settings.sdCardRoot, "Config/Cores")
        val coreFile = java.io.File(coresDir, "${coreName}_android.so")
        return if (coreFile.exists()) coreFile.absolutePath else null
    }

    private fun launchEmbedded(game: dev.cannoli.scorza.model.Game, corePath: String) {
        val cannoliRoot = java.io.File(settings.sdCardRoot)
        val romName = game.file.nameWithoutExtension
        val saveDir = java.io.File(cannoliRoot, "Saves/${game.platformTag}")
        saveDir.mkdirs()

        val intent = android.content.Intent(this, dev.cannoli.scorza.libretro.LibretroActivity::class.java).apply {
            putExtra("game_title", game.displayName)
            putExtra("core_path", corePath)
            putExtra("rom_path", game.file.absolutePath)
            putExtra("sram_path", java.io.File(saveDir, "$romName.srm").absolutePath)
            val stateDir = java.io.File(cannoliRoot, "Save States/${game.platformTag}/$romName")
            stateDir.mkdirs()
            putExtra("state_path", java.io.File(stateDir, "$romName.state").absolutePath)
            putExtra("platform_tag", game.platformTag)
            putExtra("cannoli_root", cannoliRoot.absolutePath)
            putExtra("system_dir", java.io.File(cannoliRoot, "BIOS").absolutePath)
            putExtra("save_dir", saveDir.absolutePath)
            putExtra("color_highlight", settings.colorHighlight)
            putExtra("color_text", settings.colorText)
            putExtra("color_highlight_text", settings.colorHighlightText)
            putExtra("color_accent", settings.colorAccent)
            putExtra("show_wifi", settings.showWifi)
            putExtra("show_bluetooth", settings.showBluetooth)
            putExtra("show_clock", settings.showClock)
            putExtra("show_battery", settings.showBattery)
            putExtra("use_24h", settings.timeFormat == dev.cannoli.scorza.settings.TimeFormat.TWENTY_FOUR_HOUR)
        }
        startActivity(intent)
    }

    private fun onCorePickerConfirm(ds: DialogState.CorePicker) {
        if (ds.cores.isEmpty()) return
        val chosen = ds.cores[ds.selectedIndex]
        if (ds.gamePath != null) {
            if (chosen.coreId.isEmpty()) {
                platformResolver.setGameOverride(ds.gamePath, null, null)
            } else {
                val runner = if (chosen.runnerLabel == "Internal" || chosen.runnerLabel == "RetroArch") chosen.runnerLabel else null
                platformResolver.setGameOverride(ds.gamePath, chosen.coreId, runner)
            }
            restoreContextMenu()
        } else {
            val runner = if (chosen.runnerLabel == "Internal" || chosen.runnerLabel == "RetroArch") chosen.runnerLabel else null
            platformResolver.setCoreMapping(ds.tag, chosen.coreId, runner)
            val mappings = platformResolver.getDetailedMappings()
            val idx = mappings.indexOfFirst { it.tag == ds.tag }.coerceAtLeast(0)
            dialogState.value = DialogState.CoreMappingList(mappings = mappings, selectedIndex = idx)
        }
    }

    private fun buildGameContextOptions(game: dev.cannoli.scorza.model.Game): List<String> {
        val isFav = scanner.isInCollection("Favorites", game.file.absolutePath)
        val favOption = if (isFav) "Remove from Favorites" else "Add to Favorites"
        val options = mutableListOf(favOption, "Manage Collections", "Core Override", "Rename", "Delete")
        if (game.launchTarget !is LaunchTarget.RetroArch) {
            options.remove("Core Override")
        }
        return options
    }

    private fun onContextMenuConfirm(state: DialogState.ContextMenu) {
        if (currentScreen() == Screen.SYSTEM_LIST) {
            when (state.options[state.selectedOption]) {
                "Rename" -> {
                    dialogState.value = DialogState.RenameInput(
                        gameName = state.gameName,
                        currentName = state.gameName,
                        cursorPos = state.gameName.length
                    )
                }
            }
            return
        }
        val game = gameListViewModel.getSelectedGame() ?: return
        val glState = gameListViewModel.state.value
        pendingContextReturn = null
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
                    rescanSystemList()
                }
                dialogState.value = DialogState.None
            }
            "Remove from Favorites" -> {
                ioScope.launch {
                    scanner.removeFromCollection("Favorites", game.file.absolutePath)
                    rescanSystemList()
                }
                dialogState.value = DialogState.None
            }
            "Core Override" -> {
                val tag = game.platformTag
                val options = platformResolver.getCorePickerOptions(tag)
                val defaultOption = DialogState.CorePickerOption("", "Default (Platform Setting)", "")
                val allOptions = listOf(defaultOption) + options
                val override = platformResolver.getGameOverride(game.file.absolutePath)
                val selectedIdx = if (override != null) {
                    allOptions.indexOfFirst { it.coreId == override.coreId && (it.runnerLabel == override.runner || override.runner == null) }
                        .coerceAtLeast(0)
                } else {
                    0
                }
                dialogState.value = DialogState.CorePicker(
                    tag = tag,
                    platformName = game.displayName,
                    cores = allOptions,
                    selectedIndex = selectedIdx,
                    gamePath = game.file.absolutePath
                )
            }
        }
    }

    private fun onDeleteConfirm() {
        val game = gameListViewModel.getSelectedGame() ?: return
        pendingContextReturn = null
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
                rescanSystemList()
            }
        }
        restoreContextMenu()
    }

    private fun restoreContextMenu() {
        when (val ret = pendingContextReturn) {
            is ContextReturn.Single -> {
                val game = gameListViewModel.getSelectedGame()
                if (game != null && !game.isSubfolder) {
                    val glState = gameListViewModel.state.value
                    val options = if (glState.isCollectionsList) {
                        listOf("Rename", "Delete")
                    } else {
                        buildGameContextOptions(game)
                    }
                    dialogState.value = DialogState.ContextMenu(
                        gameName = game.displayName,
                        options = options
                    )
                } else {
                    pendingContextReturn = null
                    dialogState.value = DialogState.None
                }
            }
            is ContextReturn.Bulk -> {
                dialogState.value = DialogState.BulkContextMenu(
                    gamePaths = ret.gamePaths,
                    options = ret.options
                )
            }
            null -> dialogState.value = DialogState.None
        }
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
        pendingContextReturn = ContextReturn.Bulk(state.gamePaths, state.options)
        when (state.options[state.selectedOption]) {
            "Add to Favorites" -> {
                ioScope.launch {
                    state.gamePaths.forEach { path ->
                        scanner.addToCollection("Favorites", path)
                    }
                    rescanSystemList()
                }
                restoreContextMenu()
            }
            "Manage Collections" -> {
                openCollectionManager(state.gamePaths, "${state.gamePaths.size} Selected")
            }
            "Delete" -> {
                pendingContextReturn = null
                dialogState.value = DialogState.DeleteConfirm(gameName = "${state.gamePaths.size} items")
            }
            "Remove from Collection" -> {
                pendingContextReturn = null
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
            rescanSystemList()
        }
        dialogState.value = DialogState.CollectionCreated(collectionName = name)
    }

    private fun onCollectionRenameConfirm(state: DialogState.CollectionRenameInput) {
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == state.oldName) {
            restoreContextMenu()
            return
        }
        restoreContextMenu()
        ioScope.launch {
            scanner.renameCollection(state.oldName, newName)
            gameListViewModel.loadCollectionsList(restoreIndex = true)
        }
    }

    private fun onRenameConfirm(state: DialogState.RenameInput) {
        if (currentScreen() == Screen.SYSTEM_LIST) {
            onSystemListRename(state)
            return
        }
        val game = gameListViewModel.getSelectedGame() ?: return
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == game.displayName) {
            restoreContextMenu()
            return
        }

        restoreContextMenu()
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

        val newIndex = (currentIndex + delta).mod(tags.size)

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

    // -- Keyboard state helpers to reduce duplication across RenameInput/NewCollectionInput/CollectionRenameInput --

    private fun DialogState.asKeyboardState(): KeyboardInputState? = this as? KeyboardInputState

    private fun DialogState.withKeyboard(row: Int, col: Int): DialogState = when (this) {
        is DialogState.RenameInput -> copy(keyRow = row, keyCol = col)
        is DialogState.NewCollectionInput -> copy(keyRow = row, keyCol = col)
        is DialogState.CollectionRenameInput -> copy(keyRow = row, keyCol = col)
        else -> this
    }

    private fun DialogState.withCursor(pos: Int): DialogState = when (this) {
        is DialogState.RenameInput -> copy(cursorPos = pos)
        is DialogState.NewCollectionInput -> copy(cursorPos = pos)
        is DialogState.CollectionRenameInput -> copy(cursorPos = pos)
        else -> this
    }

    private fun DialogState.withCaps(caps: Boolean): DialogState = when (this) {
        is DialogState.RenameInput -> copy(caps = caps)
        is DialogState.NewCollectionInput -> copy(caps = caps)
        is DialogState.CollectionRenameInput -> copy(caps = caps)
        else -> this
    }

    private fun DialogState.withNameAndCursor(name: String, pos: Int): DialogState = when (this) {
        is DialogState.RenameInput -> copy(currentName = name, cursorPos = pos)
        is DialogState.NewCollectionInput -> copy(currentName = name, cursorPos = pos)
        is DialogState.CollectionRenameInput -> copy(currentName = name, cursorPos = pos)
        else -> this
    }

    /** Moves context menu selection by delta, wrapping around. */
    private fun DialogState.withMenuDelta(delta: Int): DialogState? = when (this) {
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

    private fun DialogState.withBackspace(): DialogState? {
        val ks = asKeyboardState() ?: return null
        if (ks.cursorPos <= 0) return null
        val newName = ks.currentName.removeRange(ks.cursorPos - 1, ks.cursorPos)
        return withNameAndCursor(newName, ks.cursorPos - 1)
    }

    private fun DialogState.withInsertedChar(char: String): DialogState? {
        val ks = asKeyboardState() ?: return null
        val newName = ks.currentName.substring(0, ks.cursorPos) + char + ks.currentName.substring(ks.cursorPos)
        return withNameAndCursor(newName, ks.cursorPos + 1)
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
