package dev.cannoli.scorza.libretro

import android.content.Context
import android.hardware.input.InputManager
import dev.cannoli.scorza.input.CannoliAccessibilityService
import dev.cannoli.scorza.input.ControllerManager
import dev.cannoli.scorza.input.ProfileManager
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.cannoli.scorza.libretro.shader.PresetParser
import dev.cannoli.scorza.libretro.shader.ShaderPipeline
import dev.cannoli.scorza.libretro.shader.SlangTranspiler
import dev.cannoli.scorza.ui.theme.CannoliColors
import dev.cannoli.scorza.ui.theme.CannoliTheme
import dev.cannoli.scorza.ui.theme.LocalCannoliColors
import dev.cannoli.scorza.ui.theme.hexToColor
import android.os.Handler
import android.os.Looper
import java.io.File

class LibretroActivity : ComponentActivity() {

    private lateinit var runner: LibretroRunner
    private lateinit var renderer: GraphicsBackend
    private lateinit var input: LibretroInput
    private lateinit var controllerManager: ControllerManager
    private lateinit var profileManager: ProfileManager
    private lateinit var slotManager: SaveSlotManager
    private lateinit var overrideManager: OverrideManager
    private var audio: LibretroAudio? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var gameView: android.view.View? = null
    private var loading by mutableStateOf(true)

    private val screenStack = mutableStateListOf<IGMScreen>()

    private var selectedSlotIndex by mutableIntStateOf(0)
    private var slotThumbnail by mutableStateOf<Bitmap?>(null)
    private var slotExists by mutableStateOf(false)
    private var slotOccupied by mutableStateOf(emptyList<Boolean>())
    private var cleaned = false

    private var scalingMode by mutableStateOf(ScalingMode.CORE_REPORTED)
    private var screenEffect by mutableStateOf(ScreenEffect.NONE)
    private var sharpness by mutableStateOf(Sharpness.SHARP)
    private var debugHud by mutableStateOf(false)
    private var maxFfSpeed by mutableIntStateOf(4)
    private var lowLatency by mutableStateOf(false)

    private var overlay by mutableStateOf("")
    private var overlayImages = emptyList<String>()

    private var shaderPreset by mutableStateOf("")
    private var shaderPresets = emptyList<String>()
    private var shaderParams by mutableStateOf(emptyList<ShaderParamItem>())

    private var coreOptions by mutableStateOf(emptyList<LibretroRunner.CoreOption>())
    private var coreCategories by mutableStateOf(emptyList<LibretroRunner.CoreOptionCategory>())
    private var currentProfileName by mutableStateOf(ProfileManager.DEFAULT)
    private var profileNames by mutableStateOf(listOf(ProfileManager.DEFAULT))
    private var controlsSnapshot: Map<String, Int> = emptyMap()
    private var shortcutSource by mutableStateOf(OverrideSource.GLOBAL)
    private var shortcuts by mutableStateOf(mapOf<ShortcutAction, Set<Int>>())
    private val shortcutChordKeys = mutableSetOf<Int>()
    private var coreInfoText by mutableStateOf("")

    private var frontendSnapshot: OverrideManager.Settings? = null
    private var shaderParamsDirty = false
    private var platformBaseline: OverrideManager.Settings? = null
    private var defaultProfileControls = emptyMap<String, Int>()

    private var diskCount by mutableIntStateOf(0)
    private var currentDiskIndex by mutableIntStateOf(0)
    private var diskLabels = emptyList<String>()

    private var raManager: RetroAchievementsManager? = null

    private var audioSampleRate = 0
    private var fastForwarding by mutableStateOf(false)
    private var holdingFf = false

    private fun setFastForward(enabled: Boolean) {
        fastForwarding = enabled
        renderer.fastForwardFrames = if (enabled) maxFfSpeed else 0
        audio?.muted = enabled
    }

