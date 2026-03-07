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
import dev.cannoli.launcher.settings.SettingsRepository
import dev.cannoli.launcher.settings.TimeFormat
import dev.cannoli.launcher.ui.screens.DialogState
import dev.cannoli.launcher.ui.theme.CannoliTheme
import dev.cannoli.launcher.ui.viewmodel.GameListViewModel
import dev.cannoli.launcher.ui.viewmodel.SettingsViewModel
import dev.cannoli.launcher.ui.viewmodel.SystemListViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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
    private var navController: NavHostController? = null

    private val dialogState = MutableStateFlow<DialogState>(DialogState.None)
    private val pageJumpSize = 10

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
        // Block all touch input
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
        settingsViewModel = SettingsViewModel(settings)

        retroArchLauncher = RetroArchLauncher(this, settings.retroArchPackage)
        emuLauncher = EmuLauncher(this)
        apkLauncher = ApkLauncher(this)

        inputHandler = InputHandler { settings.buttonLayout }
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
                        settings = settings,
                        dialogState = dialogState
                    )
                }
            }
        }
    }

    private fun wireInput() {
        inputHandler.onUp = {
            when (currentScreen()) {
                Screen.SYSTEM_LIST -> systemListViewModel.moveSelection(-1)
                Screen.GAME_LIST -> gameListViewModel.moveSelection(-1)
                Screen.SETTINGS -> settingsViewModel.moveSelection(-1)
            }
        }

        inputHandler.onDown = {
            when (currentScreen()) {
                Screen.SYSTEM_LIST -> systemListViewModel.moveSelection(1)
                Screen.GAME_LIST -> gameListViewModel.moveSelection(1)
                Screen.SETTINGS -> settingsViewModel.moveSelection(1)
            }
        }

        inputHandler.onLeft = {
            when (currentScreen()) {
                Screen.SYSTEM_LIST -> systemListViewModel.moveSelection(-pageJumpSize)
                Screen.GAME_LIST -> gameListViewModel.moveSelection(-pageJumpSize)
                Screen.SETTINGS -> settingsViewModel.moveSelection(-pageJumpSize)
            }
        }

        inputHandler.onRight = {
            when (currentScreen()) {
                Screen.SYSTEM_LIST -> systemListViewModel.moveSelection(pageJumpSize)
                Screen.GAME_LIST -> gameListViewModel.moveSelection(pageJumpSize)
                Screen.SETTINGS -> settingsViewModel.moveSelection(pageJumpSize)
            }
        }

        inputHandler.onConfirm = {
            if (dialogState.value == DialogState.None) {
                when (currentScreen()) {
                    Screen.SYSTEM_LIST -> onSystemListConfirm()
                    Screen.GAME_LIST -> onGameListConfirm()
                    Screen.SETTINGS -> settingsViewModel.toggleSelected()
                }
            }
        }

        inputHandler.onBack = {
            if (dialogState.value != DialogState.None) {
                dialogState.value = DialogState.None
            } else {
                when (currentScreen()) {
                    Screen.SYSTEM_LIST -> { /* root screen, nowhere to go */ }
                    Screen.GAME_LIST -> {
                        if (!gameListViewModel.exitSubfolder()) {
                            navController?.popBackStack()
                        }
                    }
                    Screen.SETTINGS -> navController?.popBackStack()
                }
            }
        }

        inputHandler.onStart = {
            if (currentScreen() == Screen.SYSTEM_LIST) {
                settingsViewModel.load()
                navController?.navigate(Routes.SETTINGS)
            }
        }

        inputHandler.onSelect = {
            // Context menu — TODO
        }

        inputHandler.onL1 = {
            if (currentScreen() == Screen.GAME_LIST) {
                switchPlatform(-1)
            }
        }

        inputHandler.onR1 = {
            if (currentScreen() == Screen.GAME_LIST) {
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
                // TODO: open collection game list
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
        // Update nav back stack to reflect new tag
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
