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
import dev.cannoli.scorza.input.CannoliAccessibilityService
import dev.cannoli.scorza.input.InputHandler
import dev.cannoli.scorza.launcher.ApkLauncher
import dev.cannoli.scorza.launcher.EmuLauncher
import dev.cannoli.scorza.launcher.LaunchManager
import dev.cannoli.scorza.launcher.RetroArchLauncher
import dev.cannoli.scorza.navigation.AppNavGraph
import dev.cannoli.scorza.navigation.LauncherScreen
import dev.cannoli.scorza.libretro.LibretroActivity
import dev.cannoli.scorza.libretro.LibretroInput
import dev.cannoli.scorza.libretro.RetroAchievementsManager
import dev.cannoli.scorza.libretro.ShortcutAction
import dev.cannoli.scorza.scanner.FileScanner
import dev.cannoli.scorza.scanner.PlatformResolver
import dev.cannoli.scorza.settings.GlobalOverridesManager
import dev.cannoli.scorza.settings.SettingsRepository
import dev.cannoli.scorza.ui.components.COLOR_GRID_COLS
import dev.cannoli.scorza.ui.components.handleKeyboardConfirm
import dev.cannoli.scorza.ui.components.HEX_KEYS
import dev.cannoli.scorza.ui.components.CREDITS
import dev.cannoli.scorza.ui.components.HEX_ROW_SIZE
import dev.cannoli.scorza.ui.components.getKeyboardRows
import dev.cannoli.scorza.ui.screens.ColorEntry
import dev.cannoli.scorza.ui.screens.CoreMappingEntry
import dev.cannoli.scorza.ui.screens.CorePickerOption
import dev.cannoli.scorza.ui.screens.DialogState
import dev.cannoli.scorza.ui.screens.asKeyboardState
import dev.cannoli.scorza.ui.screens.withBackspace
import dev.cannoli.scorza.ui.screens.withCaps
import dev.cannoli.scorza.ui.screens.withSymbols
import dev.cannoli.scorza.ui.screens.withCursor
import dev.cannoli.scorza.ui.screens.withInsertedChar
import dev.cannoli.scorza.ui.screens.withKeyboard
import dev.cannoli.scorza.ui.screens.withMenuDelta
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
import kotlinx.coroutines.SupervisorJob
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
    private val currentScreen: LauncherScreen get() = screenStack.lastOrNull() ?: LauncherScreen.SystemList
    private var resumableGames by mutableStateOf(emptySet<String>())
    private val dialogState = MutableStateFlow<DialogState>(DialogState.None)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controlButtons = LibretroInput().buttons
    private val controlButtonCount = controlButtons.size
    @Volatile private var navigating = false
    private var loginManager: RetroAchievementsManager? = null
    private val loginPollHandler = Handler(Looper.getMainLooper())
    private val loginPollRunnable: Runnable = object : Runnable {
        override fun run() {
            loginManager?.idle()
            if (loginManager != null) loginPollHandler.postDelayed(this, 100)
        }
    }
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
                val action = ShortcutAction.entries.getOrNull(screen.selectedIndex) ?: return
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

    private val controlListenTimeoutMs = 3000
    private val controlListenTickMs = 100L

    private val controlListenRunnable = object : Runnable {
        override fun run() {
            val screen = screenStack.lastOrNull() as? LauncherScreen.ControlBinding ?: return
            if (screen.listeningIndex < 0) return
            val newMs = screen.listenCountdownMs + controlListenTickMs.toInt()
            if (newMs >= controlListenTimeoutMs) {
                screenStack[screenStack.lastIndex] = screen.copy(listeningIndex = -1, listenCountdownMs = 0)
            } else {
                screenStack[screenStack.lastIndex] = screen.copy(listenCountdownMs = newMs)
                shortcutCountdownHandler.postDelayed(this, controlListenTickMs)
            }
        }
    }

    private lateinit var globalOverrides: GlobalOverridesManager
    private lateinit var launchManager: LaunchManager

    private fun pushScreen(new: LauncherScreen) {
        val current = currentScreen
        screenStack[screenStack.lastIndex] = saveScrollPosition(current)
        screenStack.add(new)
    }

    private fun saveScrollPosition(screen: LauncherScreen): LauncherScreen = when (screen) {
        is LauncherScreen.CoreMapping -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.CorePicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ColorList -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.CollectionPicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ChildPicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.AppPicker -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ControlBinding -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.ShortcutBinding -> screen.copy(scrollTarget = currentFirstVisible)
        is LauncherScreen.Credits -> screen.copy(scrollTarget = currentFirstVisible)
        else -> screen
    }

    private fun pageJump(direction: Int) {
        val page = currentPageSize.coerceAtLeast(1)

        val screen = currentScreen
        val (itemCount, selectedIndex) = when (screen) {
            LauncherScreen.SystemList -> systemListViewModel.state.value.let { it.items.size to it.selectedIndex }
            LauncherScreen.GameList -> gameListViewModel.state.value.let { it.games.size to it.selectedIndex }
            LauncherScreen.Settings -> settingsViewModel.state.value.let { it.categories.size to it.categoryIndex }
            is LauncherScreen.CoreMapping -> screen.mappings.size to screen.selectedIndex
            is LauncherScreen.CorePicker -> screen.cores.size to screen.selectedIndex
            is LauncherScreen.ColorList -> screen.colors.size to screen.selectedIndex
            is LauncherScreen.CollectionPicker -> screen.collections.size to screen.selectedIndex
            is LauncherScreen.ChildPicker -> screen.collections.size to screen.selectedIndex
            is LauncherScreen.AppPicker -> screen.apps.size to screen.selectedIndex
            is LauncherScreen.ControlBinding -> controlButtonCount to screen.selectedIndex
            is LauncherScreen.ShortcutBinding -> ShortcutAction.entries.size to screen.selectedIndex
            is LauncherScreen.Credits -> CREDITS.size to screen.selectedIndex
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
            is LauncherScreen.ChildPicker -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.AppPicker -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.ControlBinding -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.ShortcutBinding -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
            is LauncherScreen.Credits -> screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = newIdx, scrollTarget = newScroll)
        }
    }

    private fun updateColorListOnStack(settingKey: String, entries: List<ColorEntry>) {
        val cl = currentScreen
        if (cl is LauncherScreen.ColorList) {
            screenStack[screenStack.lastIndex] = cl.copy(
                colors = entries,
                selectedIndex = entries.indexOfFirst { it.key == settingKey }.coerceAtLeast(0)
            )
        }
    }

    private fun refreshCollectionPickerOnStack() {
        val cp = currentScreen
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

    private fun uriToPath(uri: android.net.Uri): String? {
        return uri.path?.let { raw ->
            val prefix = "/tree/primary:"
            if (raw.startsWith(prefix)) {
                "/storage/emulated/0/" + raw.removePrefix(prefix)
            } else {
                val match = Regex("^/tree/([A-Fa-f0-9-]+):(.*)$").find(raw)
                if (match != null) "/storage/${match.groupValues[1]}/${match.groupValues[2]}"
                else raw
            }
        }?.let { if (it.endsWith("/")) it else "$it/" }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            uriToPath(uri)?.let { settings.sdCardRoot = it }
        }
    }

    private val setupFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            uriToPath(uri)?.let { path ->
                inSetup = false
                settings.sdCardRoot = path
                settings.setupCompleted = true
                initializeApp()
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
        if (settings.setupCompleted) {
            initializeApp()
        } else {
            val detected = detectExistingCannoli()
            if (detected != null) {
                settings.sdCardRoot = detected
                settings.setupCompleted = true
                initializeApp()
            } else {
                showSetupScreen()
            }
        }
    }

    private fun detectExistingCannoli(): String? {
        val volumes = detectStorageVolumes()
        for ((_, path) in volumes.reversed()) {
            val cannoli = File(path, "Cannoli")
            if (cannoli.exists() && cannoli.isDirectory) {
                return cannoli.absolutePath + "/"
            }
        }
        return null
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
        if (LibretroActivity.isRunning) {
            val intent = Intent(this, LibretroActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            return
        }
        if (::inputHandler.isInitialized) {
            CannoliAccessibilityService.onMenuKey = { action ->
                if (action == KeyEvent.ACTION_DOWN) runOnUiThread { inputHandler.onSelect() }
            }
        }
        if (::systemListViewModel.isInitialized) {
            rescanSystemList()
            if (screenStack.lastOrNull() is LauncherScreen.GameList) {
                gameListViewModel.reload()
                scanResumableGames()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        CannoliAccessibilityService.onMenuKey = null
    }

    override fun onDestroy() {
        super.onDestroy()
        settings.shutdown()
        ioScope.cancel()
        dev.cannoli.scorza.server.KitchenManager.stop()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (inSetup) {
            handleSetupInput(keyCode)
            return true
        }
        if (handleBindingKeyDown(keyCode)) {
            return true
        }
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
                if (screen.listeningIndex < 0 || screen.listeningIndex >= controlButtons.size) return false
                shortcutCountdownHandler.removeCallbacks(controlListenRunnable)
                val btn = controlButtons[screen.listeningIndex]
                screenStack[screenStack.lastIndex] = screen.copy(
                    controls = screen.controls + (btn.prefKey to keyCode),
                    listeningIndex = -1, listenCountdownMs = 0
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
            KeyEvent.KEYCODE_DPAD_UP -> setupSelectedIndex = (setupSelectedIndex - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> setupSelectedIndex = (setupSelectedIndex + 1).coerceAtMost(1)
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

    private fun initializeApp() {
        val root = File(settings.sdCardRoot)

        retroArchLauncher = RetroArchLauncher(this) { settings.retroArchPackage }
        emuLauncher = EmuLauncher(this)
        apkLauncher = ApkLauncher(this)

        val coreInfo = dev.cannoli.scorza.scanner.CoreInfoRepository(assets)
        coreInfo.load()
        val bundledCoresDir = LaunchManager.extractBundledCores(this)
        platformResolver = PlatformResolver(root, assets, coreInfo, bundledCoresDir)
        platformResolver.load()

        launchManager = LaunchManager(this, settings, platformResolver, retroArchLauncher, emuLauncher, apkLauncher)

        scanner = FileScanner(root, platformResolver)
        scanner.ensureDirectories()
        launchManager.ensureRetroArchConfig(root)

        systemListViewModel = SystemListViewModel(scanner)
        gameListViewModel = GameListViewModel(scanner, platformResolver)
        settingsViewModel = SettingsViewModel(settings, root, packageManager)
        atomicRename = AtomicRename(root)

        globalOverrides = GlobalOverridesManager { settings.sdCardRoot }

        inputHandler = InputHandler(
            getButtonLayout = { settings.buttonLayout },
            getSwapStartSelect = { settings.swapStartSelect },
            getButtonMappings = {
                (screenStack.lastOrNull() as? LauncherScreen.ControlBinding)?.controls
                    ?: globalOverrides.readControls()
            }
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
                        currentScreen = currentScreen,
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

    private fun wrapIndex(current: Int, delta: Int, size: Int): Int =
        if (size == 0) 0 else (current + delta).mod(size)

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
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveUp()
                        else systemListViewModel.moveSelection(-1)
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveUp()
                        else gameListViewModel.moveSelection(-1)
                    }
                    LauncherScreen.Settings -> settingsViewModel.moveSelection(-1)
                    is LauncherScreen.CoreMapping -> if (screen.mappings.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.mappings.size))
                    is LauncherScreen.CorePicker -> if (screen.cores.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.cores.size))
                    is LauncherScreen.AppPicker -> if (screen.apps.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.apps.size))
                    is LauncherScreen.ColorList -> if (screen.colors.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.colors.size))
                    is LauncherScreen.CollectionPicker -> if (screen.collections.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.collections.size))
                    is LauncherScreen.ChildPicker -> if (screen.collections.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, screen.collections.size))
                    is LauncherScreen.ControlBinding ->
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, controlButtonCount))
                    is LauncherScreen.ShortcutBinding -> if (!screen.listening)
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, ShortcutAction.entries.size))
                    is LauncherScreen.Credits ->
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, -1, CREDITS.size))
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
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.reorderMoveDown()
                        else systemListViewModel.moveSelection(1)
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isReorderMode()) gameListViewModel.reorderMoveDown()
                        else gameListViewModel.moveSelection(1)
                    }
                    LauncherScreen.Settings -> settingsViewModel.moveSelection(1)
                    is LauncherScreen.CoreMapping -> if (screen.mappings.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.mappings.size))
                    is LauncherScreen.CorePicker -> if (screen.cores.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.cores.size))
                    is LauncherScreen.AppPicker -> if (screen.apps.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.apps.size))
                    is LauncherScreen.ColorList -> if (screen.colors.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.colors.size))
                    is LauncherScreen.CollectionPicker -> if (screen.collections.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.collections.size))
                    is LauncherScreen.ChildPicker -> if (screen.collections.isNotEmpty())
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, screen.collections.size))
                    is LauncherScreen.ControlBinding ->
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, controlButtonCount))
                    is LauncherScreen.ShortcutBinding -> if (!screen.listening)
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, ShortcutAction.entries.size))
                    is LauncherScreen.Credits ->
                        screenStack[screenStack.lastIndex] = screen.copy(selectedIndex = wrapIndex(screen.selectedIndex, 1, CREDITS.size))
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
                    dialogState.value = ds.copy(selectedIndex = (curRow * rowSize + newCol).coerceAtMost(HEX_KEYS.lastIndex))
                }
                DialogState.None -> when (currentScreen) {
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
                    dialogState.value = ds.copy(selectedIndex = (curRow * rowSize + newCol).coerceAtMost(HEX_KEYS.lastIndex))
                }
                DialogState.None -> when (currentScreen) {
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
                is DialogState.DeleteConfirm -> onDeleteConfirm(ds)
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
                    val glState = gameListViewModel.state.value
                    val deletingFromParent = glState.isCollection && !glState.isCollectionsList
                    pendingContextReturn = null
                    dialogState.value = DialogState.None
                    if (!deletingFromParent) gameListViewModel.saveCollectionsPosition()
                    ioScope.launch {
                        scanner.deleteCollection(name)
                        if (deletingFromParent) {
                            gameListViewModel.reload()
                            rescanSystemList()
                        } else {
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
                }
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.confirmReorder()
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
                            } else if (cat?.key == "retroachievements" && settings.raToken.isNotEmpty()) {
                                dialogState.value = DialogState.RAAccount(username = settings.raUsername)
                            } else if (cat?.key == "kitchen") {
                                val root = File(settings.sdCardRoot)
                                val km = dev.cannoli.scorza.server.KitchenManager
                                if (!km.isRunning) km.toggle(root, assets)
                                dialogState.value = DialogState.Kitchen(
                                    url = km.getUrl(),
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
                                "controls" -> pushScreen(LauncherScreen.ControlBinding(controls = globalOverrides.readControls()))
                                "shortcuts" -> pushScreen(LauncherScreen.ShortcutBinding(shortcuts = globalOverrides.readShortcuts()))
                                "core_mapping" -> screenStack.add(LauncherScreen.CoreMapping(
                                    mappings = platformResolver.getDetailedMappings(packageManager)
                                ))
                                "manage_tools" -> openAppPicker("tools")
                                "manage_ports" -> openAppPicker("ports")
                                "ra_username" -> {
                                    val current = settings.raUsername
                                    dialogState.value = DialogState.RenameInput(
                                        gameName = "ra_username",
                                        currentName = current,
                                        cursorPos = current.length
                                    )
                                }
                                "ra_password" -> {
                                    if (settings.raUsername.isEmpty()) {
                                        android.widget.Toast.makeText(this, "Set username first", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        dialogState.value = DialogState.RenameInput(
                                            gameName = "ra_password",
                                            currentName = "",
                                            cursorPos = 0
                                        )
                                    }
                                }
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
                        screen.mappings.getOrNull(screen.selectedIndex)?.let { entry ->
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
                    }
                    is LauncherScreen.CorePicker -> onCorePickerConfirm(screen)
                    is LauncherScreen.ColorList -> {
                        screen.colors.getOrNull(screen.selectedIndex)?.let { entry ->
                            openColorPicker(entry.key)
                        }
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
                    is LauncherScreen.ChildPicker -> {
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
                            screenStack[screenStack.lastIndex] = screen.copy(listeningIndex = screen.selectedIndex, listenCountdownMs = 0)
                            shortcutCountdownHandler.postDelayed(controlListenRunnable, controlListenTickMs)
                        }
                    }
                    is LauncherScreen.ShortcutBinding -> {
                        if (!screen.listening) {
                            screenStack[screenStack.lastIndex] = screen.copy(
                                listening = true, heldKeys = emptySet(), countdownMs = 0
                            )
                        }
                    }
                    is LauncherScreen.Credits -> {}
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
                is DialogState.Kitchen,
                is DialogState.RAAccount -> {
                    dialogState.value = DialogState.None
                    rescanSystemList()
                }
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) systemListViewModel.cancelReorder(showTools = settings.showTools, showPorts = settings.showPorts, showEmpty = settings.showEmpty, toolsName = settings.toolsName, portsName = settings.portsName)
                    }
                    LauncherScreen.GameList -> {
                        if (gameListViewModel.isMultiSelectMode()) {
                            gameListViewModel.cancelMultiSelect()
                        } else if (gameListViewModel.isReorderMode()) {
                            gameListViewModel.cancelReorder()
                        } else if (!navigating) {
                            val glState = gameListViewModel.state.value
                            if (!gameListViewModel.exitSubfolder()) {
                                if (gameListViewModel.exitChildCollection()) {
                                    // navigated back to parent collection
                                } else if (glState.isCollection && glState.collectionName != null
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
                            val cm = screenStack.lastOrNull()
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
                    is LauncherScreen.ChildPicker -> {
                        onChildPickerConfirm(screen)
                    }
                    is LauncherScreen.ControlBinding -> {
                        globalOverrides.saveControls(screen.controls)
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.ShortcutBinding -> {
                        cancelShortcutListening()
                        globalOverrides.saveShortcuts(screen.shortcuts)
                        screenStack.removeAt(screenStack.lastIndex)
                    }
                    is LauncherScreen.Credits -> {
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
                DialogState.None -> when (currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) {
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
                                    options.add(MENU_REMOVE_FROM_COLLECTION)
                                }
                                options.addAll(listOf(MENU_MANAGE_COLLECTIONS, MENU_DELETE_ART, MENU_DELETE_GAME))
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
                            val menuName = if (game.isChildCollection) game.displayName.removePrefix("/") else game.displayName
                            dialogState.value = DialogState.ContextMenu(
                                gameName = menuName,
                                options = buildGameContextOptions(game, glState)
                            )
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
                    val (newCaps, newSymbols) = when {
                        ks.symbols -> false to false
                        ks.caps -> false to true
                        else -> true to false
                    }
                    dialogState.value = ds.withCaps(newCaps).withSymbols(newSymbols)
                }
                DialogState.None -> when (currentScreen) {
                    LauncherScreen.SystemList -> {
                        if (systemListViewModel.isReorderMode()) {
                            systemListViewModel.confirmReorder()
                        } else {
                            systemListViewModel.enterReorderMode()
                        }
                    }
                    LauncherScreen.GameList -> {
                        val glState = gameListViewModel.state.value
                        if (glState.isCollectionsList || gameListViewModel.hasChildCollections()) {
                            if (gameListViewModel.isReorderMode()) {
                                gameListViewModel.confirmReorder()
                            } else {
                                gameListViewModel.enterReorderMode()
                            }
                        } else if (glState.subfolderPath == null && glState.platformTag != "tools" && glState.platformTag != "ports") {
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
                DialogState.About -> {
                    dialogState.value = DialogState.None
                    screenStack.add(LauncherScreen.Credits())
                }
                is DialogState.Kitchen -> {
                    dev.cannoli.scorza.server.KitchenManager.stop()
                    dialogState.value = DialogState.None
                    rescanSystemList()
                }
                is DialogState.RAAccount -> {
                    settings.raUsername = ""
                    settings.raToken = ""
                    settingsViewModel.load()
                    dialogState.value = DialogState.None
                }
                is DialogState.ColorPicker -> {
                    val currentHex = settingsViewModel.getColorHex(ds.settingKey).removePrefix("#")
                    dialogState.value = DialogState.HexColorInput(
                        settingKey = ds.settingKey,
                        currentHex = currentHex
                    )
                }
                DialogState.None -> when (val screen = currentScreen) {
                    LauncherScreen.SystemList -> {
                        systemListViewModel.savePosition()
                        settingsViewModel.load()
                        screenStack.add(LauncherScreen.Settings)
                    }
                    LauncherScreen.GameList -> {
                        val glState = gameListViewModel.state.value
                        if (glState.isCollectionsList) {
                            dialogState.value = DialogState.NewCollectionInput(gamePaths = emptyList())
                        } else {
                            val game = gameListViewModel.getSelectedGame()
                            if (game != null && !game.isSubfolder && !game.isChildCollection) {
                                launchManager.resumeGame(game)
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
                            ShortcutAction.entries.getOrNull(screen.selectedIndex)?.let { action ->
                                screenStack[screenStack.lastIndex] = screen.copy(
                                    shortcuts = screen.shortcuts + (action to emptySet())
                                )
                            }
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
                    when (currentScreen) {
                        LauncherScreen.SystemList -> {
                            val km = dev.cannoli.scorza.server.KitchenManager
                            if (km.isRunning || systemListViewModel.state.value.items.isEmpty()) {
                                val root = File(settings.sdCardRoot)
                                if (!km.isRunning) km.toggle(root, assets)
                                dialogState.value = DialogState.Kitchen(
                                    url = km.getUrl(),
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
                DialogState.None -> if (currentScreen == LauncherScreen.GameList && settings.platformSwitching) switchPlatform(-1)
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
                DialogState.None -> if (currentScreen == LauncherScreen.GameList && settings.platformSwitching) switchPlatform(1)
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
            .sortedBy { it.first.lowercase(java.util.Locale.ROOT) }
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
            options = listOf(MENU_RENAME)
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
                gameListViewModel.loadPlatform(item.platform.tag, item.platform.allTags) {
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

        if (game.isChildCollection) {
            navigating = true
            val childName = game.displayName.removePrefix("/")
            gameListViewModel.enterChildCollection(childName) {
                navigating = false
            }
            return
        }

        if (game.isSubfolder) {
            gameListViewModel.enterSubfolder(game.file.name)
            return
        }

        val errorDialog = launchManager.launchGame(game)
        if (errorDialog != null) dialogState.value = errorDialog
    }

    private fun scanResumableGames() {
        val games = gameListViewModel.state.value.games
        ioScope.launch {
            val result = launchManager.findResumableGames(games)
            withContext(Dispatchers.Main) { resumableGames = result }
        }
    }

    private fun onCorePickerConfirm(screen: LauncherScreen.CorePicker) {
        val chosen = screen.cores.getOrNull(screen.selectedIndex) ?: return
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
            val cm = screenStack.lastOrNull()
            if (cm is LauncherScreen.CoreMapping) {
                val mappings = platformResolver.getDetailedMappings(packageManager)
                val idx = mappings.indexOfFirst { it.tag == screen.tag }.coerceAtLeast(0)
                screenStack[screenStack.lastIndex] = cm.copy(mappings = mappings, selectedIndex = idx)
            }
        }
    }

    private fun buildGameContextOptions(game: dev.cannoli.scorza.model.Game, glState: dev.cannoli.scorza.ui.viewmodel.GameListViewModel.State): List<String> {
        if (glState.isCollectionsList || game.isChildCollection) return listOf(MENU_RENAME, MENU_CHILD_COLLECTIONS, MENU_DELETE)
        if (game.isSubfolder) return listOf(MENU_RENAME, MENU_DELETE)
        return buildList {
            addAll(gameContextOptions)
            if (game.artFile != null) {
                val idx = indexOf(MENU_DELETE_GAME)
                if (idx >= 0) add(idx, MENU_DELETE_ART) else add(MENU_DELETE_ART)
            }
        }
    }

    companion object {
        private const val MENU_RENAME = "Rename"
        private const val MENU_DELETE = "Delete"
        private const val MENU_DELETE_GAME = "Delete Game"
        private const val MENU_DELETE_ART = "Delete Art"
        private const val MENU_MANAGE_COLLECTIONS = "Manage Collections"
        private const val MENU_EMULATOR_OVERRIDE = "Emulator Override"
        private const val MENU_REMOVE_FROM_COLLECTION = "Remove from Collection"
        private const val MENU_CHILD_COLLECTIONS = "Child Collections"
    }

    private val gameContextOptions = listOf(MENU_MANAGE_COLLECTIONS, MENU_EMULATOR_OVERRIDE, MENU_RENAME, MENU_DELETE_GAME)

    private fun onContextMenuConfirm(state: DialogState.ContextMenu) {
        if (currentScreen == LauncherScreen.SystemList) {
            when (state.options[state.selectedOption]) {
                MENU_RENAME -> {
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
            MENU_RENAME -> {
                if (glState.isCollectionsList || game.isChildCollection) {
                    val collName = if (game.isChildCollection) game.displayName.removePrefix("/") else game.displayName
                    dialogState.value = DialogState.CollectionRenameInput(
                        oldName = collName,
                        currentName = collName,
                        cursorPos = collName.length
                    )
                } else {
                    val name = game.displayName.removePrefix("★ ")
                    dialogState.value = DialogState.RenameInput(
                        gameName = name,
                        currentName = name,
                        cursorPos = name.length
                    )
                }
            }
            MENU_DELETE, MENU_DELETE_GAME -> {
                if (glState.isCollectionsList || game.isChildCollection) {
                    val collName = if (game.isChildCollection) game.displayName.removePrefix("/") else game.displayName
                    dialogState.value = DialogState.DeleteCollectionConfirm(collectionName = collName)
                } else {
                    dialogState.value = DialogState.DeleteConfirm(gameName = game.displayName)
                }
            }
            MENU_MANAGE_COLLECTIONS -> {
                openCollectionManager(listOf(game.file.absolutePath), game.displayName)
            }
            MENU_CHILD_COLLECTIONS -> {
                val collName = if (game.isChildCollection) game.displayName.removePrefix("/") else game.displayName
                openChildPicker(collName)
            }
            MENU_DELETE_ART -> {
                pendingContextReturn = null
                game.artFile?.delete()
                scanner.invalidateArtCache()
                gameListViewModel.reload()
                dialogState.value = DialogState.None
            }
            MENU_EMULATOR_OVERRIDE -> {
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

    private fun onDeleteConfirm(state: DialogState.DeleteConfirm) {
        pendingContextReturn = null
        if (state.bulkPaths != null) {
            val games = gameListViewModel.state.value.games
            val pathSet = state.bulkPaths.toSet()
            val toDelete = games.filter { it.file.absolutePath in pathSet }
            ioScope.launch {
                toDelete.forEach { scanner.deleteGame(it) }
                gameListViewModel.reload()
                rescanSystemList()
                withContext(Dispatchers.Main) { dialogState.value = DialogState.None }
            }
        } else {
            val game = gameListViewModel.getSelectedGame() ?: return
            ioScope.launch {
                scanner.deleteGame(game)
                gameListViewModel.reload()
                rescanSystemList()
                withContext(Dispatchers.Main) { dialogState.value = DialogState.None }
            }
        }
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
                if (game != null) {
                    val glState = gameListViewModel.state.value
                    dialogState.value = DialogState.ContextMenu(
                        gameName = game.displayName,
                        options = buildGameContextOptions(game, glState)
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
        } else {
            gamePaths.map { scanner.getCollectionsContaining(it) }
                .reduceOrNull { acc, set -> acc intersect set } ?: emptySet()
        }
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

    private fun openChildPicker(collectionName: String) {
        val allNames = scanner.getCollectionNames()
            .filter { !it.equals("Favorites", ignoreCase = true) }
        val ancestors = scanner.getAncestors(collectionName)
        val available = allNames.filter { it != collectionName && it !in ancestors }
        val currentChildren = scanner.getChildCollections(collectionName).toSet()
        val initialChecked = available.indices
            .filter { available[it] in currentChildren }
            .toSet()
        dialogState.value = DialogState.None
        screenStack.add(LauncherScreen.ChildPicker(
            collectionName = collectionName,
            collections = available,
            selectedIndex = 0,
            checkedIndices = initialChecked,
            initialChecked = initialChecked
        ))
    }

    private fun onChildPickerConfirm(screen: LauncherScreen.ChildPicker) {
        val selected = screen.checkedIndices
            .mapNotNull { screen.collections.getOrNull(it) }
            .toSet()
        ioScope.launch {
            scanner.setChildCollections(screen.collectionName, selected)
            gameListViewModel.reload()
            rescanSystemList()
        }
        screenStack.removeAt(screenStack.lastIndex)
        restoreContextMenu()
    }

    private fun onBulkContextMenuConfirm(state: DialogState.BulkContextMenu) {
        pendingContextReturn = ContextReturn.Bulk(state.gamePaths, state.options)
        when (state.options[state.selectedOption]) {
            MENU_MANAGE_COLLECTIONS -> {
                openCollectionManager(state.gamePaths, "${state.gamePaths.size} Selected")
            }
            MENU_DELETE_GAME -> {
                pendingContextReturn = null
                dialogState.value = DialogState.DeleteConfirm(
                    gameName = "${state.gamePaths.size} items",
                    bulkPaths = state.gamePaths
                )
            }
            MENU_DELETE_ART -> {
                pendingContextReturn = null
                val games = gameListViewModel.state.value.games
                val pathSet = state.gamePaths.toSet()
                games.filter { it.file.absolutePath in pathSet }
                    .mapNotNull { it.artFile }
                    .forEach { it.delete() }
                scanner.invalidateArtCache()
                gameListViewModel.reload()
                dialogState.value = DialogState.None
            }
            MENU_REMOVE_FROM_COLLECTION -> {
                pendingContextReturn = null
                val collName = gameListViewModel.state.value.collectionName ?: return
                ioScope.launch {
                    state.gamePaths.forEach { path ->
                        scanner.removeFromCollection(collName, path)
                    }
                    gameListViewModel.reload()
                    rescanSystemList()
                }
                dialogState.value = DialogState.None
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
        val glState = gameListViewModel.state.value
        val renamingFromParent = glState.isCollection && !glState.isCollectionsList
        dialogState.value = DialogState.None
        ioScope.launch {
            scanner.renameCollection(state.oldName, newName)
            if (renamingFromParent) {
                gameListViewModel.reload()
            } else {
                gameListViewModel.loadCollectionsList(restoreIndex = true)
            }
        }
    }

    private fun onRenameConfirm(state: DialogState.RenameInput) {
        if (state.gameName == "ra_username") {
            settings.raUsername = state.currentName.trim()
            settingsViewModel.load()
            dialogState.value = DialogState.None
            return
        }
        if (state.gameName == "ra_password") {
            val password = state.currentName.trim()
            if (password.isNotEmpty()) {
                val ra = RetroAchievementsManager(
                    onLogin = { success, nameOrError, token ->
                        if (success && token != null) {
                            settings.raUsername = nameOrError
                            settings.raToken = token
                            android.widget.Toast.makeText(this@MainActivity, "Logged in as $nameOrError", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(this@MainActivity, "Login failed: $nameOrError", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        settingsViewModel.load()
                        loginPollHandler.removeCallbacks(loginPollRunnable)
                        loginManager?.destroy()
                        loginManager = null
                    }
                )
                ra.init()
                ra.loginWithPassword(settings.raUsername, password)
                loginManager = ra
                loginPollHandler.postDelayed(loginPollRunnable, 100)
                android.widget.Toast.makeText(this, "Logging in...", android.widget.Toast.LENGTH_SHORT).show()
            }
            dialogState.value = DialogState.None
            return
        }
        if (currentScreen == LauncherScreen.SystemList) {
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
                val result = atomicRename.rename(game.file, newName, game.platformTag)
                if (!result.success) {
                    withContext(Dispatchers.Main) {
                        dialogState.value = DialogState.RenameResult(false, result.error ?: "Rename failed")
                    }
                }
            }
            scanner.invalidateArtCache()
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
                gs.isCollection && gs.collectionName.equals("Favorites", ignoreCase = true) -> item is SystemListViewModel.ListItem.FavoritesItem
                gs.isCollection -> item is SystemListViewModel.ListItem.CollectionsFolder
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
                gameListViewModel.loadPlatform(target.platform.tag, target.platform.allTags) {
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