    private enum class UndoType { SAVE, LOAD, RESET }
    private var undoType by mutableStateOf<UndoType?>(null)
    private var undoSlot: SaveSlotManager.Slot? = null
    private val undoHandler = Handler(Looper.getMainLooper())
    private val clearUndoRunnable = Runnable { clearUndo() }
    private var osdMessage by mutableStateOf<String?>(null)
    private val osdHandler = Handler(Looper.getMainLooper())
    private val clearOsdRunnable = Runnable { osdMessage = null }
    private val autoLockHandler = Handler(Looper.getMainLooper())
    private val autoLockRunnable = Runnable {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private var gameTitle: String = ""
    private var corePath: String = ""
    private var romPath: String = ""
    private var originalRomPath: String? = null
    private var sramPath: String = ""
    private var stateBasePath: String = ""
    private var systemDir: String = ""
    private var saveDir: String = ""
    private var platformTag: String = ""
    private var gameBaseName: String = ""
    private var platformName: String = ""
    private var cannoliRoot: String = ""
    private var showWifi = true
    private var showBluetooth = true
    private var showClock = true
    private var showBattery = true
    private var use24h = false
    private var autoLockMs = 300_000L

    private val currentSlot get() = slotManager.slots[selectedSlotIndex]
    private val currentScreen get() = screenStack.lastOrNull()
    private val hasDiscs get() = diskCount > 1

    private fun diskLabel(index: Int): String =
        diskLabels.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "Disc ${index + 1}"

    @Volatile private var raHasAchievements = false

    private lateinit var guideManager: GuideManager
    private var guideFiles by mutableStateOf(emptyList<GuideFile>())
    private var guidePageCount by mutableIntStateOf(0)
    private var guideScrollDir by mutableIntStateOf(0)
    private var guideScrollXDir by mutableIntStateOf(0)
    private var guidePageJump by mutableIntStateOf(0)
    private var guidePageJumpDir = 0
    private var guideScrollPos = 0
    private var guideScrollXPos = 0
    private var guideInitialScroll by mutableIntStateOf(0)
    private var guideInitialScrollX by mutableIntStateOf(0)

    private fun menuOptions() = InGameMenuOptions(hasDiscs, diskLabel(currentDiskIndex), raHasAchievements, guideFiles.isNotEmpty())

    private fun refreshDiskInfo() {
        if (!romPath.endsWith(".m3u", ignoreCase = true)) return
        diskCount = runner.getDiskCount()
        currentDiskIndex = runner.getDiskIndex()
        if (diskCount > 1) {
            diskLabels = (0 until diskCount).map { runner.getDiskLabel(it) ?: "" }
        }
    }

    data class ShaderParamItem(
        val id: String, val description: String,
        val value: Float, val min: Float, val max: Float, val step: Float
    )

    companion object {
        private val FF_SPEEDS = listOf(2, 3, 4, 6, 8)
        @Volatile var isRunning = false
    }

    private fun push(screen: IGMScreen) { screenStack.add(screen) }

    private fun pop() {
        if (screenStack.isNotEmpty()) screenStack.removeAt(screenStack.lastIndex)
    }

    private fun replaceTop(screen: IGMScreen) {
        if (screenStack.isNotEmpty()) screenStack[screenStack.lastIndex] = screen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isRunning = true
        goFullscreen()

        gameTitle = (intent.getStringExtra("game_title") ?: "").removePrefix("★ ")
        corePath = intent.getStringExtra("core_path") ?: run { finish(); return }
        romPath = intent.getStringExtra("rom_path") ?: run { finish(); return }
        originalRomPath = intent.getStringExtra("original_rom_path")
        sramPath = intent.getStringExtra("sram_path") ?: ""
        stateBasePath = intent.getStringExtra("state_path") ?: ""
        systemDir = intent.getStringExtra("system_dir") ?: ""
        saveDir = intent.getStringExtra("save_dir") ?: ""
        platformTag = intent.getStringExtra("platform_tag") ?: ""
        platformName = intent.getStringExtra("platform_name") ?: platformTag
        cannoliRoot = intent.getStringExtra("cannoli_root") ?: ""
        showWifi = intent.getBooleanExtra("show_wifi", true)
        showBluetooth = intent.getBooleanExtra("show_bluetooth", true)
        showClock = intent.getBooleanExtra("show_clock", true)
        showBattery = intent.getBooleanExtra("show_battery", true)
        use24h = intent.getBooleanExtra("use_24h", false)
        autoLockMs = intent.getLongExtra("auto_lock_ms", 300_000L)

        slotManager = SaveSlotManager(stateBasePath)
        guideManager = GuideManager(cannoliRoot, platformTag, gameTitle)
        profileManager = ProfileManager(cannoliRoot)
        profileManager.ensureDefault()
        controllerManager = ControllerManager()
        controllerManager.onDeviceDisconnected = { port -> onControllerDisconnected(port) }
        controllerManager.onDeviceConnected = { port, _ ->
            if (::runner.isInitialized) runner.setControllerPortDevice(port, LibretroRunner.DEVICE_JOYPAD)
            onControllerReconnected(port)
        }
        controllerManager.initialize()
        input = controllerManager.portInputs[0]
        runner = LibretroRunner()
        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(controllerManager, Handler(Looper.getMainLooper()))

        val colors = CannoliColors(
            highlight = hexToColor(intent.getStringExtra("color_highlight") ?: "#FFFFFF") ?: Color.White,
            text = hexToColor(intent.getStringExtra("color_text") ?: "#FFFFFF") ?: Color.White,
            highlightText = hexToColor(intent.getStringExtra("color_highlight_text") ?: "#000000") ?: Color.Black,
            accent = hexToColor(intent.getStringExtra("color_accent") ?: "#FFFFFF") ?: Color.White
        )

        setContent {
            CannoliTheme {
                CompositionLocalProvider(LocalCannoliColors provides colors) {
                    if (loading) {
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                    } else {
                        val screen = currentScreen
                        LibretroScreen(
                            glSurfaceView = gameView!!,
                            gameTitle = gameTitle,
                            screen = screen,
                            menuOptions = menuOptions(),
                            selectedSlot = currentSlot,
                            slotThumbnail = slotThumbnail,
                            slotExists = slotExists,
                            slotOccupied = slotOccupied,
                            undoLabel = when (undoType) {
                                UndoType.SAVE -> "Undo Save"
                                UndoType.LOAD -> "Undo Load"
                                UndoType.RESET -> "Undo Reset"
                                null -> null
                            },
                            settingsItems = if (screen is IGMScreen.Menu) emptyList() else buildSettingsItems(),
                            coreInfo = coreInfoText,
                            input = controllerManager.portInputs[0],
                            profileName = currentProfileName,
                            profileNames = profileNames,
                            debugHud = debugHud,
                            renderer = renderer,
                            runner = runner,
                            audioSampleRate = audioSampleRate,
                            showWifi = showWifi,
                            showBluetooth = showBluetooth,
                            showClock = showClock,
                            showBattery = showBattery,
                            use24h = use24h,
                            osdMessage = osdMessage,
                            fastForwarding = fastForwarding,
                            guideFiles = guideFiles,
                            guidePageCount = guidePageCount,
                            guideScrollDir = guideScrollDir,
                            guideScrollXDir = guideScrollXDir,
                            guidePageJump = guidePageJump,
                            guidePageJumpDir = guidePageJumpDir,
                            guideInitialScroll = guideInitialScroll,
                            guideInitialScrollX = guideInitialScrollX,
                            onGuideScrollChanged = { y, x -> guideScrollPos = y; guideScrollXPos = x },
                            gameInfo = GameInfo(
                                coreName = coreInfoText,
                                romPath = romPath,
                                savePath = sramPath.takeIf { java.io.File(it).exists() },
                                rootPrefix = cannoliRoot,
                                originalRomPath = originalRomPath,
                                rendererName = renderer.backendName,
                                raStatus = raManager?.let { ra ->
                                    if (ra.isLoggedIn) {
                                        val status = if (ra.isOnline) "Online" else "Offline"
                                        "${ra.username} ($status)"
                                    } else null
                                },
                                raGameId = raManager?.let { ra ->
                                    val id = ra.gameId
                                    if (id > 0) {
                                        val title = ra.gameTitle
                                        if (title.isNotEmpty()) "$id — $title" else "$id"
                                    } else null
                                }
                            )
                        )
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (loading) return
                if (screenStack.isEmpty()) openMenu() else pop()
                if (screenStack.isEmpty()) renderer.paused = false
            }
        })

        val resumeSlot = intent.getIntExtra("resume_slot", -1)
        Thread {
            if (!runner.loadCore(corePath)) { runOnUiThread { finish() }; return@Thread }
            runner.init(systemDir, saveDir)
            val avInfo = runner.loadGame(romPath)
            if (avInfo == null) { runner.deinit(); runOnUiThread { finish() }; return@Thread }
            if (sramPath.isNotEmpty() && File(sramPath).exists()) runner.loadSRAM(sramPath)
            if (resumeSlot >= 0) {
                val slot = slotManager.slots.getOrNull(resumeSlot)
                if (slot != null && slotManager.stateExists(slot)) {
                    slotManager.loadState(runner, slot)
                    runOnUiThread { selectedSlotIndex = resumeSlot }
                }
            }
            runOnUiThread {
                val (coreName, coreVersion) = runner.getSystemInfo()
                coreInfoText = if (coreVersion.isNotEmpty()) "$coreName $coreVersion" else coreName
                coreOptions = runner.getCoreOptions()
                coreCategories = runner.getCoreCategories()

                val coreBaseName = File(corePath).nameWithoutExtension
                gameBaseName = if (romPath.isNotEmpty()) File(romPath).nameWithoutExtension else ""
                overrideManager = OverrideManager(cannoliRoot, platformTag, gameBaseName, coreBaseName)
                loadOverrides()
                for (p in 0 until LibretroRunner.MAX_PORTS) {
                    if (controllerManager.slots[p] != null) runner.setControllerPortDevice(p, LibretroRunner.DEVICE_JOYPAD)
                }
                scanOverlayImages()
                copyBundledShaders()
                scanShaderPresets()

                audioSampleRate = avInfo.sampleRate
                audio = LibretroAudio(avInfo.sampleRate, lowLatency)
                runner.setAudioCallback(audio!!)
                audio!!.start()

                val shaderCacheDir = File(cacheDir, "shader_cache")
                ShaderPipeline.cacheDir = shaderCacheDir
                SlangTranspiler.cacheDir = shaderCacheDir

                fun configureBackend(backend: GraphicsBackend) {
                    backend.coreAspectRatio = runner.getAspectRatio()
                    backend.scalingMode = scalingMode
                    backend.sharpness = sharpness
                    backend.screenEffect = screenEffect
                    backend.debugHud = debugHud
                    backend.overlayPath = resolveOverlayPath()
                    backend.shaderPresetPath = resolveShaderPresetPath()
                    backend.lowLatency = lowLatency
                }

                val glesBackend = LibretroRenderer(runner)
                configureBackend(glesBackend)
                renderer = glesBackend

                glSurfaceView = GLSurfaceView(this).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(glesBackend)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
                gameView = glSurfaceView

                loading = false
                runOnUiThread { applyLowLatency() }

                val raUser = intent.getStringExtra("ra_username") ?: ""
                val raToken = intent.getStringExtra("ra_token") ?: ""
                val consoleId = RetroAchievementsManager.CONSOLE_MAP[platformTag]
                if (consoleId != null && raUser.isNotEmpty() && raToken.isNotEmpty()) {
                    val raGameIdOverride = intent.getIntExtra("ra_game_id", 0)
                    val ra = RetroAchievementsManager(
                        context = this,
                        cacheDir = java.io.File(cacheDir, "ra_cache"),
                        onEvent = { _, title, _, _ ->
                            raHasAchievements = true
                            showOsd("\uDB81\uDD38 $title")
                        },
                        onSyncStatus = { msg -> showOsd(msg) }
                    )
                    ra.init()
                    ra.loginWithToken(raUser, raToken)
                    if (raGameIdOverride > 0) {
                        ra.loadGameById(raGameIdOverride, consoleId)
                    } else {
                        ra.loadGame(romPath, consoleId)
                    }
                    raManager = ra
                }
            }
        }.start()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // --- Input ---

    private val axisMask = LibretroInput.RETRO_UP or LibretroInput.RETRO_DOWN or
            LibretroInput.RETRO_LEFT or LibretroInput.RETRO_RIGHT or
            LibretroInput.RETRO_L2 or LibretroInput.RETRO_R2

    private var menuHeldKey = 0
    private val menuRepeatHandler = Handler(Looper.getMainLooper())
    private val menuRepeatDelay = 400L
    private val menuRepeatInterval = 80L
    private val menuRepeatRunnable = object : Runnable {
        override fun run() {
            if (menuHeldKey != 0 && screenStack.isNotEmpty()) {
                onKeyDown(menuHeldKey, KeyEvent(KeyEvent.ACTION_DOWN, menuHeldKey))
                menuRepeatHandler.postDelayed(this, menuRepeatInterval)
            }
        }
    }

    private fun handleMenuMotion(event: android.view.MotionEvent) {
        val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(android.view.MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
        val x = if (kotlin.math.abs(hatX) > 0.5f) hatX else stickX
        val y = if (kotlin.math.abs(hatY) > 0.5f) hatY else stickY
        val key = when {
            y < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            y > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            x < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            x > 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> 0
        }
        if (key != menuHeldKey) {
            menuRepeatHandler.removeCallbacks(menuRepeatRunnable)
            menuHeldKey = key
            if (key != 0) {
                onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key))
                if (currentScreen !is IGMScreen.Guide) {
                    menuRepeatHandler.postDelayed(menuRepeatRunnable, menuRepeatDelay)
                }
            } else if (currentScreen is IGMScreen.Guide) {
                guideScrollDir = 0
                guideScrollXDir = 0
            }
        }
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (loading) return super.dispatchGenericMotionEvent(event)
        val source = event.source
        val isJoystick = source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
                source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD
        if (!isJoystick) return super.dispatchGenericMotionEvent(event)
        if (screenStack.isNotEmpty()) {
            handleMenuMotion(event)
            return true
        }

        val port = controllerManager.getPortForDeviceId(event.deviceId) ?: 0
        val portInput = controllerManager.portInputs[port]
        var axes = 0

        val hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y)
        if (hatX < -0.5f) axes = axes or (portInput.keyCodeToRetroMask(KeyEvent.KEYCODE_DPAD_LEFT) ?: LibretroInput.RETRO_LEFT)
        if (hatX > 0.5f) axes = axes or (portInput.keyCodeToRetroMask(KeyEvent.KEYCODE_DPAD_RIGHT) ?: LibretroInput.RETRO_RIGHT)
        if (hatY < -0.5f) axes = axes or (portInput.keyCodeToRetroMask(KeyEvent.KEYCODE_DPAD_UP) ?: LibretroInput.RETRO_UP)
        if (hatY > 0.5f) axes = axes or (portInput.keyCodeToRetroMask(KeyEvent.KEYCODE_DPAD_DOWN) ?: LibretroInput.RETRO_DOWN)

        val stickX = event.getAxisValue(android.view.MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(android.view.MotionEvent.AXIS_Y)
        if (stickX < -0.5f) axes = axes or (portInput.keyCodeToRetroMask(KeyEvent.KEYCODE_DPAD_LEFT) ?: LibretroInput.RETRO_LEFT)
        if (stickX > 0.5f) axes = axes or (portInput.keyCodeToRetroMask(KeyEvent.KEYCODE_DPAD_RIGHT) ?: LibretroInput.RETRO_RIGHT)
        if (stickY < -0.5f) axes = axes or (portInput.keyCodeToRetroMask(KeyEvent.KEYCODE_DPAD_UP) ?: LibretroInput.RETRO_UP)
        if (stickY > 0.5f) axes = axes or (portInput.keyCodeToRetroMask(KeyEvent.KEYCODE_DPAD_DOWN) ?: LibretroInput.RETRO_DOWN)

        if (event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER) > 0.5f) axes = axes or LibretroInput.RETRO_L2
        if (event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER) > 0.5f) axes = axes or LibretroInput.RETRO_R2

        controllerManager.portInputMasks[port] = (controllerManager.portInputMasks[port] and axisMask.inv()) or axes
        runner.setInput(port, controllerManager.portInputMasks[port])
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val source = event.source
        val isGamepad = source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
                source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK
        if (isGamepad || event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (currentScreen is IGMScreen.Guide && event.repeatCount > 0) return true
            when (event.action) {
                KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (loading) return true
        if (screenStack.isNotEmpty()) resetAutoLock()
        val screen = currentScreen ?: return handleGameplayInput(keyCode, event)
        val resolved = resolveGlobal(keyCode)
        return when (screen) {
            is IGMScreen.Menu -> handleMenuInput(screen, resolved)
            is IGMScreen.Settings -> handleCategoryInput(screen, resolved)
            is IGMScreen.Video -> handleVideoInput(screen, resolved)
            is IGMScreen.Advanced -> handleAdvancedInput(screen, resolved)
            is IGMScreen.ShaderSettings -> handleShaderSettingsInput(screen, resolved)
            is IGMScreen.Emulator -> handleEmulatorInput(screen, resolved)
            is IGMScreen.EmulatorCategory -> handleEmulatorCategoryInput(screen, resolved)
            is IGMScreen.Controls -> handleProfilePickerInput(screen, resolved)
            is IGMScreen.ControlEdit -> handleControlEditInput(screen, keyCode, resolved)
            is IGMScreen.ProfileName -> handleProfileNameInput(screen, resolved)
            is IGMScreen.Shortcuts -> handleShortcutsInput(screen, keyCode, resolved)
            is IGMScreen.SavePrompt -> handleSavePromptInput(screen, resolved)
            is IGMScreen.Info -> {
                if (resolved == KeyEvent.KEYCODE_BUTTON_B || resolved == KeyEvent.KEYCODE_BUTTON_A) { pop(); true } else true
            }
            is IGMScreen.Achievements -> handleAchievementsInput(screen, resolved)
            is IGMScreen.AchievementDetail -> handleAchievementDetailInput(screen, resolved)
            is IGMScreen.GuidePicker -> handleGuidePickerInput(screen, resolved)
            is IGMScreen.Guide -> handleGuideInput(screen, resolved)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (loading) return true
        if (screenStack.isNotEmpty()) {
            if (currentScreen is IGMScreen.Guide) {
                val resolved = resolveGlobal(keyCode)
                when (resolved) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> guideScrollDir = 0
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> guideScrollXDir = 0
                }
            }
            handleShortcutKeyUp(keyCode)
            return true
        }
        val port = controllerManager.getPortForDeviceId(event.deviceId) ?: 0
        val portKeys = controllerManager.portPressedKeys[port]
        portKeys.remove(keyCode)

        if (holdingFf) {
            val holdChord = shortcuts[ShortcutAction.HOLD_FF]
            if (holdChord != null && !portKeys.containsAll(holdChord)) {
                holdingFf = false
                setFastForward(false)
            }
        }

        val portInput = controllerManager.portInputs[port]
        val mask = portInput.keyCodeToRetroMask(keyCode) ?: return super.onKeyUp(keyCode, event)
        controllerManager.portInputMasks[port] = controllerManager.portInputMasks[port] and mask.inv()
        runner.setInput(port, controllerManager.portInputMasks[port])
        return true
    }

    private fun resolveGlobal(keyCode: Int): Int {
        for (btn in input.buttons) {
            val assigned = defaultProfileControls[btn.prefKey] ?: btn.defaultKeyCode
            if (assigned == keyCode) return btn.defaultKeyCode
        }
        return keyCode
    }

    private fun handleGameplayInput(keyCode: Int, event: KeyEvent): Boolean {
        val menuCode = defaultProfileControls["btn_menu"] ?: KeyEvent.KEYCODE_BUTTON_MODE
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU || keyCode == menuCode) { openMenu(); return true }
        val port = controllerManager.getPortForDeviceId(event.deviceId) ?: 0
        val portKeys = controllerManager.portPressedKeys[port]
        val isNewPress = portKeys.add(keyCode)
        if (isNewPress) checkShortcuts(port)
        val portInput = controllerManager.portInputs[port]
        val mask = portInput.keyCodeToRetroMask(keyCode) ?: return super.onKeyDown(keyCode, event)
        controllerManager.portInputMasks[port] = controllerManager.portInputMasks[port] or mask
        runner.setInput(port, controllerManager.portInputMasks[port])
        return true
    }

    private fun checkShortcuts(port: Int) {
        val portKeys = controllerManager.portPressedKeys[port]
        for ((action, chord) in shortcuts) {
            if (chord.isEmpty() || !portKeys.containsAll(chord)) continue
            when (action) {
                ShortcutAction.SAVE_STATE -> {
                    if (stateBasePath.isNotEmpty()) {
                        slotManager.saveState(runner, currentSlot)
                        showOsd("Saved to ${currentSlot.label}")
                    }
                }
                ShortcutAction.LOAD_STATE -> {
                    if (stateBasePath.isNotEmpty() && slotManager.stateExists(currentSlot)) {
                        slotManager.loadState(runner, currentSlot)
                        raManager?.reset()
                        showOsd("Loaded ${currentSlot.label}")
                    }
                }
                ShortcutAction.RESET_GAME -> {
                    if (stateBasePath.isNotEmpty()) {
                        slotManager.cacheForUndoLoad(runner)
                        undoType = UndoType.RESET
                        undoSlot = null
                        startUndoTimer(30_000)
                    }
                    runner.reset()
                    showOsd("Reset")
                }
                ShortcutAction.SAVE_AND_QUIT -> {
                    renderer.paused = true
                    if (stateBasePath.isNotEmpty()) slotManager.saveState(runner, slotManager.slots[0])
                    quit()
                }
                ShortcutAction.CYCLE_SCALING -> {
                    val modes = ScalingMode.entries
                    scalingMode = modes[(scalingMode.ordinal + 1) % modes.size]
                    renderer.scalingMode = scalingMode
                    showOsd("Scaling: ${scalingLabel()}")
                }
                ShortcutAction.CYCLE_EFFECT -> {
                    cycleShader(1)
                    val label = if (shaderPreset.isEmpty()) "Off" else File(shaderPreset).nameWithoutExtension
                    showOsd("Shader: $label")
                }
                ShortcutAction.TOGGLE_FF -> {
                    setFastForward(!fastForwarding)
                }
                ShortcutAction.HOLD_FF -> {
                    if (holdingFf) continue
                    holdingFf = true; setFastForward(true)
                }
                ShortcutAction.OPEN_GUIDE -> {
                    val guides = guideManager.findGuides()
                    if (guides.isNotEmpty()) {
                        renderer.paused = true
                        screenStack.clear()
                        guideFiles = guides
                        if (guides.size == 1) openGuide(guides[0])
                        else push(IGMScreen.GuidePicker())
                    }
                }
            }
            portKeys.clear()
            controllerManager.portInputMasks[port] = 0
            runner.setInput(port, 0)
            break
        }
    }

    private fun resetAutoLock() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        autoLockHandler.removeCallbacks(autoLockRunnable)
        if (screenStack.isNotEmpty() && autoLockMs > 0) {
            autoLockHandler.postDelayed(autoLockRunnable, autoLockMs)
        }
    }

