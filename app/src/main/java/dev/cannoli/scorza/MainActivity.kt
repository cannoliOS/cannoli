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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import dev.cannoli.scorza.input.InputHandler
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.EmuLauncher
import dev.cannoli.scorza.launcher.LaunchResult
import dev.cannoli.scorza.launcher.RetroArchLauncher
import dev.cannoli.scorza.model.LaunchTarget
import dev.cannoli.scorza.navigation.AppNavGraph
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.libretro.ShortcutAction
import dev.cannoli.scorza.scanner.FileScanner
import dev.cannoli.scorza.scanner.PlatformResolver
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.components.COLOR_GRID_COLS
import dev.cannoli.scorza.ui.components.HEX_KEYS
import dev.cannoli.scorza.ui.components.HEX_ROW_SIZE
import dev.cannoli.scorza.ui.components.KEY_BACKSPACE
import dev.cannoli.scorza.ui.components.KEY_ENTER
import dev.cannoli.scorza.ui.components.KEY_SHIFT
import dev.cannoli.scorza.ui.components.KEY_SPACE
import dev.cannoli.scorza.ui.components.KEY_SYMBOLS
import dev.cannoli.scorza.ui.components.getKeyboardRows
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.CoreMappingEntry
import dev.cannoli.scorza.ui.screens.CorePickerOption
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.KeyboardInputState
import dev.cannoli.scorza.util.IniParser
import dev.cannoli.scorza.util.IniWriter
import dev.cannoli.scorza.ui.theme.COLOR_PRESETS
import dev.cannoli.scorza.ui.theme.CannoliTheme
import dev.cannoli.scorza.ui.theme.colorToArgbLong
import dev.cannoli.scorza.ui.theme.hexToColor
import dev.cannoli.scorza.ui.screens.SetupScreen
import dev.cannoli.scorza.ui.theme.initFonts
import dev.cannoli.scorza.ui.viewmodel.GameListViewModel
import dev.cannoli.scorza.ui.viewmodel.SettingsViewModel
import dev.cannoli.scorza.ui.viewmodel.SystemListViewModel
import dev.cannoli.scorza.util.AtomicRename
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var resumableGames by mutableStateOf(emptySet<String>())
    private val dialogState = MutableStateFlow<DialogState>(DialogState.None)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val controlButtons = LibretroInput().buttons
    private val controlButtonCount = controlButtons.size
    @Volatile private var navigating = false
    private var currentFirstVisible = 0
    private var currentPageSize = 10
    private var inSetup by mutableStateOf(false)
    private var setupSelectedIndex by mutableStateOf(0)
    private var setupVolumeIndex by mutableStateOf(0)
    private var setupVolumes = listOf<Pair<String, String>>()

    private val shortcutCountdownHandler = Handler(Looper.getMainLooper())
    private val shortcutHoldMs = 1500
    private val shortcutTickMs = 100L

    private val shortcutCountdownRunnable = object : Runnable {
        override fun run() {
            val screen = screenStack.lastOrNull() as? LauncherScreen.ShortcutBinding ?: return
            if (!screen.listening) return
            val newMs = screen.countdownMs + shortcutTickMs.toInt()
            if (newMs >= shortcutHoldMs) {
                val action = ShortcutAction.entries[screen.selectedIndex]
                screenStack[screenStack.lastIndex] = screen.copy(
                    shortcuts = screen.shortcuts + (action to screen.heldKeys),
                    listening = false, heldKeys = emptySet(), countdownMs = 0
                )
            } else {
                screenStack[screenStack.lastIndex] = screen.copy(countdownMs = newMs)
                shortcutCountdownHandler.postDelayed(this, shortcutTickMs)
            }
        }
    }

    private fun cancelShortcutListening() {
        shortcutCountdownHandler.removeCallbacks(shortcutCountdownRunnable)
        val screen = screenStack.lastOrNull() as? LauncherScreen.ShortcutBinding ?: return
        if (screen.listening) {
            screenStack[screenStack.lastIndex] = screen.copy(listening = false, heldKeys = emptySet(), countdownMs = 0)
        }
    }

    private fun globalIniFile() = File(settings.sdCardRoot, "Config/Overrides/global.ini")

    private fun readGlobalControls(): Map<String, Int> {
        val ini = IniParser.parse(globalIniFile())
        val map = mutableMapOf<String, Int>()
        for ((key, value) in ini.getSection("controls")) {
            value.toIntOrNull()?.let { map[key] = it }
        }
        return map
    }

    private fun readGlobalShortcuts(): Map<ShortcutAction, Set<Int>> {
        val ini = IniParser.parse(globalIniFile())
        val map = mutableMapOf<ShortcutAction, Set<Int>>()
        for ((key, value) in ini.getSection("shortcuts")) {
            val action = try { ShortcutAction.valueOf(key) } catch (_: Exception) { continue }
            val chord = if (value.isEmpty()) emptySet()
            else value.split(",").mapNotNull { it.toIntOrNull() }.toSet()
            map[action] = chord
        }
        return map
    }

    private fun saveGlobalControls(controls: Map<String, Int>) {
        IniWriter.mergeWrite(globalIniFile(), "controls", controls.mapValues { it.value.toString() })
    }

    private fun saveGlobalShortcuts(shortcuts: Map<ShortcutAction, Set<Int>>) {
        IniWriter.mergeWrite(
            globalIniFile(), "shortcuts",
            shortcuts.mapKeys { it.key.name }.mapValues { it.value.joinToString(",") }
        )
    }

    private fun pushScreen(new: LauncherScreen) {
        val current = screenStack.last()
        screenStack[screenStack.lastIndex] = saveScrollPosition(current)
        screenStack.add(new)
    }

    private fun saveScrollPosition(screen: LauncherScreen): LauncherScreen = when (screen) {
        is LauncherScreen.CoreMapping -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.CorePicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ColorList -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.CollectionPicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.AppPicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ControlBinding -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ShortcutBinding -> screen.copy(scrollTarget = currentFirstVisible)
        else -> screen
    }

    private fun pageJump(direction: Int) {
        val page = currentPageSize.coerceAtLeast(1)

        val screen = screenStack.last()
        val (itemCount, selectedIndex) = when (screen) {
            LauncherScreen.SystemList -> systemListViewModel.state.value.let { it.items.size to it.selectedIndex }
            LauncherScreen.GameList -> gameListViewModel.state.value.let { it.games.size to it.selectedIndex }
            LauncherScreen.Settings -> settingsViewModel.state.value.let { it.categories.size to it.categoryIndex }
            is LauncherScreen.CoreMapping -> screen.mappings.size to screen.selectedIndex
            is LauncherScreen.CorePicker -> screen.cores.size to screen.selectedIndex
            is LauncherScreen.ColorList -> screen.colors.size to screen.selectedIndex
            is LauncherScreen.CollectionPicker -> screen.collections.size to screen.selectedIndex
            is LauncherScreen.AppPicker -> screen.apps.size to screen.selectedIndex
            is LauncherScreen.ControlBinding -> controlButtonCount to screen.selectedIndex
            is LauncherScreen.ShortcutBinding -> ShortcutAction.entries.size to screen.selectedIndex
        }

        if (itemCount == 0) return
        val lastIndex = itemCount - 1

        val newIdx: Int
        val newScroll: Int

        if (direction > 0) {
            val lastVisible = currentFirstVisible + page - 1
            if (lastVisible >= lastIndex) {
                if (selectedIndex >= lastIndex) return
                newIdx = lastIndex
                newScroll = currentFirstVisible
            } else {
                newIdx = (currentFirstVisible + page).coerceAtMost(lastIndex)
                newScroll = newIdx
            }
        } else {
            if (currentFirstVisible <= 0) {
                if (selectedIndex <= 0) return
                newIdx = 0
                newScroll = 0
            } else {
                newIdx = (currentFirstVisible - page).coerceAtLeast(0)
                newScroll = newIdx
            }
        }

        applyPageJump(screen, newIdx, newScroll)
    }

    private fun applyPageJump(screen: LauncherScreen, newIdx: Int, newScroll: Int) {
        when (screen) {
            LauncherScreen.SystemList -> systemListViewModel.jumpToIndex(newIdx, newScroll)
            LauncherScreen.GameList -> gameListViewModel.jumpToIndex(newIdx, newScroll)
            LauncherScreen.Settings -> settingsViewModel.setCategoryIndex(newIdx)
            is LauncherScreen.CoreMapping -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.CorePicker -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.ColorList -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.CollectionPicker -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.AppPicker -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.ControlBinding -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.ShortcutBinding -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
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
            afterPermissionGranted()
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
            afterPermissionGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hideSystemUI()

        settings = SettingsRepository(this)
        initFonts(assets)

        if (hasStoragePermission()) {
            afterPermissionGranted()
        } else {
            requestStoragePermission()
        }
    }

    private fun afterPermissionGranted() {
        if (settings.setupCompleted || File(settings.sdCardRoot).exists()) {
            settings.setupCompleted = true
            initializeApp()
        } else {
            showSetupScreen()
        }
    }

    private fun detectStorageVolumes(): List<Pair<String, String>> {
        val volumes = mutableListOf("Internal Storage" to "/storage/emulated/0/")
        val storageDir = File("/storage")
        if (storageDir.exists()) {
            storageDir.listFiles()?.forEach { dir ->
                if (dir.name != "emulated" && dir.name != "self" && dir.isDirectory && dir.canRead()) {
                    volumes.add("SD Card" to dir.absolutePath + "/")
                }
            }
        }
        return volumes
    }

    private fun showSetupScreen() {
        inSetup = true
        setupVolumes = detectStorageVolumes()
        setupVolumeIndex = 0
        setupSelectedIndex = 0
        setContent {
            CannoliTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen(
                        storageLabel = setupVolumes[setupVolumeIndex].first,
                        selectedIndex = setupSelectedIndex
                    )
                }
            }
        }
    }

    private fun completeSetup() {
        inSetup = false
        settings.sdCardRoot = setupVolumes[setupVolumeIndex].second + "Cannoli/"
        settings.setupCompleted = true
        initializeApp()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (::systemListViewModel.isInitialized) {
            rescanSystemList()
            if (screenStack.lastOrNull() is LauncherScreen.GameList) {
                gameListViewModel.reload()
                scanResumableGames()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioScope.cancel()
        dev.cannoli.scorza.server.KitchenManager.stop()
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
            window.decorView.post {
                if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@post
                inputHandler.onStart()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (inSetup) {
            handleSetupInput(keyCode)
            return true
        }
        if (handleBindingKeyDown(keyCode)) return true
        if (::inputHandler.isInitialized && inputHandler.handleKeyEvent(event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val screen = screenStack.lastOrNull()
        if (screen is LauncherScreen.ShortcutBinding && screen.listening && screen.heldKeys.contains(keyCode)) {
            cancelShortcutListening()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun handleBindingKeyDown(keyCode: Int): Boolean {
        val screen = screenStack.lastOrNull() ?: return false
        when (screen) {
            is LauncherScreen.ControlBinding -> {
                if (screen.listeningIndex < 0) return false
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                    screenStack[screenStack.lastIndex] = screen.copy(listeningIndex = -1)
                    return true
                }
                val btn = controlButtons[screen.listeningIndex]
                screenStack[screenStack.lastIndex] = screen.copy(
                    controls = screen.controls + (btn.prefKey to keyCode),
                    listeningIndex = -1
                )
                return true
            }
            is LauncherScreen.ShortcutBinding -> {
                if (!screen.listening) return false
                if (screen.heldKeys.contains(keyCode)) return true
                val newKeys = screen.heldKeys + keyCode
                screenStack[screenStack.lastIndex] = screen.copy(heldKeys = newKeys, countdownMs = 0)
                shortcutCountdownHandler.removeCallbacks(shortcutCountdownRunnable)
                shortcutCountdownHandler.postDelayed(shortcutCountdownRunnable, shortcutTickMs)
                return true
            }
            else -> return false
        }
    }

    private fun handleSetupInput(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> setupSelectedIndex = 0
            KeyEvent.KEYCODE_DPAD_DOWN -> setupSelectedIndex = 1
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (setupSelectedIndex == 0 && setupVolumes.size > 1) {
                    setupVolumeIndex = (setupVolumeIndex - 1 + setupVolumes.size) % setupVolumes.size
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (setupSelectedIndex == 0 && setupVolumes.size > 1) {
                    setupVolumeIndex = (setupVolumeIndex + 1) % setupVolumes.size
                }
            }
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (setupSelectedIndex == 1) completeSetup()
            }
            KeyEvent.KEYCODE_BUTTON_B -> finishAffinity()
        }
    }

    private fun extractBundledCores(): String {
        val coresDir = java.io.File(filesDir, "cores")
        coresDir.mkdirs()
        val versionFile = java.io.File(coresDir, ".version")
        val currentVersion = java.io.File(applicationInfo.sourceDir).lastModified().toString()
        if (versionFile.exists() && versionFile.readText() == currentVersion) return coresDir.absolutePath
        java.util.zip.ZipFile(applicationInfo.sourceDir).use { apkZip ->
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val prefix = "lib/$abi/"
            for (entry in apkZip.entries()) {
                if (!entry.name.startsWith(prefix) || !entry.name.endsWith("_libretro_android.so")) continue
                val name = entry.name.removePrefix(prefix)
                val dst = java.io.File(coresDir, name)
                apkZip.getInputStream(entry).use { inp -> dst.outputStream().use { inp.copyTo(it) } }
            }
        }
        versionFile.writeText(currentVersion)
        return coresDir.absolutePath
    }

    private fun initializeApp() {
        val root = File(settings.sdCardRoot)
        val bundledCoresDir = extractBundledCores()
        val coreInfo = dev.cannoli.scorza.scanner.CoreInfoRepository(assets)
        coreInfo.load()
        platformResolver = PlatformResolver(root, assets, coreInfo, bundledCoresDir)
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

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // swallow — back button/gesture does nothing in the launcher
            }
        })

        rescanSystemList()

        setContent {
            CannoliTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavGraph(
                        currentScreen = screenStack.last(),
                        systemListViewModel = systemListViewModel,
                        gameListViewModel = gameListViewModel,
                        settingsViewModel = settingsViewModel,
                        dialogState = dialogState,
                        onVisibleRangeChanged = { first, count, full ->
                            currentFirstVisible = first
                            if (full) currentPageSize = count
                        },
                        resumableGames = resumableGames
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
                    val rowSize = HEX_ROW_SIZE
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
                    is LauncherScreen.ControlBinding -> {
                        val count = controlButtonCount
                        val newIdx = if (screen.selectedIndex <= 0) count - 1 else screen.selectedIndex - 1
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                    }
                    is LauncherScreen.ShortcutBinding -> {
                        if (!screen.listening) {
                            val count = ShortcutAction.entries.size
                            val newIdx = if (screen.selectedIndex <= 0) count - 1 else screen.selectedIndex - 1
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
                    val rowSize = HEX_ROW_SIZE
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
                    is LauncherScreen.ControlBinding -> {
                        val count = controlButtonCount
                        val newIdx = if (screen.selectedIndex >= count - 1) 0 else screen.selectedIndex + 1
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx)
                    }
                    is LauncherScreen.ShortcutBinding -> {
                        if (!screen.listening) {
                            val count = ShortcutAction.entries.size
                            val newIdx = if (screen.selectedIndex >= count - 1) 0 else screen.selectedIndex + 1
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
                    val rowSize = HEX_ROW_SIZE
                    val curRow = ds.selectedIndex / rowSize
                    val col = ds.selectedIndex % rowSize
                    val newCol = if (col <= 0) rowSize - 1 else col - 1
                    dialogState.value = ds.copy(selectedIndex = curRow * rowSize + newCol)
                }
                DialogState.None -> when (screenStack.last()) {
                    LauncherScreen.SystemList -> if (!systemListViewModel.isReorderMode()) pageJump(-1)
                    LauncherScreen.GameList -> if (!gameListViewModel.isReorderMode()) pageJump(-1)
                    LauncherScreen.Settings -> if (settingsViewModel.state.value.inSubList) settingsViewModel.cycleSelected(-1) else pageJump(-1)
                    else -> pageJump(-1)
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
                    val rowSize = HEX_ROW_SIZE
                    val curRow = ds.selectedIndex / rowSize
                    val col = ds.selectedIndex % rowSize
                    val newCol = if (col >= rowSize - 1) 0 else col + 1
                    dialogState.value = ds.copy(selectedIndex = curRow * rowSize + newCol)
                }
                DialogState.None -> when (screenStack.last()) {
                    LauncherScreen.SystemList -> if (!systemListViewModel.isReorderMode()) pageJump(1)
                    LauncherScreen.GameList -> if (!gameListViewModel.isReorderMode()) pageJump(1)
                    LauncherScreen.Settings -> if (settingsViewModel.state.value.inSubList) settingsViewModel.cycleSelected(1) else pageJump(1)
                    else -> pageJump(1)
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
                            withContext(Dispatchers.Main) {
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
                            } else if (cat?.key == "kitchen") {
                                val root = File(settings.sdCardRoot)
                                val km = dev.cannoli.scorza.server.KitchenManager
                                if (!km.isRunning) km.toggle(root, assets)
                                val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                                dialogState.value = DialogState.Kitchen(
                                    url = km.getUrl(wifiManager),
                                    pin = km.pin
                                )
                            } else {
                                settingsViewModel.enterCategory()
                            }
                        } else {
                            when (val key = settingsViewModel.enterSelected()) {
                                "sd_root" -> folderPickerLauncher.launch(null)
                                "colors" -> screenStack.add(LauncherScreen.ColorList(
                                    colors = settingsViewModel.getColorEntries()
                                ))
                                "controls" -> pushScreen(LauncherScreen.ControlBinding(controls = readGlobalControls()))
                                "shortcuts" -> pushScreen(LauncherScreen.ShortcutBinding(shortcuts = readGlobalShortcuts()))
                                "core_mapping" -> screenStack.add(LauncherScreen.CoreMapping(
                                    mappings = platformResolver.getDetailedMappings(packageManager)
                                ))
                                "manage_tools" -> openAppPicker("tools")
                                "manage_ports" -> openAppPicker("ports")
                                null -> {}
                                else -> {
                                    if (key.startsWith("color_")) {
                                        val entries = settingsViewModel.getColorEntries()
                                        val idx = entries.indexOfFirst { it.key == key }.coerceAtLeast(0)
                                        screenStack.add(LauncherScreen.ColorList(colors = entries, selectedIndex = idx))
                                        openColorPicker(key)
                                    } else {
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
                            selectedIndex = selectedIdx,
                            activeIndex = selectedIdx
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
                    is LauncherScreen.ControlBinding -> {
                        if (screen.listeningIndex < 0) {
                            screenStack[screenStack.lastIndex] = screen.copy(listeningIndex = screen.selectedIndex)
                        }
                    }
                    is LauncherScreen.ShortcutBinding -> {
                        if (!screen.listening) {
                            screenStack[screenStack.lastIndex] = screen.copy(
                                listening = true, heldKeys = emptySet(), countdownMs = 0
                            )
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
                    dialogState.value = DialogState.None
                }
                is DialogState.MissingCore,
                is DialogState.MissingApp,
                is DialogState.LaunchError -> {
                    dialogState.value = DialogState.None
                }
                DialogState.About,
                is DialogState.Kitchen -> {
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
                    is LauncherScreen.ControlBinding -> {
                        saveGlobalControls(screen.controls)
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.ShortcutBinding -> {
                        cancelShortcutListening()
                        saveGlobalShortcuts(screen.shortcuts)
                        screenStack.removeAt(screenStack.lastIndex)
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
                                options.addAll(listOf("Manage Collections", "Delete"))
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
                            } else if (game.isSubfolder) {
                                dialogState.value = DialogState.ContextMenu(
                                    gameName = game.displayName,
                                    options = listOf("Rename", "Delete")
                                )
                            } else {
                                dialogState.value = DialogState.ContextMenu(
                                    gameName = game.displayName,
                                    options = gameContextOptions
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
                is DialogState.Kitchen -> {
                    dev.cannoli.scorza.server.KitchenManager.stop()
                    dialogState.value = DialogState.None
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
                    LauncherScreen.GameList -> {
                        val game = gameListViewModel.getSelectedGame()
                        if (game != null && !game.isSubfolder && !gameListViewModel.state.value.isCollectionsList) {
                            val corePath = getEmbeddedCorePath(game)
                            if (corePath != null) {
                                val slot = findMostRecentSlot(game)
                                if (slot != null) {
                                    val launchFile = if (game.discFiles != null) createTempM3u(game) else game.file
                                    launchEmbedded(game.copy(file = launchFile), corePath, slot)
                                }
                            }
                        }
                    }
                    is LauncherScreen.CollectionPicker -> {
                        dialogState.value = DialogState.NewCollectionInput(gamePaths = screen.gamePaths)
                    }
                    is LauncherScreen.ControlBinding -> {
                        screenStack[screenStack.lastIndex] = screen.copy(controls = emptyMap())
                    }
                    is LauncherScreen.ShortcutBinding -> {
                        if (!screen.listening) {
                            val action = ShortcutAction.entries[screen.selectedIndex]
                            screenStack[screenStack.lastIndex] = screen.copy(
                                shortcuts = screen.shortcuts + (action to emptySet())
                            )
                        }
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
                DialogState.None -> {
                    when (screenStack.last()) {
                        LauncherScreen.SystemList -> {
                            val km = dev.cannoli.scorza.server.KitchenManager
                            if (km.isRunning || systemListViewModel.state.value.items.isEmpty()) {
                                val root = File(settings.sdCardRoot)
                                if (!km.isRunning) km.toggle(root, assets)
                                val wifiManager = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                                dialogState.value = DialogState.Kitchen(
                                    url = km.getUrl(wifiManager),
                                    pin = km.pin
                                )
                            }
                        }
                        LauncherScreen.GameList -> {
                            val glState = gameListViewModel.state.value
                            if (!glState.isCollectionsList && !glState.multiSelectMode && !glState.reorderMode) {
                                gameListViewModel.toggleFavorite { rescanSystemList() }
                            }
                        }
                        else -> {}
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
        scanner.invalidateArtCache()
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
                    scanResumableGames()
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
                    scanResumableGames()
                    screenStack.add(LauncherScreen.GameList)
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                navigating = true
                gameListViewModel.loadCollection(item.name) {
                    scanResumableGames()
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
                val runnerPref = gameOverride?.runner ?: platformResolver.getRunnerPreference(game.platformTag)
                if (runnerPref == "App") {
                    val app = platformResolver.getAppPackage(game.platformTag)
                    if (app != null) {
                        apkLauncher.launchWithRom(app, launchFile)
                    } else {
                        LaunchResult.CoreNotInstalled("unknown")
                    }
                } else {
                    val core = gameOverride?.coreId ?: platformResolver.getCoreName(game.platformTag)
                    if (core != null) {
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
        m3uFile.writeText(checkNotNull(game.discFiles).joinToString("\n") { it.absolutePath } + "\n")
        return m3uFile
    }

    private fun findEmbeddedCore(coreName: String): String? {
        val soName = "${coreName}_android.so"
        val coreFile = java.io.File(filesDir, "cores/$soName")
        return if (coreFile.exists()) coreFile.absolutePath else null
    }

    private fun scanResumableGames() {
        val games = gameListViewModel.state.value.games
        val cannoliRoot = java.io.File(settings.sdCardRoot)
        ioScope.launch {
            val result = mutableSetOf<String>()
            for (game in games) {
                if (game.isSubfolder) continue
                if (getEmbeddedCorePath(game) == null) continue
                val romName = game.file.nameWithoutExtension
                val stateDir = java.io.File(cannoliRoot, "Save States/${game.platformTag}/$romName")
                if (stateDir.exists() && stateDir.listFiles()?.any { it.extension == "state" || it.name.contains(".state.") } == true) {
                    result.add(game.file.absolutePath)
                }
            }
            withContext(Dispatchers.Main) { resumableGames = result }
        }
    }

    private fun getEmbeddedCorePath(game: dev.cannoli.scorza.model.Game): String? {
        val gameOverride = platformResolver.getGameOverride(game.file.absolutePath)
        if (gameOverride?.appPackage != null) return null
        val target = game.launchTarget
        if (target is LaunchTarget.Embedded) return target.corePath
        if (target !is LaunchTarget.RetroArch) return null
        val core = gameOverride?.coreId ?: platformResolver.getCoreName(game.platformTag) ?: return null
        val runnerPref = gameOverride?.runner ?: platformResolver.getRunnerPreference(game.platformTag)
        if (runnerPref == "RetroArch") return null
        return findEmbeddedCore(core)
    }

    private fun findMostRecentSlot(game: dev.cannoli.scorza.model.Game): Int? {
        val cannoliRoot = java.io.File(settings.sdCardRoot)
        val romName = game.file.nameWithoutExtension
        val stateBase = java.io.File(cannoliRoot, "Save States/${game.platformTag}/$romName/$romName.state")
        val slotManager = dev.cannoli.scorza.libretro.SaveSlotManager(stateBase.absolutePath)
        var bestSlot = -1
        var bestTime = 0L
        for (slot in slotManager.slots) {
            val f = java.io.File(slotManager.statePath(slot))
            if (f.exists() && f.lastModified() > bestTime) {
                bestTime = f.lastModified()
                bestSlot = slot.index
            }
        }
        return if (bestSlot >= 0) bestSlot else null
    }

    private fun launchEmbedded(game: dev.cannoli.scorza.model.Game, corePath: String, resumeSlot: Int = -1) {
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
            putExtra("swap_start_select", settings.swapStartSelect)
            if (resumeSlot >= 0) putExtra("resume_slot", resumeSlot)
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

    private val gameContextOptions = listOf("Manage Collections", "Emulator Override", "Rename", "Delete")

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
        pendingContextReturn = ContextReturn.Single(state.gameName, state.options)
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
            "Emulator Override" -> {
                val tag = game.platformTag
                val options = platformResolver.getCorePickerOptions(tag, packageManager)
                val platformCoreId = platformResolver.getCoreMapping(tag)
                val platformCoreName = options.firstOrNull { it.coreId == platformCoreId }?.displayName ?: platformCoreId
                val defaultLabel = if (platformCoreName.isNotEmpty()) "Platform Setting ($platformCoreName)" else "Platform Setting"
                val defaultOption = CorePickerOption("", defaultLabel, "")
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
                    gamePath = game.file.absolutePath,
                    activeIndex = selectedIdx
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
                        gameContextOptions
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
            pendingContextReturn = null
            dialogState.value = DialogState.None
            return
        }

        pendingContextReturn = null
        dialogState.value = DialogState.None
        ioScope.launch {
            if (game.isSubfolder) {
                val newDir = File(game.file.parentFile, newName)
                val ok = game.file.renameTo(newDir)
                val msg = if (ok) null else "Failed to rename directory"
                withContext(Dispatchers.Main) {
                    if (msg != null) dialogState.value = DialogState.RenameResult(false, msg)
                }
            } else {
                atomicRename.rename(game.file, newName, game.platformTag)
            }
            gameListViewModel.reload()
        }
    }

    private fun switchPlatform(delta: Int) {
        if (navigating) return
        val items = systemListViewModel.getNavigableItems()
        if (items.size < 2) return

        val gs = gameListViewModel.state.value
        val currentIndex = items.indexOfFirst { item ->
            when {
                gs.isCollectionsList -> item is SystemListViewModel.ListItem.CollectionsFolder
                gs.isCollection && gs.collectionName == "Favorites" -> item is SystemListViewModel.ListItem.FavoritesItem
                gs.platformTag == "tools" -> item is SystemListViewModel.ListItem.ToolsFolder
                gs.platformTag == "ports" -> item is SystemListViewModel.ListItem.PortsFolder
                gs.platformTag.isNotEmpty() -> item is SystemListViewModel.ListItem.PlatformItem && item.platform.tag == gs.platformTag
                else -> false
            }
        }
        if (currentIndex == -1) return

        val newIndex = (currentIndex + delta).mod(items.size)
        navigating = true
        when (val target = items[newIndex]) {
            is SystemListViewModel.ListItem.FavoritesItem -> {
                gameListViewModel.loadCollection("Favorites") {
                    scanResumableGames()
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.CollectionsFolder -> {
                gameListViewModel.loadCollectionsList {
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.PlatformItem -> {
                gameListViewModel.loadPlatform(target.platform.tag) {
                    scanResumableGames()
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.ToolsFolder -> {
                gameListViewModel.loadApkList("tools", target.name) {
                    navigating = false
                }
            }
            is SystemListViewModel.ListItem.PortsFolder -> {
                gameListViewModel.loadApkList("ports", target.name) {
                    navigating = false
                }
            }
            else -> { navigating = false }
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
