package dev.cannoli.launcher

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
import android.view.WindowManager
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
import dev.cannoli.launcher.input.InputHandler
import dev.cannoli.launcher.launcher.ApkLauncher
import dev.cannoli.launcher.launcher.EmuLauncher
import dev.cannoli.launcher.launcher.LaunchResult
import dev.cannoli.launcher.launcher.RetroArchLauncher
import dev.cannoli.launcher.model.LaunchTarget
import dev.cannoli.launcher.navigation.AppNavGraph
import dev.cannoli.launcher.navigation.Routes
import dev.cannoli.launcher.scanner.FileScanner
import dev.cannoli.launcher.scanner.PlatformResolver
import dev.cannoli.launcher.settings.ScrollSpeed
import dev.cannoli.launcher.settings.SettingsRepository
import dev.cannoli.launcher.ui.screens.DialogState
import dev.cannoli.launcher.ui.theme.CannoliTheme
import dev.cannoli.launcher.ui.theme.initFonts
import dev.cannoli.launcher.ui.viewmodel.GameListViewModel
import dev.cannoli.launcher.ui.viewmodel.SettingsViewModel
import dev.cannoli.launcher.ui.viewmodel.SystemListViewModel
import dev.cannoli.launcher.util.AtomicRename
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
    private val pageJumpSize = 10
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasStoragePermission()) {
            initializeApp()
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
                is DialogState.ContextMenu -> {
                    val newIdx = if (ds.selectedOption <= 0) ds.options.lastIndex else ds.selectedOption - 1
                    dialogState.value = ds.copy(selectedOption = newIdx)
                }
                is DialogState.CollectionPicker -> {
                    val total = ds.collections.size + 1
                    val newIdx = if (ds.selectedIndex <= 0) total - 1 else ds.selectedIndex - 1
                    dialogState.value = ds.copy(selectedIndex = newIdx)
                }
                is DialogState.RenameInput -> {
                    val chars = ds.currentName.toCharArray()
                    if (ds.cursorPos < chars.size) {
                        chars[ds.cursorPos] = nextChar(chars[ds.cursorPos], 1)
                        dialogState.value = ds.copy(currentName = String(chars))
                    }
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> systemListViewModel.moveSelection(-1)
                    Screen.GAME_LIST -> gameListViewModel.moveSelection(-1)
                    Screen.SETTINGS -> settingsViewModel.moveSelection(-1)
                }
                else -> {}
            }
        }

        inputHandler.onDown = {
            when (val ds = dialogState.value) {
                is DialogState.ContextMenu -> {
                    val newIdx = if (ds.selectedOption >= ds.options.lastIndex) 0 else ds.selectedOption + 1
                    dialogState.value = ds.copy(selectedOption = newIdx)
                }
                is DialogState.CollectionPicker -> {
                    val total = ds.collections.size + 1
                    val newIdx = if (ds.selectedIndex >= total - 1) 0 else ds.selectedIndex + 1
                    dialogState.value = ds.copy(selectedIndex = newIdx)
                }
                is DialogState.RenameInput -> {
                    val chars = ds.currentName.toCharArray()
                    if (ds.cursorPos < chars.size) {
                        chars[ds.cursorPos] = nextChar(chars[ds.cursorPos], -1)
                        dialogState.value = ds.copy(currentName = String(chars))
                    }
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> systemListViewModel.moveSelection(1)
                    Screen.GAME_LIST -> gameListViewModel.moveSelection(1)
                    Screen.SETTINGS -> settingsViewModel.moveSelection(1)
                }
                else -> {}
            }
        }

        inputHandler.onLeft = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> {
                    if (ds.cursorPos > 0) {
                        dialogState.value = ds.copy(cursorPos = ds.cursorPos - 1)
                    }
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> systemListViewModel.moveSelection(-pageJumpSize)
                    Screen.GAME_LIST -> gameListViewModel.moveSelection(-pageJumpSize)
                    Screen.SETTINGS -> if (settingsViewModel.state.value.inSubList) settingsViewModel.cycleSelected(-1)
                }
                else -> {}
            }
        }

        inputHandler.onRight = {
            when (val ds = dialogState.value) {
                is DialogState.RenameInput -> {
                    if (ds.cursorPos < ds.currentName.length - 1) {
                        dialogState.value = ds.copy(cursorPos = ds.cursorPos + 1)
                    }
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> systemListViewModel.moveSelection(pageJumpSize)
                    Screen.GAME_LIST -> gameListViewModel.moveSelection(pageJumpSize)
                    Screen.SETTINGS -> if (settingsViewModel.state.value.inSubList) settingsViewModel.cycleSelected(1)
                }
                else -> {}
            }
        }

        inputHandler.onConfirm = {
            when (val ds = dialogState.value) {
                is DialogState.ContextMenu -> onContextMenuConfirm(ds)
                is DialogState.DeleteConfirm -> onDeleteConfirm()
                is DialogState.CollectionPicker -> onCollectionPickerConfirm(ds)
                is DialogState.RenameInput -> onRenameConfirm(ds)
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> onSystemListConfirm()
                    Screen.GAME_LIST -> onGameListConfirm()
                    Screen.SETTINGS -> {
                        if (!settingsViewModel.state.value.inSubList) {
                            settingsViewModel.enterCategory()
                        } else {
                            val key = settingsViewModel.enterSelected()
                            if (key != null) {
                                val displayValue = settingsViewModel.getSelectedItemDisplayValue()
                                dialogState.value = DialogState.RenameInput(
                                    gameName = key,
                                    currentName = displayValue,
                                    cursorPos = (displayValue.length - 1).coerceAtLeast(0)
                                )
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        inputHandler.onBack = {
            when (dialogState.value) {
                is DialogState.ContextMenu, is DialogState.DeleteConfirm,
                is DialogState.CollectionPicker, is DialogState.RenameInput,
                is DialogState.RenameResult, is DialogState.MissingCore,
                is DialogState.MissingApp -> {
                    dialogState.value = DialogState.None
                }
                DialogState.None -> when (currentScreen()) {
                    Screen.SYSTEM_LIST -> { /* root screen, nowhere to go */ }
                    Screen.GAME_LIST -> {
                        if (!gameListViewModel.exitSubfolder()) {
                            navController?.popBackStack()
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
            if (!dialogOpen()) {
                when (currentScreen()) {
                    Screen.SYSTEM_LIST -> {
                        settingsViewModel.load()
                        navController?.navigate(Routes.SETTINGS)
                    }
                    Screen.SETTINGS -> {
                        if (settingsViewModel.state.value.inSubList) {
                            settingsViewModel.exitSubList()
                        } else {
                            navController?.popBackStack()
                            systemListViewModel.scan()
                        }
                    }
                    else -> {}
                }
            }
        }

        inputHandler.onSelect = {
            if (!dialogOpen() && currentScreen() == Screen.GAME_LIST) {
                val game = gameListViewModel.getSelectedGame()
                if (game != null && !game.isSubfolder) {
                    dialogState.value = DialogState.ContextMenu(gameName = game.displayName)
                }
            }
        }

        inputHandler.onL1 = {
            if (!dialogOpen() && currentScreen() == Screen.GAME_LIST) {
                switchPlatform(-1)
            }
        }

        inputHandler.onR1 = {
            if (!dialogOpen() && currentScreen() == Screen.GAME_LIST) {
                switchPlatform(1)
            }
        }
    }

    private fun onSystemListConfirm() {
        when (val item = systemListViewModel.getSelectedItem()) {
            is SystemListViewModel.ListItem.PlatformItem -> {
                gameListViewModel.loadPlatform(item.platform.tag)
                navController?.navigate(Routes.gameList(item.platform.tag))
            }
            is SystemListViewModel.ListItem.CollectionItem -> {
                gameListViewModel.loadCollection(item.name)
                navController?.navigate(Routes.gameList("collection_${item.name}"))
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
        val game = gameListViewModel.getSelectedGame() ?: return

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
        when (state.options[state.selectedOption]) {
            "Rename" -> {
                dialogState.value = DialogState.RenameInput(
                    gameName = game.displayName,
                    currentName = game.displayName,
                    cursorPos = game.displayName.length - 1
                )
            }
            "Delete" -> {
                dialogState.value = DialogState.DeleteConfirm(gameName = game.displayName)
            }
            "Add to Collection" -> {
                val collections = scanner.getCollectionNames()
                dialogState.value = DialogState.CollectionPicker(
                    gamePath = game.file.absolutePath,
                    collections = collections,
                    selectedIndex = 0
                )
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
        val totalOptions = state.collections.size + 1 // +1 for "New Collection"
        if (state.selectedIndex < state.collections.size) {
            val collName = state.collections[state.selectedIndex]
            ioScope.launch {
                scanner.addToCollection(collName, state.gamePath)
            }
            dialogState.value = DialogState.None
        } else {
            ioScope.launch {
                val existing = scanner.getCollectionNames()
                var name = "Favorites"
                var i = 2
                while (name in existing) {
                    name = "Favorites $i"
                    i++
                }
                scanner.createCollection(name)
                scanner.addToCollection(name, state.gamePath)
            }
            dialogState.value = DialogState.None
        }
    }

    private fun onRenameConfirm(state: DialogState.RenameInput) {
        val game = gameListViewModel.getSelectedGame() ?: return
        val newName = state.currentName.trim()
        if (newName.isEmpty() || newName == game.displayName) {
            dialogState.value = DialogState.None
            return
        }

        ioScope.launch {
            val result = atomicRename.rename(game.file, newName, game.platformTag)
            dialogState.value = DialogState.RenameResult(result.success, result.error ?: "")
            if (result.success) {
                gameListViewModel.reload()
            }
        }
    }

    private fun nextChar(c: Char, direction: Int): Char {
        val min = ' '
        val max = '~'
        val range = max - min + 1
        val offset = c - min + direction
        return min + ((offset % range + range) % range)
    }

    private fun switchPlatform(delta: Int) {
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
        gameListViewModel.loadPlatform(newTag)
        navController?.let { nav ->
            nav.popBackStack()
            nav.navigate(Routes.gameList(newTag))
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