    // --- Menu screen ---

    private fun openMenu() {
        if (!raHasAchievements) {
            raManager?.let { ra -> raHasAchievements = ra.isLoggedIn && ra.getAchievements().isNotEmpty() }
        }
        guideFiles = guideManager.findGuides()
        screenStack.clear()
        push(IGMScreen.Menu())
        renderer.paused = true
        refreshSlotInfo()
        refreshDiskInfo()
        resetAutoLock()
    }

    private fun closeAll() {
        screenStack.clear()
        menuRepeatHandler.removeCallbacks(menuRepeatRunnable)
        menuHeldKey = 0
        applyProfileToAllPorts(profileManager.readControls(currentProfileName))
        controllerManager.resetAllInput()
        for (p in 0 until LibretroRunner.MAX_PORTS) runner.setInput(p, 0)
        renderer.paused = false
        resetAutoLock()
    }

    private fun onControllerDisconnected(port: Int) {
        if (loading) return
        runner.setInput(port, 0)
        runner.setControllerPortDevice(port, LibretroRunner.DEVICE_NONE)
        showOsd("Player ${port + 1} Disconnected")
    }

    private fun onControllerReconnected(port: Int) {
        runner.setControllerPortDevice(port, LibretroRunner.DEVICE_JOYPAD)
        if (!loading) showOsd("Player ${port + 1} connected")
    }

