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
import androidx.compose.runtime.mutableStateListOf
import dev.cannoli.scorza.input.InputHandler
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.EmuLauncher
import dev.cannoli.scorza.launcher.LaunchResult
import dev.cannoli.scorza.launcher.RetroArchLauncher
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.navigation.AppNavGraph
import dev.cannoli.scorza.navigation.LauncherScreen
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
import dev.cannoli.scorza.ui.components.lastFirstVisibleIndex
import dev.cannoli.scorza.ui.components.lastVisibleCount
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.CoreMappingEntry
import dev.cannoli.scorza.ui.screens.CorePickerOption
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

    private val screenStack = mutableStateListOf<LauncherScreen>(LauncherScreen.SystemList)
    private val dialogState = MutableStateFlow<DialogState>(DialogState.None)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    @Volatile private var navigating = false

    private fun pushScreen(new: LauncherScreen) {
        val current = screenStack.last()
        screenStack[screenStack.lastIndex] = saveScrollPosition(current)
        screenStack.add(new)
    }

    private fun saveScrollPosition(screen: LauncherScreen): LauncherScreen = when (screen) {
        is LauncherScreen.CoreMapping -> screen.copy(scrollTarget = lastFirstVisibleIndex)
        is LauncherScreen.CorePicker -> screen.copy(scrollTarget = lastFirstVisibleIndex)
        is LauncherScreen.ColorList -> screen.copy(scrollTarget = lastFirstVisibleIndex)
        is LauncherScreen.CollectionPicker -> screen.copy(scrollTarget = lastFirstVisibleIndex)
        is LauncherScreen.AppPicker -> screen.copy(scrollTarget = lastFirstVisibleIndex)
        else -> screen
    }

    private fun screenPageJump(screen: LauncherScreen, itemCount: Int, selectedIndex: Int, direction: Int) {
        if (itemCount == 0) return
        val jump = lastVisibleCount.coerceAtLeast(1) * direction
        val newIdx = (selectedIndex + jump).coerceIn(0, itemCount - 1)
        if (newIdx == selectedIndex) return
        screenStack[screenStack.lastIndex] = when (screen) {
            is LauncherScreen.CoreMapping -> screen.copy(selectedIndex = newIdx)
            is LauncherScreen.CorePicker -> screen.copy(selectedIndex = newIdx)
            is LauncherScreen.ColorList -> screen.copy(selectedIndex = newIdx)
            is LauncherScreen.CollectionPicker -> screen.copy(selectedIndex = newIdx)
            is LauncherScreen.AppPicker -> screen.copy(selectedIndex = newIdx)
            else -> screen
        }
    }

    private fun updateColorListOnStack(settingKey: String, entries: List<ColorEntry>) {
        val cl = screenStack.last()
        if (cl is LauncherScreen.ColorList) {
            screenStack[screenStack.lastIndex] = cl.copy(
                colors = entries,
                selectedIndex = entries.indexOfFirst { it.key == settingKey }.coerceAtLeast(0)
            )
        }
    }

    private fun refreshCollectionPickerOnStack() {
        val cp = screenStack.last()
        if (cp is LauncherScreen.CollectionPicker) {
            val allCollections = scanner.getCollectionNames()
                .filter { !it.equals("Favorites", ignoreCase = true) }
            val alreadyIn = if (cp.gamePaths.size == 1) {
                scanner.getCollectionsContaining(cp.gamePaths[0])
            } else emptySet()
            val initialChecked = allCollections.indices
                .filter { allCollections[it] in alreadyIn }
                .toSet()
            screenStack[screenStack.lastIndex] = cp.copy(
                collections = allCollections,
                checkedIndices = initialChecked,
                initialChecked = initialChecked
            )
        }
    }

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
        platformResolver = PlatformResolver(root, assets, coreInfo)
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
                    AppNavGraph(
                        currentScreen = screenStack.last(),
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
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    val ks = ds.asKeyboardState()!!
                    val rows = getKeyboardRows(ks.caps, ks.symbols)
                    val newRow = if (ks.keyRow <= 0) rows.lastIndex else ks.keyRow - 1
                    val newCol = ks.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.withKeyboard(newRow, newCol)
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
                DialogState.None -> when (val screen = screenStack.last()) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveUp()
                        else systemListViewModel.moveSelection(-1)
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveUp()
                        else gameListViewModel.moveSelection(-1)
                    }
                    LauncherScreen.Settings -> settingsViewModel.moveSelection(-1)
                    is LauncherScreen.CoreMapping -> {
                        if (screen.mappings.isNotEmpty()) {
                            val newIdx = if (screen.selectedIndex <= 0) screen.mappings.lastIndex else screen.selectedIndex - 1
                            screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                        }
                    }
                    is LauncherScreen.CorePicker -> {
                        if (screen.cores.isNotEmpty()) {
                            val newIdx = if (screen.selectedIndex <= 0) screen.cores.lastIndex else screen.selectedIndex - 1
                            screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                        }
                    }
                    is LauncherScreen.AppPicker -> {
                        if (screen.apps.isNotEmpty()) {
                            val newIdx = if (screen.selectedIndex <= 0) screen.apps.lastIndex else screen.selectedIndex - 1
                            screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                        }
                    }
                    is LauncherScreen.ColorList -> {
                        if (screen.colors.isNotEmpty()) {
                            val newIdx = if (screen.selectedIndex <= 0) screen.colors.lastIndex else screen.selectedIndex - 1
                            screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                        }
                    }
                    is LauncherScreen.CollectionPicker -> {
                        if (screen.collections.isNotEmpty()) {
                            val newIdx = if (screen.selectedIndex <= 0) screen.collections.lastIndex else screen.selectedIndex - 1
                            screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                        }
                    }
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
                is DialogState.RenameInput,
                is DialogState.NewCollectionInput,
                is DialogState.CollectionRenameInput -> {
                    val ks = ds.asKeyboardState()!!
                    val rows = getKeyboardRows(ks.caps, ks.symbols)
                    val newRow = if (ks.keyRow >= rows.lastIndex) 0 else ks.keyRow + 1
                    val newCol = ks.keyCol.coerceAtMost(rows[newRow].lastIndex)
                    dialogState.value = ds.withKeyboard(newRow, newCol)
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
                DialogState.None -> when (val screen = screenStack.last()) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveDown()
                        else systemListViewModel.moveSelection(1)
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveDown()
                        else gameListViewModel.moveSelection(1)
                    }
                    LauncherScreen.Settings -> settingsViewModel.moveSelection(1)
                    is LauncherScreen.CoreMapping -> {
                        if (screen.mappings.isNotEmpty()) {
                            val newIdx = if (screen.selectedIndex >= screen.mappings.lastIndex) 0 else screen.selectedIndex + 1
                            screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                        }
                    }
                    is LauncherScreen.CorePicker -> {
                        if (screen.cores.isNotEmpty()) {
                            val newIdx = if (screen.selectedIndex >= screen.cores.lastIndex) 0 else screen.selectedIndex + 1
                            screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                        }
                    }
                    is LauncherScreen.AppPicker -> {
                        if (screen.apps.isNotEmpty()) {
                            val newIdx = if (screen.selectedIndex >= screen.apps.lastIndex) 0 else screen.selectedIndex + 1
                            screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                        }
                    }
                    is LauncherScreen.ColorList -> {
                        if (screen.colors.isNotEmpty()) {
                            val newIdx = if (screen.selectedIndex >= screen.colors.lastIndex) 0 else screen.selectedIndex + 1
                            screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                        }
                    }
                    is LauncherScreen.CollectionPicker -> {
                        if (screen.collections.isNotEmpty()) {
                            val newIdx = if (screen.selectedIndex >= screen.collections.lastIndex) 0 else screen.selectedIndex + 1
                            screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                        }
                    }
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
                DialogState.None -> when (val screen = screenStack.last()) {
                    LauncherScreen.SystemList -> if (!systemListViewModel.isReorderMode()) systemListViewModel.pageJump(-systemListViewModel.pageSize)
                    LauncherScreen.GameList -> if (!gameListViewModel.isReorderMode()) gameListViewModel.pageJump(-gameListViewModel.pageSize)
                    LauncherScreen.Settings -> if (settingsViewModel.state.value.inSubList) settingsViewModel.cycleSelected(-1)
                    is LauncherScreen.CoreMapping -> screenPageJump(screen, screen.mappings.size, screen.selectedIndex, -1)
                    is LauncherScreen.CorePicker -> screenPageJump(screen, screen.cores.size, screen.selectedIndex, -1)
                    is LauncherScreen.ColorList -> screenPageJump(screen, screen.colors.size, screen.selectedIndex, -1)
                    is LauncherScreen.CollectionPicker -> screenPageJump(screen, screen.collections.size, screen.selectedIndex, -1)
                    is LauncherScreen.AppPicker -> screenPageJump(screen, screen.apps.size, screen.selectedIndex, -1)
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
                DialogState.None -> when (val screen = screenStack.last()) {
                    LauncherScreen.SystemList -> if (!systemListViewModel.isReorderMode()) systemListViewModel.pageJump(systemListViewModel.pageSize)
                    LauncherScreen.GameList -> if (!gameListViewModel.isReorderMode()) gameListViewModel.pageJump(gameListViewModel.pageSize)
                    LauncherScreen.Settings -> if (settingsViewModel.state.value.inSubList) settingsViewModel.cycleSelected(1)
                    is LauncherScreen.CoreMapping -> screenPageJump(screen, screen.mappings.size, screen.selectedIndex, 1)
                    is LauncherScreen.CorePicker -> screenPageJump(screen, screen.cores.size, screen.selectedIndex, 1)
                    is LauncherScreen.ColorList -> screenPageJump(screen, screen.colors.size, screen.selectedIndex, 1)
                    is LauncherScreen.CollectionPicker -> screenPageJump(screen, screen.collections.size, screen.selectedIndex, 1)
                    is LauncherScreen.AppPicker -> screenPageJump(screen, screen.apps.size, screen.selectedIndex, 1)
                }
                else -> {}
            }
        }

        inputHandler.onConfirm = {
            when (val ds = dialogState.value) {
                is DialogState.ContextMenu -> onContextMenuConfirm(ds)
                is DialogState.BulkContextMenu -> onBulkContextMenuConfirm(ds)
                is DialogState.DeleteConfirm -> onDeleteConfirm()
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
                is DialogState.ColorPicker -> {
                    val idx = ds.selectedRow * COLOR_GRID_COLS + ds.selectedCol
                    val preset = COLOR_PRESETS.getOrNull(idx)
                    if (preset != null) {
                        val hex = "#%06X".format(preset.color and 0xFFFFFF)
                        settingsViewModel.setColor(ds.settingKey, hex)
                        val entries = settingsViewModel.getColorEntries()
                        updateColorListOnStack(ds.settingKey, entries)
                        dialogState.value = DialogState.None
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
                                val entries = settingsViewModel.getColorEntries()
                                updateColorListOnStack(ds.settingKey, entries)
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
                                screenStack.removeAt(screenStack.lastIndex)
                                rescanSystemList()
                            }
                        } else {
                            gameListViewModel.loadCollectionsList(restoreIndex = true)
                        }
                    }
                }
                DialogState.None -> when (val screen = screenStack.last()) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isMultiSelectMode()) systemListViewModel.toggleChecked()
                        else if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
                        else onSystemListConfirm()
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isMultiSelectMode()) gameListViewModel.toggleChecked()
                        else if (gameListViewModel.isReorderMode()) gameListViewModel.confirmReorder()
                        else onGameListConfirm()
                    }
                    LauncherScreen.Settings -> {
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
                                screenStack.add(LauncherScreen.ColorList(
                                    colors = settingsViewModel.getColorEntries()
                                ))
                            } else if (key != null && key.startsWith("color_")) {
                                val entries = settingsViewModel.getColorEntries()
                                val idx = entries.indexOfFirst { it.key == key }.coerceAtLeast(0)
                                screenStack.add(LauncherScreen.ColorList(colors = entries, selectedIndex = idx))
                                openColorPicker(key)
                            } else if (key == "core_mapping") {
                                screenStack.add(LauncherScreen.CoreMapping(
                                    mappings = platformResolver.getDetailedMappings(packageManager)
                                ))
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
                    is LauncherScreen.CoreMapping -> {
                        val entry = screen.mappings[screen.selectedIndex]
                        val options = platformResolver.getCorePickerOptions(entry.tag, packageManager)
                        val currentCore = platformResolver.getCoreMapping(entry.tag)
                        val currentApp = platformResolver.getAppPackage(entry.tag)
                        val currentRunner = entry.runnerLabel
                        val selectedIdx = if (currentRunner == "App" && currentApp != null) {
                            options.indexOfFirst { it.appPackage == currentApp }.coerceAtLeast(0)
                        } else {
                            options.indexOfFirst { it.coreId == currentCore && it.runnerLabel == currentRunner }
                                .coerceAtLeast(options.indexOfFirst { it.coreId == currentCore }.coerceAtLeast(0))
                        }
                        pushScreen(LauncherScreen.CorePicker(
                            tag = entry.tag,
                            platformName = entry.platformName,
                            cores = options,
                            selectedIndex = selectedIdx
                        ))
                    }
                    is LauncherScreen.CorePicker -> onCorePickerConfirm(screen)
                    is LauncherScreen.ColorList -> {
                        val entry = screen.colors[screen.selectedIndex]
                        openColorPicker(entry.key)
                    }
                    is LauncherScreen.CollectionPicker -> {
                        if (screen.collections.isNotEmpty()) {
                            val newChecked = if (screen.selectedIndex in screen.checkedIndices) {
                                screen.checkedIndices - screen.selectedIndex
                            } else {
                                screen.checkedIndices + screen.selectedIndex
                            }
                            screenStack[screenStack.lastIndex] = screen.copy(checkedIndices = newChecked)
                        }
                    }
                    is LauncherScreen.AppPicker -> {
                        val newChecked = if (screen.selectedIndex in screen.checkedIndices) {
                            screen.checkedIndices - screen.selectedIndex
                        } else {
                            screen.checkedIndices + screen.selectedIndex
                        }
                        screenStack[screenStack.lastIndex] = screen.copy(checkedIndices = newChecked)
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
                is DialogState.ColorPicker -> {
                    val entries = settingsViewModel.getColorEntries()
                    updateColorListOnStack(ds.settingKey, entries)
                    dialogState.value = DialogState.None
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
                is DialogState.CollectionCreated -> {
                    refreshCollectionPickerOnStack()
                    dialogState.value = DialogState.None
                }
                is DialogState.RenameResult -> {
                    restoreContextMenu()
                }
                is DialogState.MissingCore,
                is DialogState.MissingApp,
                is DialogState.LaunchError -> {
                    dialogState.value = DialogState.None
                }
                DialogState.About -> {
                    dialogState.value = DialogState.None
                }
                DialogState.None -> when (val screen = screenStack.last()) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isMultiSelectMode()) systemListViewModel.cancelMultiSelect()
                        else if (systemListViewModel.isReorderMode()) systemListViewModel.cancelReorder(showTools = settings.showTools, showPorts = settings.showPorts, showEmpty = settings.showEmpty, toolsName = settings.toolsName, portsName = settings.portsName)
                    }
                    LauncherScreen.GameList -> {
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
                                    screenStack.removeAt(screenStack.lastIndex)
                                    rescanSystemList()
                                }
                            }
                        }
                    }
                    LauncherScreen.Settings -> {
                        if (settingsViewModel.state.value.inSubList) {
                            settingsViewModel.save()
                            settingsViewModel.exitSubList()
                            rescanSystemList()
                        } else {
                            settingsViewModel.cancel()
                            screenStack.removeAt(screenStack.lastIndex)
                        }
                    }
                    is LauncherScreen.CorePicker -> {
                        screenStack.removeAt(screenStack.lastIndex)
                        if (screen.gamePath != null) {
                            restoreContextMenu()
                        } else {
                            val cm = screenStack.last()
                            if (cm is LauncherScreen.CoreMapping) {
                                val mappings = platformResolver.getDetailedMappings(packageManager)
                                val idx = mappings.indexOfFirst { it.tag == screen.tag }.coerceAtLeast(0)
                                screenStack[screenStack.lastIndex] = cm.copy(mappings = mappings, selectedIndex = idx)
                            }
                        }
                    }
                    is LauncherScreen.CoreMapping -> {
                        platformResolver.saveCoreMappings()
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.AppPicker -> {
                        onAppPickerConfirm(screen)
                    }
                    is LauncherScreen.ColorList -> {
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.CollectionPicker -> {
                        onCollectionPickerConfirm(screen)
                    }
                }
            }
        }

        inputHandler.onStart = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> onRenameConfirm(ds)
                is DialogState.NewCollectionInput -> onNewCollectionConfirm(ds)
                is DialogState.CollectionRenameInput -> onCollectionRenameConfirm(ds)
                DialogState.None -> when (screenStack.last()) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isMultiSelectMode()) {
                            systemListViewModel.confirmMultiSelect()
                        } else if (systemListViewModel.isReorderMode()) {
                            systemListViewModel.confirmReorder()
                        } else {
                            onSystemListContextMenu()
                        }
                    }
                    LauncherScreen.GameList -> {
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
                    else -> {}
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
                DialogState.None -> when (screenStack.last()) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) {
                            systemListViewModel.confirmReorder()
                        } else {
                            systemListViewModel.enterReorderMode()
                        }
                    }
                    LauncherScreen.GameList -> {
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
                DialogState.None -> when (val screen = screenStack.last()) {
                    LauncherScreen.SystemList -> {
                        systemListViewModel.savePosition()
                        settingsViewModel.load()
                        screenStack.add(LauncherScreen.Settings)
                    }
                    is LauncherScreen.CollectionPicker -> {
                        dialogState.value = DialogState.NewCollectionInput(gamePaths = screen.gamePaths)
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
                    dialogState.value = DialogState.None
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
                DialogState.None -> if (screenStack.last() == LauncherScreen.GameList && settings.platformSwitching) switchPlatform(-1)
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
                DialogState.None -> if (screenStack.last() == LauncherScreen.GameList && settings.platformSwitching) switchPlatform(1)
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
        screenStack.add(LauncherScreen.AppPicker(
            type = type,
            title = title,
            apps = allApps.map { it.first },
            packages = allApps.map { it.second },
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
    }

    private fun onAppPickerConfirm(state: LauncherScreen.AppPicker) {
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
        screenStack.removeAt(screenStack.lastIndex)
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
        systemListViewModel.savePosition()
        when (val item = systemListViewModel.getSelectedItem()) {
            is SystemListViewModel.ListItem.FavoritesItem -> {
                navigating = true
                gameListViewModel.loadCollection("Favorites") {
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionsFolder -> {
                navigating = true
                gameListViewModel.loadCollectionsList {
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.PlatformItem -> {
                navigating = true
                gameListViewModel.loadPlatform(item.platform.tag) {
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                navigating = true
                gameListViewModel.loadCollection(item.name) {
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                navigating = true
                gameListViewModel.loadApkList("tools", item.name) {
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                navigating = true
                gameListViewModel.loadApkList("ports", item.name) {
                    screenStack.add(LauncherScreen.GameList)
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

        val launchFile = if (game.discFiles != null) createTempM3u(game) else game.file

        val gameOverride = platformResolver.getGameOverride(game.file.absolutePath)
        if (gameOverride?.appPackage != null) {
            val result = apkLauncher.launchWithRom(gameOverride.appPackage, launchFile)
            handleLaunchResult(result)
            return
        }

        val result = when (val target = game.launchTarget) {
            is LaunchTarget.RetroArch -> {
                val core = gameOverride?.coreId ?: platformResolver.getCoreName(game.platformTag)
                if (core != null) {
                    val runnerPref = gameOverride?.runner ?: platformResolver.getRunnerPreference(game.platformTag)
                    if (runnerPref != "RetroArch") {
                        val embeddedCorePath = findEmbeddedCore(core)
                        if (embeddedCorePath != null) {
                            launchEmbedded(game.copy(file = launchFile), embeddedCorePath)
                            return
                        }
                    }
                    retroArchLauncher.launch(launchFile, core)
                } else {
                    LaunchResult.CoreNotInstalled("unknown")
                }
            }
            is LaunchTarget.EmuLaunch -> {
                emuLauncher.launch(launchFile, target.packageName, target.activityName, target.action)
            }
            is LaunchTarget.ApkLaunch -> {
                if (launchFile.exists()) {
                    apkLauncher.launchWithRom(target.packageName, launchFile)
                } else {
                    apkLauncher.launch(target.packageName)
                }
            }
            is LaunchTarget.Embedded -> {
                launchEmbedded(game.copy(file = launchFile), target.corePath)
                return
            }
        }

        handleLaunchResult(result)
    }

    private fun handleLaunchResult(result: LaunchResult) {
        when (result) {
            is LaunchResult.CoreNotInstalled -> {
                dialogState.value = DialogState.MissingCore(result.coreName)
            }
            is LaunchResult.AppNotInstalled -> {
                val appName = try {
                    val info = packageManager.getApplicationInfo(result.packageName, 0)
                    packageManager.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    result.packageName
                }
                dialogState.value = DialogState.MissingApp(appName, result.packageName)
            }
            is LaunchResult.Error -> {
                dialogState.value = DialogState.LaunchError(result.message)
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

    private fun createTempM3u(game: dev.cannoli.scorza.model.Game): java.io.File {
        val m3uDir = java.io.File(cacheDir, "m3u")
        m3uDir.mkdirs()
        val m3uFile = java.io.File(m3uDir, "${game.displayName}.m3u")
        m3uFile.writeText(game.discFiles!!.joinToString("\n") { it.absolutePath } + "\n")
        return m3uFile
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
            putExtra("platform_name", platformResolver.getDisplayName(game.platformTag))
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

    private fun onCorePickerConfirm(screen: LauncherScreen.CorePicker) {
        if (screen.cores.isEmpty()) return
        val chosen = screen.cores[screen.selectedIndex]
        if (screen.gamePath != null) {
            if (chosen.coreId.isEmpty() && chosen.appPackage == null) {
                platformResolver.setGameOverride(screen.gamePath, null, null)
                platformResolver.setGameAppOverride(screen.gamePath, null)
            } else if (chosen.appPackage != null) {
                platformResolver.setGameAppOverride(screen.gamePath, chosen.appPackage)
            } else {
                val runner = if (chosen.runnerLabel == "Internal" || chosen.runnerLabel == "RetroArch") chosen.runnerLabel else null
                platformResolver.setGameOverride(screen.gamePath, chosen.coreId, runner)
            }
            screenStack.removeAt(screenStack.lastIndex)
            restoreContextMenu()
        } else {
            if (chosen.appPackage != null) {
                platformResolver.setAppMapping(screen.tag, chosen.appPackage)
            } else {
                val runner = if (chosen.runnerLabel == "Internal" || chosen.runnerLabel == "RetroArch") chosen.runnerLabel else null
                platformResolver.setCoreMapping(screen.tag, chosen.coreId, runner)
            }
            platformResolver.saveCoreMappings()
            screenStack.removeAt(screenStack.lastIndex)
            val cm = screenStack.last()
            if (cm is LauncherScreen.CoreMapping) {
                val mappings = platformResolver.getDetailedMappings(packageManager)
                val idx = mappings.indexOfFirst { it.tag == screen.tag }.coerceAtLeast(0)
                screenStack[screenStack.lastIndex] = cm.copy(mappings = mappings, selectedIndex = idx)
            }
        }
    }

    private fun buildGameContextOptions(game: dev.cannoli.scorza.model.Game): List<String> {
        val isFav = scanner.isInCollection("Favorites", game.file.absolutePath)
        val favOption = if (isFav) "Remove from Favorites" else "Add to Favorites"
        return listOf(favOption, "Manage Collections", "Emulator Override", "Rename", "Delete")
    }

    private fun onContextMenuConfirm(state: DialogState.ContextMenu) {
        if (screenStack.last() == LauncherScreen.SystemList) {
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
            "Emulator Override" -> {
                val tag = game.platformTag
                val options = platformResolver.getCorePickerOptions(tag, packageManager)
                val defaultOption = CorePickerOption("", "Default (Platform Setting)", "")
                val allOptions = listOf(defaultOption) + options
                val override = platformResolver.getGameOverride(game.file.absolutePath)
                val selectedIdx = if (override?.appPackage != null) {
                    allOptions.indexOfFirst { it.appPackage == override.appPackage }.coerceAtLeast(0)
                } else if (override != null) {
                    allOptions.indexOfFirst { it.coreId == override.coreId && (it.runnerLabel == override.runner || override.runner == null) }
                        .coerceAtLeast(0)
                } else {
                    0
                }
                dialogState.value = DialogState.None
                screenStack.add(LauncherScreen.CorePicker(
                    tag = tag,
                    platformName = game.displayName,
                    cores = allOptions,
                    selectedIndex = selectedIdx,
                    gamePath = game.file.absolutePath
                ))
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

    private fun onCollectionPickerConfirm(state: LauncherScreen.CollectionPicker) {
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
        screenStack.removeAt(screenStack.lastIndex)
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
        dialogState.value = DialogState.None
        screenStack.add(LauncherScreen.CollectionPicker(
            gamePaths = gamePaths,
            title = title,
            collections = allCollections,
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
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
        if (screenStack.last() == LauncherScreen.SystemList) {
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

}