    private fun refreshSlotInfo() {
        val slot = currentSlot
        slotExists = slotManager.stateExists(slot)
        slotThumbnail = slotManager.loadThumbnail(slot)
        slotOccupied = slotManager.slots.map { slotManager.stateExists(it) }
    }

    private fun cycleSlot(direction: Int) {
        val count = slotManager.slots.size
        selectedSlotIndex = ((selectedSlotIndex + direction) + count) % count
        refreshSlotInfo()
    }

    private fun cycleDisc(direction: Int) {
        val newIndex = ((currentDiskIndex + direction) + diskCount) % diskCount
        if (newIndex != currentDiskIndex && runner.setDiskIndex(newIndex)) {
            currentDiskIndex = newIndex
            showOsd("Switched to ${diskLabel(currentDiskIndex)}")
        }
    }

    private fun handleMenuInput(screen: IGMScreen.Menu, keyCode: Int): Boolean {
        if (screen.confirmDeleteSlot) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_X -> {
                    slotManager.deleteState(currentSlot)
                    refreshSlotInfo()
                    showOsd("Deleted ${currentSlot.label}")
                    replaceTop(screen.copy(confirmDeleteSlot = false))
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                    replaceTop(screen.copy(confirmDeleteSlot = false))
                    true
                }
                else -> true
            }
        }

        val menu = menuOptions()
        val options = menu.options
        val onSlotRow = screen.selectedIndex == menu.saveStateIndex || screen.selectedIndex == menu.loadStateIndex
        val onDiscRow = screen.selectedIndex == menu.switchDiscIndex
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val idx = ((screen.selectedIndex - 1) + options.size) % options.size
                replaceTop(screen.copy(selectedIndex = idx))
                if (idx == menu.saveStateIndex || idx == menu.loadStateIndex) refreshSlotInfo()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val idx = (screen.selectedIndex + 1) % options.size
                replaceTop(screen.copy(selectedIndex = idx))
                if (idx == menu.saveStateIndex || idx == menu.loadStateIndex) refreshSlotInfo()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when {
                    onSlotRow -> cycleSlot(-1)
                    onDiscRow -> cycleDisc(-1)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when {
                    onSlotRow -> cycleSlot(1)
                    onDiscRow -> cycleDisc(1)
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleMenuAction(menu, screen.selectedIndex); true
            }
            KeyEvent.KEYCODE_BUTTON_X -> { if (undoType != null) performUndo(); true }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (onSlotRow && slotExists) {
                    replaceTop(screen.copy(confirmDeleteSlot = true))
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { closeAll(); true }
            else -> true
        }
    }

    private fun handleMenuAction(menu: InGameMenuOptions, index: Int) {
        when (index) {
            menu.resumeIndex -> closeAll()
            menu.saveStateIndex -> {
                if (stateBasePath.isNotEmpty()) {
                    val slot = currentSlot
                    if (slot.index != 0) {
                        slotManager.cacheForUndoSave(slot)
                        undoType = UndoType.SAVE
                        undoSlot = slot
                        startUndoTimer()
                    }
                    slotManager.saveState(runner, slot)
                    refreshSlotInfo()
                    showOsd("Saved to ${slot.label}")
                }
                closeAll()
            }
            menu.loadStateIndex -> {
                if (stateBasePath.isNotEmpty() && slotManager.stateExists(currentSlot)) {
                    val slot = currentSlot
                    slotManager.cacheForUndoLoad(runner)
                    undoType = UndoType.LOAD
                    undoSlot = null
                    startUndoTimer()
                    slotManager.loadState(runner, slot)
                    raManager?.reset()
                    showOsd("Loaded ${slot.label}")
                }
                closeAll()
            }
            menu.guideIndex -> {
                if (guideFiles.size == 1) {
                    openGuide(guideFiles[0])
                } else {
                    push(IGMScreen.GuidePicker())
                }
            }
            menu.settingsIndex -> {
                coreOptions = runner.getCoreOptions()
                refreshShaderParams()
                frontendSnapshot = buildCurrentSettings()
                shaderParamsDirty = false
                push(IGMScreen.Settings())
            }
            menu.resetIndex -> {
                if (stateBasePath.isNotEmpty()) {
                    slotManager.cacheForUndoLoad(runner)
                    undoType = UndoType.RESET
                    undoSlot = null
                    startUndoTimer(30_000)
                }
                runner.reset()
                closeAll()
            }
            menu.achievementsIndex -> {
                val ra = raManager ?: return
                val pending = ra.pendingSyncIds
                val local = ra.localUnlocks
                val achievements = ra.getAchievements().map {
                    when {
                        it.id in pending -> it.copy(unlocked = true, pendingSync = true)
                        it.id in local -> it.copy(unlocked = true)
                        else -> it
                    }
                }
                push(IGMScreen.Achievements(achievements = achievements, status = ra.getStatus()))
            }
            menu.quitIndex -> quit()
        }
    }

    // --- Settings category screen ---

    private fun handleCategoryInput(screen: IGMScreen.Settings, keyCode: Int): Boolean {
        val count = IGMSettings.CATEGORIES.size
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (screen.selectedIndex) {
                    IGMSettings.VIDEO -> push(IGMScreen.Video())
                    IGMSettings.EMULATOR -> {
                        coreOptions = runner.getCoreOptions()
                        coreCategories = runner.getCoreCategories()
                        push(IGMScreen.Emulator())
                    }
                    IGMSettings.CONTROLS -> {
                        push(IGMScreen.Controls(selectedIndex = profileNames.indexOf(currentProfileName).coerceAtLeast(0)))
                    }
                    IGMSettings.SHORTCUTS -> push(IGMScreen.Shortcuts())
                    IGMSettings.ADVANCED -> push(IGMScreen.Advanced())
                    IGMSettings.INFO -> push(IGMScreen.Info())
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                val snap = frontendSnapshot
                if (snap != null && (shaderParamsDirty || !buildCurrentSettings().frontendEquals(snap))) {
                    push(IGMScreen.SavePrompt())
                } else {
                    pop()
                }
                true
            }
            else -> true
        }
    }

    // --- Frontend ---

    private fun scalingLabel() = when (scalingMode) {
        ScalingMode.CORE_REPORTED -> "Core Reported"
        ScalingMode.INTEGER -> "Integer"
        ScalingMode.FULLSCREEN -> "Fullscreen"
    }

    private fun sharpnessLabel() = when (sharpness) {
        Sharpness.SHARP -> "Sharp"
        Sharpness.CRISP -> "Crisp"
        Sharpness.SOFT -> "Soft"
    }

    private fun overlayLabel() = if (overlay.isEmpty()) "None" else File(overlay).nameWithoutExtension

    private fun resolveOverlayPath(): String? =
        if (overlay.isEmpty()) null else File(cannoliRoot, "Overlays/$platformTag/$overlay").absolutePath

    private fun scanOverlayImages() {
        val dir = File(cannoliRoot, "Overlays/$platformTag")
        val exts = setOf("png", "jpg", "jpeg")
        overlayImages = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in exts }
            ?.sortedBy { it.name }
            ?.map { it.name }
            ?: emptyList()
    }

    private fun copyBundledShaders() {
        val destDir = File(cannoliRoot, "Shaders")
        copyAssetDir("shaders", destDir)
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val entries = try { assets.list(assetPath) } catch (_: Exception) { return }
        if (entries.isNullOrEmpty()) return
        for (entry in entries) {
            val src = "$assetPath/$entry"
            val dest = File(destDir, entry)
            val subEntries = try { assets.list(src) } catch (_: Exception) { null }
            if (!subEntries.isNullOrEmpty()) {
                copyAssetDir(src, dest)
            } else {
                dest.parentFile?.mkdirs()
                assets.open(src).use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun scanShaderPresets() {
        val dir = File(cannoliRoot, "Shaders")
        val exts = setOf("glslp", "slangp")
        shaderPresets = dir.walk()
            .filter { it.isFile && it.extension.lowercase(java.util.Locale.ROOT) in exts }
            .map { it.relativeTo(dir).path }
            .sorted()
            .toList()
    }

    private fun resolveShaderPresetPath(): String? =
        if (shaderPreset.isEmpty()) null
        else File(cannoliRoot, "Shaders/$shaderPreset").absolutePath

    private fun refreshShaderParams() {
        val path = resolveShaderPresetPath()
        if (path.isNullOrEmpty()) { shaderParams = emptyList(); return }
        val preset = PresetParser.parse(File(path))
        if (preset == null) { shaderParams = emptyList(); return }
        shaderParams = preset.parameters.values.map { p ->
            ShaderParamItem(p.id, p.description, p.default, p.min, p.max, p.step)
        }
    }

    private fun cycleOverlay(direction: Int) {
        if (overlayImages.isEmpty()) { overlay = ""; return }
        val currentIndex = overlayImages.indexOf(overlay)
        val newIndex = if (currentIndex == -1) {
            if (direction > 0) 0 else overlayImages.lastIndex
        } else {
            val raw = currentIndex + direction
            if (raw < 0 || raw >= overlayImages.size) -1 else raw
        }
        overlay = if (newIndex < 0) "" else overlayImages[newIndex]
        renderer.overlayPath = resolveOverlayPath()
    }

    private fun handleVideoInput(screen: IGMScreen.Video, keyCode: Int): Boolean {
        val hasParams = shaderParams.isNotEmpty()
        val count = if (hasParams) 5 else 4
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                cycleVideoValue(screen.selectedIndex, dir, hasParams)
                true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val shaderSettingsIdx = if (hasParams) 3 else -1
                if (screen.selectedIndex == shaderSettingsIdx) push(IGMScreen.ShaderSettings())
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun cycleVideoValue(index: Int, direction: Int, hasParams: Boolean) {
        when (index) {
            0 -> {
                val modes = ScalingMode.entries
                scalingMode = modes[(scalingMode.ordinal + direction + modes.size) % modes.size]
                renderer.scalingMode = scalingMode
            }
            1 -> {
                val vals = Sharpness.entries
                sharpness = vals[(sharpness.ordinal + direction + vals.size) % vals.size]
                renderer.sharpness = sharpness
                if (sharpness == Sharpness.CRISP) {
                    scalingMode = ScalingMode.INTEGER
                    renderer.scalingMode = scalingMode
                }
            }
            2 -> cycleShader(direction)
            3 -> if (!hasParams) cycleOverlay(direction)
            4 -> cycleOverlay(direction)
        }
    }

    private fun handleAdvancedInput(screen: IGMScreen.Advanced, keyCode: Int): Boolean {
        val count = 3
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                cycleAdvancedValue(screen.selectedIndex, dir)
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun cycleAdvancedValue(index: Int, direction: Int) {
        when (index) {
            0 -> { lowLatency = !lowLatency; renderer.lowLatency = lowLatency; applyLowLatency() }
            1 -> { cycleFfSpeed(direction) }
            2 -> { debugHud = !debugHud; renderer.debugHud = debugHud }
        }
    }

    private fun applyLowLatency() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setPreferMinimalPostProcessing(lowLatency)
        }
        audio?.setLowLatency(lowLatency)
    }

    private fun cycleShader(direction: Int) {
        if (shaderPresets.isEmpty()) {
            screenEffect = ScreenEffect.NONE
            shaderPreset = ""
        } else {
            val currentIndex = if (screenEffect == ScreenEffect.NONE || shaderPreset.isEmpty()) -1
                else shaderPresets.indexOf(shaderPreset)
            val total = shaderPresets.size + 1
            val newIndex = ((currentIndex + 1 + direction) % total + total) % total - 1
            if (newIndex in 0 until shaderPresets.size) {
                screenEffect = ScreenEffect.SHADER
                shaderPreset = shaderPresets[newIndex]
            } else {
                screenEffect = ScreenEffect.NONE
                shaderPreset = ""
            }
        }
        shaderParamsDirty = true
        renderer.clearShaderParamOverrides()
        renderer.screenEffect = screenEffect
        renderer.shaderPresetPath = resolveShaderPresetPath()
        refreshShaderParams()
    }

    private fun filteredAchievements(screen: IGMScreen.Achievements): List<RetroAchievementsManager.Achievement> = when (screen.filter) {
        1 -> screen.achievements.filter { it.unlocked }
        else -> screen.achievements
    }

    private fun handleAchievementsInput(screen: IGMScreen.Achievements, keyCode: Int): Boolean {
        val filtered = filteredAchievements(screen)
        val count = filtered.size
        if (count == 0 && keyCode != KeyEvent.KEYCODE_BUTTON_Y) return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_BUTTON_A -> {
                var ach = filtered.getOrNull(screen.selectedIndex)
                if (ach != null) {
                    val ra = raManager
                    if (ra != null) {
                        if (ach.id in ra.pendingSyncIds) ach = ach.copy(unlocked = true, pendingSync = true)
                        else if (ach.id in ra.localUnlocks) ach = ach.copy(unlocked = true)
                    }
                    push(IGMScreen.AchievementDetail(achievement = ach, parentIndex = screen.selectedIndex))
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                val newFilter = (screen.filter + 1) % 2
                replaceTop(screen.copy(filter = newFilter, selectedIndex = 0))
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private val detailHeldKeys = mutableSetOf<Int>()

    private fun handleAchievementDetailInput(screen: IGMScreen.AchievementDetail, keyCode: Int): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
            detailHeldKeys.add(keyCode)
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (!screen.achievement.unlocked && detailHeldKeys.contains(KeyEvent.KEYCODE_BUTTON_L1) && detailHeldKeys.contains(KeyEvent.KEYCODE_BUTTON_R1)) {
                    raManager?.manualUnlock(screen.achievement.id)
                    showOsd("Unlocked: ${screen.achievement.title}")
                    val unlockedAch = screen.achievement.copy(unlocked = true)
                    detailHeldKeys.clear()
                    pop()
                    val top = currentScreen
                    if (top is IGMScreen.Achievements) {
                        val updated = top.achievements.map {
                            if (it.id == unlockedAch.id) unlockedAch else it
                        }
                        replaceTop(top.copy(achievements = updated))
                    }
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { detailHeldKeys.clear(); pop(); true }
            else -> true
        }
    }

    private fun openGuide(guide: GuideFile) {
        val saved = guideManager.loadSavedPosition(guide.file)
        guideScrollDir = 0
        guideScrollXDir = 0
        guidePageJump = 0
        guideScrollXPos = saved.scrollX
        guideInitialScrollX = saved.scrollX
        guidePageCount = if (guide.type == GuideType.PDF) {
            try {
                val pfd = android.os.ParcelFileDescriptor.open(guide.file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                pfd.use { android.graphics.pdf.PdfRenderer(it).use { r -> r.pageCount } }
            } catch (_: Exception) { 1 }
        } else 0
        guideScrollPos = if (guide.type == GuideType.PDF) saved.scrollY else saved.position
        guideInitialScroll = guideScrollPos
        if (guide.type == GuideType.PDF) {
            push(IGMScreen.Guide(filePath = guide.file.absolutePath, page = saved.position.coerceIn(0, (guidePageCount - 1).coerceAtLeast(0)), textZoom = saved.zoom))
        } else {
            push(IGMScreen.Guide(filePath = guide.file.absolutePath, textZoom = saved.zoom))
        }
    }

    private fun handleGuidePickerInput(screen: IGMScreen.GuidePicker, keyCode: Int): Boolean {
        val count = guideFiles.size
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                guideFiles.getOrNull(screen.selectedIndex)?.let { openGuide(it) }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                pop()
                if (screenStack.isEmpty()) closeAll()
                true
            }
            else -> true
        }
    }

    private fun handleGuideInput(screen: IGMScreen.Guide, keyCode: Int): Boolean {
        val guide = guideFiles.firstOrNull { it.file.absolutePath == screen.filePath }
        val type = guide?.type ?: return true
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { guideScrollDir = -1; true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { guideScrollDir = 1; true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (type != GuideType.TXT && screen.textZoom > 1) guideScrollXDir = -1
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (type != GuideType.TXT && screen.textZoom > 1) guideScrollXDir = 1
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (type == GuideType.PDF) {
                    replaceTop(screen.copy(page = (screen.page - 1).coerceAtLeast(0)))
                } else {
                    guidePageJumpDir = -1; guidePageJump++
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (type == GuideType.PDF) {
                    replaceTop(screen.copy(page = (screen.page + 1).coerceAtMost(guidePageCount - 1)))
                } else {
                    guidePageJumpDir = 1; guidePageJump++
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                guideInitialScroll = guideScrollPos
                guideInitialScrollX = guideScrollXPos
                replaceTop(screen.copy(textZoom = if (screen.textZoom >= 3) 1 else screen.textZoom + 1))
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                val pos = if (type == GuideType.PDF) screen.page else guideScrollPos
                guideManager.save(guide.file, pos, guideScrollPos, guideScrollXPos, screen.textZoom)
                guideScrollDir = 0
                guideScrollXDir = 0
                pop()
                if (screenStack.isEmpty()) closeAll()
                true
            }
            else -> true
        }
    }

    private fun handleShaderSettingsInput(screen: IGMScreen.ShaderSettings, keyCode: Int): Boolean {
        val count = shaderParams.size
        if (count == 0) return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { cycleShaderParam(screen.selectedIndex, -1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleShaderParam(screen.selectedIndex, 1); true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun cycleShaderParam(index: Int, direction: Int) {
        shaderParamsDirty = true
        val param = shaderParams.getOrNull(index) ?: return
        val newValue = cycleFloat(param.value, direction, param.min, param.max, param.step)
        shaderParams = shaderParams.toMutableList().also {
            it[index] = param.copy(value = newValue)
        }
        renderer.setShaderParameter(param.id, newValue)
    }

    private fun cycleFloat(current: Float, direction: Int, min: Float, max: Float, step: Float): Float {
        val next = current + direction * step
        return (Math.round(next / step) * step).coerceIn(min, max)
    }

    private fun cycleFfSpeed(direction: Int) {
        val idx = FF_SPEEDS.indexOf(maxFfSpeed).coerceAtLeast(0)
        maxFfSpeed = FF_SPEEDS[(idx + direction + FF_SPEEDS.size) % FF_SPEEDS.size]
        if (fastForwarding) renderer.fastForwardFrames = maxFfSpeed
    }

    // --- Emulator ---

    private fun emulatorMenuItems(): List<String> {
        if (coreOptions.isEmpty()) return listOf("No options available")
        val usedCategories = coreCategories.filter { cat -> coreOptions.any { it.category == cat.key } }
        if (usedCategories.isEmpty()) return emptyList()
        val items = usedCategories.map { it.desc }.toMutableList()
        val uncategorized = coreOptions.filter { it.category.isEmpty() }
        if (uncategorized.isNotEmpty()) items.add("Other")
        return items
    }

    private fun emulatorHasCategories(): Boolean =
        coreCategories.isNotEmpty() && coreOptions.any { it.category.isNotEmpty() }

    private fun handleEmulatorInput(screen: IGMScreen.Emulator, keyCode: Int): Boolean {
        if (screen.showDescription) {
            return if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER) {
                replaceTop(screen.copy(showDescription = false)); true
            } else true
        }
        if (coreOptions.isEmpty()) {
            return if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) { pop(); true } else true
        }
        if (emulatorHasCategories()) {
            val items = emulatorMenuItems()
            val count = items.size
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
                }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val usedCategories = coreCategories.filter { cat -> coreOptions.any { it.category == cat.key } }
                    val cat = usedCategories.getOrNull(screen.selectedIndex)
                    push(IGMScreen.EmulatorCategory(categoryKey = cat?.key ?: "", categoryTitle = cat?.desc ?: ""))
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
                else -> true
            }
        }
        val count = coreOptions.size
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { cycleEmulatorValue(coreOptions, screen.selectedIndex, -1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleEmulatorValue(coreOptions, screen.selectedIndex, 1); true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val info = coreOptions.getOrNull(screen.selectedIndex)?.info
                if (!info.isNullOrEmpty()) replaceTop(screen.copy(showDescription = true))
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun handleEmulatorCategoryInput(screen: IGMScreen.EmulatorCategory, keyCode: Int): Boolean {
        if (screen.showDescription) {
            return if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER) {
                replaceTop(screen.copy(showDescription = false)); true
            } else true
        }
        val filtered = coreOptions.filter { it.category == screen.categoryKey }
        if (filtered.isEmpty()) {
            return if (keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK) { pop(); true } else true
        }
        val count = filtered.size
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { cycleEmulatorValue(filtered, screen.selectedIndex, -1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleEmulatorValue(filtered, screen.selectedIndex, 1); true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val info = filtered.getOrNull(screen.selectedIndex)?.info
                if (!info.isNullOrEmpty()) replaceTop(screen.copy(showDescription = true))
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun cycleEmulatorValue(options: List<LibretroRunner.CoreOption>, index: Int, direction: Int) {
        val opt = options.getOrNull(index) ?: return
        if (opt.values.isEmpty()) return
        val curIdx = opt.values.indexOfFirst { it.value == opt.selected }.coerceAtLeast(0)
        val newVal = opt.values[(curIdx + direction + opt.values.size) % opt.values.size]
        runner.setCoreOption(opt.key, newVal.value)
        coreOptions = runner.getCoreOptions()
    }

    // --- Controls ---

    private val controlListenTimeoutMs = 3000
    private val controlListenTickMs = 100L

    private val controlListenRunnable = object : Runnable {
        override fun run() {
            val screen = currentScreen as? IGMScreen.ControlEdit ?: return
            if (screen.listeningIndex < 0) return
            val newMs = screen.listenCountdownMs + controlListenTickMs.toInt()
            if (newMs >= controlListenTimeoutMs) {
                replaceTop(screen.copy(listeningIndex = -1, listenCountdownMs = 0))
            } else {
                replaceTop(screen.copy(listenCountdownMs = newMs))
                shortcutCountdownHandler.postDelayed(this, controlListenTickMs)
            }
        }
    }

    private fun handleProfilePickerInput(screen: IGMScreen.Controls, keyCode: Int): Boolean {
        if (screen.menuOpen) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { replaceTop(screen.copy(menuIndex = if (screen.menuIndex <= 0) 1 else 0)); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { replaceTop(screen.copy(menuIndex = if (screen.menuIndex >= 1) 0 else 1)); true }
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val name = profileNames.getOrNull(screen.selectedIndex) ?: return true
                    when (screen.menuIndex) {
                        0 -> {
                            replaceTop(screen.copy(menuOpen = false))
                            push(IGMScreen.ProfileName(name = name, cursorPos = name.length, isNew = false, originalName = name))
                        }
                        1 -> {
                            profileManager.deleteProfile(name)
                            profileNames = profileManager.listProfiles()
                            if (currentProfileName == name) {
                                currentProfileName = ProfileManager.DEFAULT
                                profileManager.saveProfileSelection(platformTag, gameBaseName, currentProfileName)
                            }
                            replaceTop(IGMScreen.Controls(selectedIndex = screen.selectedIndex.coerceAtMost(profileNames.lastIndex)))
                        }
                    }
                    true
                }
                else -> { replaceTop(screen.copy(menuOpen = false)); true }
            }
        }
        val count = profileNames.size
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (count > 0) replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (count > 0) replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val name = profileNames.getOrNull(screen.selectedIndex) ?: return true
                currentProfileName = name
                profileManager.saveProfileSelection(platformTag, gameBaseName, name)
                replaceTop(screen)
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                val name = profileNames.getOrNull(screen.selectedIndex) ?: return true
                currentProfileName = name
                profileManager.saveProfileSelection(platformTag, gameBaseName, name)
                applyProfileToAllPorts(profileManager.readControls(name))
                val inp = controllerManager.portInputs[0]
                controlsSnapshot = inp.buttons.associate { it.prefKey to inp.getKeyCodeFor(it) }
                push(IGMScreen.ControlEdit())
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                push(IGMScreen.ProfileName(isNew = true))
                true
            }
            KeyEvent.KEYCODE_BUTTON_START -> {
                val name = profileNames.getOrNull(screen.selectedIndex)
                if (name != null && name != ProfileManager.DEFAULT) {
                    replaceTop(screen.copy(menuOpen = true, menuIndex = 0))
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun handleControlEditInput(screen: IGMScreen.ControlEdit, rawKeyCode: Int, keyCode: Int): Boolean {
        val inp = controllerManager.portInputs[0]
        if (screen.listeningIndex >= 0) {
            shortcutCountdownHandler.removeCallbacks(controlListenRunnable)
            inp.assign(inp.buttons[screen.listeningIndex], rawKeyCode)
            saveCurrentProfile()
            replaceTop(screen.copy(listeningIndex = -1, listenCountdownMs = 0, dirty = true))
            return true
        }
        val count = inp.buttons.size
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                replaceTop(screen.copy(listeningIndex = screen.selectedIndex, listenCountdownMs = 0))
                shortcutCountdownHandler.postDelayed(controlListenRunnable, controlListenTickMs)
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (screen.dirty) {
                    inp.resetDefaults()
                    for ((key, kc) in controlsSnapshot) {
                        val btn = inp.buttons.find { it.prefKey == key } ?: continue
                        inp.assign(btn, kc)
                    }
                    saveCurrentProfile()
                    replaceTop(IGMScreen.ControlEdit(selectedIndex = screen.selectedIndex))
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun handleProfileNameInput(screen: IGMScreen.ProfileName, keyCode: Int): Boolean {
        val rows = dev.cannoli.scorza.ui.components.getKeyboardRows(screen.caps, screen.symbols)
        val maxRow = rows.lastIndex
        val maxCol = rows[screen.keyRow.coerceIn(0, maxRow)].lastIndex
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val newRow = if (screen.keyRow <= 0) maxRow else screen.keyRow - 1
                replaceTop(screen.copy(keyRow = newRow, keyCol = screen.keyCol.coerceAtMost(rows[newRow].lastIndex))); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                val newRow = if (screen.keyRow >= maxRow) 0 else screen.keyRow + 1
                replaceTop(screen.copy(keyRow = newRow, keyCol = screen.keyCol.coerceAtMost(rows[newRow].lastIndex))); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                replaceTop(screen.copy(keyCol = if (screen.keyCol <= 0) maxCol else screen.keyCol - 1)); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                replaceTop(screen.copy(keyCol = if (screen.keyCol >= maxCol) 0 else screen.keyCol + 1)); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                dev.cannoli.scorza.ui.components.handleKeyboardConfirm(
                    screen.caps, screen.symbols, screen.keyRow, screen.keyCol,
                    screen.name, screen.cursorPos,
                    onChar = { newName, newPos -> replaceTop(screen.copy(name = newName, cursorPos = newPos)) },
                    onShift = { replaceTop(screen.copy(caps = !screen.caps)) },
                    onSymbols = { replaceTop(screen.copy(symbols = !screen.symbols)) },
                    onEnter = { finishProfileName(screen) }
                )
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (screen.cursorPos > 0) replaceTop(screen.copy(cursorPos = screen.cursorPos - 1)); true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (screen.cursorPos < screen.name.length) replaceTop(screen.copy(cursorPos = screen.cursorPos + 1)); true
            }
            KeyEvent.KEYCODE_BUTTON_START -> { finishProfileName(screen); true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun finishProfileName(screen: IGMScreen.ProfileName) {
        val name = screen.name.trim()
        if (name.isBlank() || name.equals(ProfileManager.DEFAULT, ignoreCase = true)) { pop(); return }
        if (screen.isNew) {
            val currentControls = mutableMapOf<String, Int>()
            val inp = controllerManager.portInputs[0]
            for (btn in inp.buttons) currentControls[btn.prefKey] = inp.getKeyCodeFor(btn)
            if (!profileManager.createProfile(name, currentControls)) { pop(); return }
        } else {
            val file = java.io.File(cannoliRoot, "Config/Profiles/${screen.originalName}.ini")
            val dest = java.io.File(cannoliRoot, "Config/Profiles/$name.ini")
            if (dest.exists() && name != screen.originalName) { pop(); return }
            file.renameTo(dest)
            if (currentProfileName == screen.originalName) currentProfileName = name
        }
        profileNames = profileManager.listProfiles()
        pop()
        val controlsScreen = screenStack.lastOrNull() as? IGMScreen.Controls
        if (controlsScreen != null) {
            replaceTop(controlsScreen.copy(selectedIndex = profileNames.indexOf(name).coerceAtLeast(0)))
        }
    }

    private fun saveCurrentProfile() {
        val inp = controllerManager.portInputs[0]
        val controlMap = mutableMapOf<String, Int>()
        for (btn in inp.buttons) controlMap[btn.prefKey] = inp.getKeyCodeFor(btn)
        profileManager.saveControls(currentProfileName, controlMap)
        applyProfileToAllPorts(controlMap)
    }

    private fun applyProfileToAllPorts(controls: Map<String, Int>) {
        for (p in 0 until LibretroRunner.MAX_PORTS) {
            val portInput = controllerManager.portInputs[p]
            portInput.resetDefaults()
            for ((key, keyCode) in controls) {
                val btn = portInput.buttons.find { it.prefKey == key } ?: continue
                portInput.assign(btn, keyCode)
            }
        }
    }


    // --- Shortcuts ---

    private val shortcutCountdownHandler = Handler(Looper.getMainLooper())
    private val shortcutHoldMs = 1500
    private val shortcutTickMs = 100L

    private val shortcutCountdownRunnable = object : Runnable {
        override fun run() {
            val screen = currentScreen as? IGMScreen.Shortcuts ?: return
            if (!screen.listening) return
            val newMs = screen.countdownMs + shortcutTickMs.toInt()
            if (newMs >= shortcutHoldMs) {
                val action = ShortcutAction.entries[screen.selectedIndex - 1]
                shortcuts = shortcuts + (action to screen.heldKeys)
                saveCurrentShortcuts()
                replaceTop(screen.copy(listening = false, heldKeys = emptySet(), countdownMs = 0))
            } else {
                replaceTop(screen.copy(countdownMs = newMs))
                shortcutCountdownHandler.postDelayed(this, shortcutTickMs)
            }
        }
    }

    private fun cancelShortcutListening() {
        shortcutCountdownHandler.removeCallbacks(shortcutCountdownRunnable)
        val screen = currentScreen as? IGMScreen.Shortcuts ?: return
        if (screen.listening) replaceTop(screen.copy(listening = false, heldKeys = emptySet(), countdownMs = 0))
    }

    private fun handleShortcutKeyUp(keyCode: Int) {
        val screen = currentScreen as? IGMScreen.Shortcuts ?: return
        if (screen.listening && screen.heldKeys.contains(keyCode)) cancelShortcutListening()
    }

    private fun handleShortcutsInput(screen: IGMScreen.Shortcuts, rawKeyCode: Int, keyCode: Int): Boolean {
        if (screen.listening) {
            if (screen.heldKeys.contains(rawKeyCode)) return true
            val newKeys = screen.heldKeys + rawKeyCode
            replaceTop(screen.copy(heldKeys = newKeys, countdownMs = 0))
            shortcutCountdownHandler.removeCallbacks(shortcutCountdownRunnable)
            shortcutCountdownHandler.postDelayed(shortcutCountdownRunnable, shortcutTickMs)
            return true
        }
        val count = ShortcutAction.entries.size + 1
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (screen.selectedIndex == 0) cycleShortcutSource(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (screen.selectedIndex == 0) cycleShortcutSource(1)
                true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (screen.selectedIndex == 0) {
                    cycleShortcutSource(1)
                } else {
                    replaceTop(screen.copy(listening = true, heldKeys = emptySet(), countdownMs = 0))
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (screen.selectedIndex > 0) {
                    val action = ShortcutAction.entries[screen.selectedIndex - 1]
                    shortcuts = shortcuts + (action to emptySet())
                    saveCurrentShortcuts()
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun cycleShortcutSource(direction: Int) {
        val sources = OverrideSource.entries
        shortcutSource = sources[(shortcutSource.ordinal + direction + sources.size) % sources.size]
        overrideManager.saveShortcutSource(shortcutSource)
        shortcuts = overrideManager.loadShortcutsForSource(shortcutSource)
    }

    private fun saveCurrentShortcuts() {
        overrideManager.saveShortcuts(shortcutSource, shortcuts)
    }

    // --- Save Prompt ---

    private fun handleSavePromptInput(screen: IGMScreen.SavePrompt, keyCode: Int): Boolean {
        val count = 3
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (screen.selectedIndex) {
                    0 -> { saveToPlatform(); showOsd("Saved for $platformName") }
                    1 -> { saveToGame(); showOsd("Saved for this game") }
                }
                frontendSnapshot = null
                shaderParamsDirty = false
                pop(); pop()
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                frontendSnapshot = null
                shaderParamsDirty = false
                pop(); pop()
                true
            }
            else -> true
        }
    }

    // --- Settings item builders ---

    private fun buildSettingsItems(): List<IGMSettingsItem> = when (val screen = currentScreen) {
        is IGMScreen.Settings -> IGMSettings.CATEGORIES.map { IGMSettingsItem(it) }
        is IGMScreen.Video -> buildList {
            add(IGMSettingsItem("Screen Scaling", scalingLabel()))
            add(IGMSettingsItem("Screen Sharpness", sharpnessLabel()))
            val shaderLabel = if (screenEffect == ScreenEffect.NONE || shaderPreset.isEmpty()) "Off"
                else File(shaderPreset).nameWithoutExtension
            add(IGMSettingsItem("Shader", shaderLabel))
            if (shaderParams.isNotEmpty()) add(IGMSettingsItem("Shader Settings"))
            add(IGMSettingsItem("Overlay", overlayLabel()))
        }
        is IGMScreen.Advanced -> listOf(
            IGMSettingsItem("Low Latency", if (lowLatency) "On" else "Off"),
            IGMSettingsItem("Max FF Speed", "${maxFfSpeed}x"),
            IGMSettingsItem("Debug HUD", if (debugHud) "On" else "Off")
        )
        is IGMScreen.ShaderSettings -> {
            if (shaderParams.isEmpty()) listOf(IGMSettingsItem("No parameters"))
            else shaderParams.map { p ->
                IGMSettingsItem(p.description, "%.2f".format(p.value))
            }
        }
        is IGMScreen.Emulator -> {
            if (emulatorHasCategories()) {
                val usedCategories = coreCategories.filter { cat -> coreOptions.any { it.category == cat.key } }
                val items = usedCategories.map { IGMSettingsItem(it.desc, hint = it.info.ifEmpty { null }) }.toMutableList()
                val uncategorized = coreOptions.filter { it.category.isEmpty() }
                if (uncategorized.isNotEmpty()) items.add(IGMSettingsItem("Other"))
                items
            } else if (coreOptions.isEmpty()) {
                listOf(IGMSettingsItem("No options available"))
            } else {
                coreOptions.map { opt ->
                    val label = opt.values.find { it.value == opt.selected }?.label ?: opt.selected
                    IGMSettingsItem(opt.desc, label, hint = opt.info.ifEmpty { null })
                }
            }
        }
        is IGMScreen.EmulatorCategory -> {
            val filtered = coreOptions.filter { it.category == screen.categoryKey }
            filtered.map { opt ->
                val label = opt.values.find { it.value == opt.selected }?.label ?: opt.selected
                IGMSettingsItem(opt.desc, label, hint = opt.info.ifEmpty { null })
            }
        }
        is IGMScreen.Shortcuts -> buildList {
            add(IGMSettingsItem("Source", sourceLabel(shortcutSource)))
            for (action in ShortcutAction.entries) {
                val chord = shortcuts[action]
                val label = if (chord.isNullOrEmpty()) "None"
                else chord.joinToString(" + ") { LibretroInput.keyCodeName(it) }
                add(IGMSettingsItem(action.label, label))
            }
        }
        is IGMScreen.SavePrompt -> listOf(
            IGMSettingsItem("Save for $platformName"),
            IGMSettingsItem("Save for this game"),
            IGMSettingsItem("Discard")
        )
        else -> emptyList()
    }

    // --- Settings persistence ---

    private fun buildCurrentSettings(): OverrideManager.Settings {
        val optionMap = mutableMapOf<String, String>()
        for (opt in coreOptions) optionMap[opt.key] = opt.selected

        return OverrideManager.Settings(
            scalingMode = scalingMode,
            screenEffect = screenEffect,
            sharpness = sharpness,
            debugHud = debugHud,
            maxFfSpeed = maxFfSpeed,
            lowLatency = lowLatency,
            shaderPreset = shaderPreset,
            overlay = overlay,
            coreOptions = optionMap
        )
    }

    private fun saveToPlatform() {
        val settings = buildCurrentSettings()
        overrideManager.savePlatform(settings)
        platformBaseline = overrideManager.loadPlatformBaseline()
    }

    private fun saveToGame() {
        val settings = buildCurrentSettings()
        val baseline = platformBaseline ?: overrideManager.loadPlatformBaseline()
        overrideManager.saveGameDelta(settings, baseline)
    }

    private fun sourceLabel(source: OverrideSource): String = when (source) {
        OverrideSource.GLOBAL -> "Global"
        OverrideSource.PLATFORM -> platformName
        OverrideSource.GAME -> "This Game"
    }

    private fun loadOverrides() {
        val settings = overrideManager.load(profileManager)
        scalingMode = settings.scalingMode
        screenEffect = settings.screenEffect
        sharpness = settings.sharpness
        debugHud = settings.debugHud
        maxFfSpeed = settings.maxFfSpeed
        lowLatency = settings.lowLatency
        shaderPreset = settings.shaderPreset
        overlay = settings.overlay
        currentProfileName = settings.profileName
        profileNames = profileManager.listProfiles()
        shortcutSource = settings.shortcutSource
        shortcuts = settings.shortcuts

        applyProfileToAllPorts(settings.controls)
        defaultProfileControls = profileManager.readControls(ProfileManager.DEFAULT)

        for ((key, value) in settings.coreOptions) {
            runner.setCoreOption(key, value)
        }
        coreOptions = runner.getCoreOptions()
        platformBaseline = overrideManager.loadPlatformBaseline()
    }

    // --- OSD / Undo ---

    private fun showOsd(message: String) {
        osdHandler.removeCallbacks(clearOsdRunnable)
        osdMessage = message
        osdHandler.postDelayed(clearOsdRunnable, 3000)
    }

    private fun startUndoTimer(durationMs: Long = 60_000) {
        undoHandler.removeCallbacks(clearUndoRunnable)
        undoHandler.postDelayed(clearUndoRunnable, durationMs)
    }

    private fun performUndo() {
        val type = undoType ?: return
        val label = when (type) {
            UndoType.SAVE -> "Undo Save"
            UndoType.LOAD -> "Undo Load"
            UndoType.RESET -> "Undo Reset"
        }
        when (type) {
            UndoType.SAVE -> undoSlot?.let { slotManager.performUndoSave(it) }
            UndoType.LOAD, UndoType.RESET -> slotManager.performUndoLoad(runner)
        }
        clearUndo()
        refreshSlotInfo()
        showOsd(label)
        closeAll()
    }

    private fun clearUndo() {
        undoType = null
        undoSlot = null
        undoHandler.removeCallbacks(clearUndoRunnable)
        slotManager.clearUndoCache()
    }

    // --- Lifecycle ---

    private fun cleanup() {
        if (cleaned || loading) return
        cleaned = true
        raManager?.unloadGame()
        raManager?.destroy()
        raManager = null
        if (sramPath.isNotEmpty()) { File(sramPath).parentFile?.mkdirs(); runner.saveSRAM(sramPath) }
        audio?.stop()
        runner.unloadGame()
        runner.deinit()
        File(cacheDir, "rom_cache").deleteRecursively()
    }

    private fun quit() { isRunning = false; cleanup(); finish() }

    override fun onPause() {
        super.onPause()
        CannoliAccessibilityService.onHomeKey = null
        CannoliAccessibilityService.onMenuKey = null
        glSurfaceView?.onPause()
        if (!loading && !cleaned && sramPath.isNotEmpty()) { File(sramPath).parentFile?.mkdirs(); runner.saveSRAM(sramPath) }
    }

    override fun onResume() {
        super.onResume(); glSurfaceView?.onResume(); goFullscreen()
        CannoliAccessibilityService.onHomeKey = { runOnUiThread { openMenu() } }
        CannoliAccessibilityService.onMenuKey = { action ->
            runOnUiThread {
                val event = KeyEvent(action, KeyEvent.KEYCODE_BUTTON_SELECT)
                if (action == KeyEvent.ACTION_DOWN) onKeyDown(KeyEvent.KEYCODE_BUTTON_SELECT, event)
                else if (action == KeyEvent.ACTION_UP) onKeyUp(KeyEvent.KEYCODE_BUTTON_SELECT, event)
            }
        }
    }
    override fun onDestroy() {
        if (::controllerManager.isInitialized) {
            val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
            inputManager.unregisterInputDeviceListener(controllerManager)
        }
        super.onDestroy()
        cleanup()
    }


    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
