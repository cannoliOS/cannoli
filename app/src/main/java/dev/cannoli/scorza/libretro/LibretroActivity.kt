package dev.cannoli.scorza.libretro

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.cannoli.scorza.ui.theme.CannoliColors
import dev.cannoli.scorza.ui.theme.CannoliTheme
import dev.cannoli.scorza.ui.theme.LocalCannoliColors
import dev.cannoli.scorza.ui.theme.hexToColor
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LibretroActivity : ComponentActivity() {

    private lateinit var runner: LibretroRunner
    private lateinit var renderer: LibretroRenderer
    private lateinit var input: LibretroInput
    private lateinit var slotManager: SaveSlotManager
    private lateinit var overrideManager: OverrideManager
    private var audio: LibretroAudio? = null
    private var glSurfaceView: GLSurfaceView? = null

    private val inputMask = AtomicInteger(0)
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

    private var coreOptions by mutableStateOf(emptyList<LibretroRunner.CoreOption>())
    private var coreCategories by mutableStateOf(emptyList<LibretroRunner.CoreOptionCategory>())
    private var shortcuts by mutableStateOf(mapOf<ShortcutAction, Set<Int>>())
    private val shortcutChordKeys = mutableSetOf<Int>()
    private var coreInfoText by mutableStateOf("")

    private var settingsSnapshot: OverrideManager.Settings? = null

    private var diskCount by mutableIntStateOf(0)
    private var currentDiskIndex by mutableIntStateOf(0)
    private var diskLabels = emptyList<String>()

    private var audioSampleRate = 0
    private var fastForwarding by mutableStateOf(false)
    private var holdingFf = false

    private fun setFastForward(enabled: Boolean) {
        fastForwarding = enabled
        renderer.fastForwardFrames = if (enabled) maxFfSpeed else 0
        audio?.muted = enabled
    }

    private enum class UndoType { SAVE, LOAD }
    private var undoType by mutableStateOf<UndoType?>(null)
    private var undoSlot: SaveSlotManager.Slot? = null
    private val undoHandler = Handler(Looper.getMainLooper())
    private val clearUndoRunnable = Runnable { clearUndo() }
    private var osdMessage by mutableStateOf<String?>(null)
    private val osdHandler = Handler(Looper.getMainLooper())
    private val clearOsdRunnable = Runnable { osdMessage = null }

    private var gameTitle: String = ""
    private var corePath: String = ""
    private var romPath: String = ""
    private var sramPath: String = ""
    private var stateBasePath: String = ""
    private var systemDir: String = ""
    private var saveDir: String = ""
    private var platformTag: String = ""
    private var platformName: String = ""
    private var cannoliRoot: String = ""
    private var showWifi = true
    private var showBluetooth = true
    private var showClock = true
    private var showBattery = true
    private var use24h = false

    private val currentSlot get() = slotManager.slots[selectedSlotIndex]
    private val currentScreen get() = screenStack.lastOrNull()
    private val hasDiscs get() = diskCount > 1

    private fun diskLabel(index: Int): String =
        diskLabels.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "Disc ${index + 1}"

    private fun menuOptions() = InGameMenuOptions(hasDiscs, diskLabel(currentDiskIndex))

    private fun refreshDiskInfo() {
        diskCount = runner.getDiskCount()
        currentDiskIndex = runner.getDiskIndex()
        if (diskCount > 1) {
            diskLabels = (0 until diskCount).map { runner.getDiskLabel(it) ?: "" }
        }
    }

    companion object {
        private val FF_SPEEDS = listOf(2, 3, 4, 6, 8)
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
        goFullscreen()

        gameTitle = intent.getStringExtra("game_title") ?: ""
        corePath = intent.getStringExtra("core_path") ?: run { finish(); return }
        romPath = intent.getStringExtra("rom_path") ?: run { finish(); return }
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

        slotManager = SaveSlotManager(stateBasePath)
        input = LibretroInput()
        if (intent.getBooleanExtra("swap_start_select", false)) input.swapStartSelect()

        runner = LibretroRunner()
        if (!runner.loadCore(corePath)) { finish(); return }
        runner.init(systemDir, saveDir)
        val avInfo = runner.loadGame(romPath) ?: run { runner.deinit(); finish(); return }

        val (coreName, coreVersion) = runner.getSystemInfo()
        coreInfoText = if (coreVersion.isNotEmpty()) "$coreName $coreVersion" else coreName
        coreOptions = runner.getCoreOptions()
        coreCategories = runner.getCoreCategories()

        val coreBaseName = File(corePath).nameWithoutExtension
        val gameBaseName = if (romPath.isNotEmpty()) File(romPath).nameWithoutExtension else ""
        overrideManager = OverrideManager(cannoliRoot, coreBaseName, platformTag, gameBaseName)
        loadOverrides()

        if (sramPath.isNotEmpty() && File(sramPath).exists()) runner.loadSRAM(sramPath)

        val resumeSlot = intent.getIntExtra("resume_slot", -1)
        if (resumeSlot >= 0) {
            val slot = slotManager.slots.getOrNull(resumeSlot)
            if (slot != null && slotManager.stateExists(slot)) {
                slotManager.loadState(runner, slot)
                selectedSlotIndex = resumeSlot
            }
        }

        audioSampleRate = avInfo.sampleRate
        audio = LibretroAudio(avInfo.sampleRate)
        runner.setAudioCallback(audio!!)
        audio!!.start()

        renderer = LibretroRenderer(runner).also {
            it.coreAspectRatio = runner.getAspectRatio()
            it.scalingMode = scalingMode
            it.sharpness = sharpness
            it.screenEffect = screenEffect
            it.debugHud = debugHud
        }

        val glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glSurfaceView = glView

        val colors = CannoliColors(
            highlight = hexToColor(intent.getStringExtra("color_highlight") ?: "#FFFFFF") ?: Color.White,
            text = hexToColor(intent.getStringExtra("color_text") ?: "#FFFFFF") ?: Color.White,
            highlightText = hexToColor(intent.getStringExtra("color_highlight_text") ?: "#000000") ?: Color.Black,
            accent = hexToColor(intent.getStringExtra("color_accent") ?: "#FFFFFF") ?: Color.White
        )

        setContent {
            CannoliTheme {
                CompositionLocalProvider(LocalCannoliColors provides colors) {
                    val screen = currentScreen
                    LibretroScreen(
                        glSurfaceView = glView,
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
                            null -> null
                        },
                        settingsItems = if (screen is IGMScreen.Menu) emptyList() else buildSettingsItems(),
                        coreInfo = coreInfoText,
                        input = input,
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
                        fastForwarding = fastForwarding
                    )
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screenStack.isEmpty()) openMenu() else pop()
                if (screenStack.isEmpty()) renderer.paused = false
            }
        })

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // --- Input ---

    private val pressedKeys = mutableSetOf<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val screen = currentScreen ?: return handleGameplayInput(keyCode, event)
        return when (screen) {
            is IGMScreen.Menu -> handleMenuInput(screen, keyCode)
            is IGMScreen.Settings -> handleCategoryInput(screen, keyCode)
            is IGMScreen.Frontend -> handleFrontendInput(screen, keyCode)
            is IGMScreen.Emulator -> handleEmulatorInput(screen, keyCode)
            is IGMScreen.EmulatorCategory -> handleEmulatorCategoryInput(screen, keyCode)
            is IGMScreen.Controls -> handleControlsInput(screen, keyCode)
            is IGMScreen.Shortcuts -> handleShortcutsInput(screen, keyCode)
            is IGMScreen.SavePrompt -> handleSavePromptInput(screen, keyCode)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (screenStack.isNotEmpty()) {
            handleShortcutKeyUp(keyCode)
            return true
        }
        pressedKeys.remove(keyCode)

        if (holdingFf) {
            val holdChord = shortcuts[ShortcutAction.HOLD_FF]
            if (holdChord != null && !pressedKeys.containsAll(holdChord)) {
                holdingFf = false
                setFastForward(false)
            }
        }

        val mask = input.keyCodeToRetroMask(keyCode) ?: return super.onKeyUp(keyCode, event)
        inputMask.updateAndGet { it and mask.inv() }
        runner.setInput(inputMask.get())
        return true
    }

    private fun handleGameplayInput(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { openMenu(); return true }
        val isNewPress = pressedKeys.add(keyCode)
        if (isNewPress) checkShortcuts()
        val mask = input.keyCodeToRetroMask(keyCode) ?: return super.onKeyDown(keyCode, event)
        inputMask.updateAndGet { it or mask }
        runner.setInput(inputMask.get())
        return true
    }

    private fun checkShortcuts() {
        for ((action, chord) in shortcuts) {
            if (chord.isEmpty() || !pressedKeys.containsAll(chord)) continue
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
                        showOsd("Loaded ${currentSlot.label}")
                    }
                }
                ShortcutAction.RESET_GAME -> { runner.reset(); showOsd("Reset") }
                ShortcutAction.SAVE_AND_QUIT -> {
                    if (stateBasePath.isNotEmpty()) slotManager.saveState(runner, currentSlot)
                    quit()
                }
                ShortcutAction.CYCLE_SCALING -> {
                    val modes = ScalingMode.entries
                    scalingMode = modes[(scalingMode.ordinal + 1) % modes.size]
                    renderer.scalingMode = scalingMode
                    showOsd("Scaling: ${scalingLabel()}")
                }
                ShortcutAction.CYCLE_EFFECT -> {
                    val effects = ScreenEffect.entries
                    screenEffect = effects[(screenEffect.ordinal + 1) % effects.size]
                    renderer.screenEffect = screenEffect
                    showOsd("Effect: ${effectLabel()}")
                }
                ShortcutAction.TOGGLE_FF -> {
                    setFastForward(!fastForwarding)
                }
                ShortcutAction.HOLD_FF -> {
                    if (holdingFf) continue
                    holdingFf = true; setFastForward(true)
                }
            }
            pressedKeys.clear()
            inputMask.set(0)
            runner.setInput(0)
            break
        }
    }

    // --- Menu screen ---

    private fun openMenu() {
        screenStack.clear()
        push(IGMScreen.Menu())
        renderer.paused = true
        refreshSlotInfo()
        refreshDiskInfo()
    }

    private fun closeAll() {
        screenStack.clear()
        renderer.paused = false
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
                if (onSlotRow && currentSlot.index != 0 && slotExists) {
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
                    showOsd("Loaded ${slot.label}")
                }
                closeAll()
            }
            menu.settingsIndex -> {
                coreOptions = runner.getCoreOptions()
                settingsSnapshot = buildCurrentSettings()
                push(IGMScreen.Settings())
            }
            menu.resetIndex -> { runner.reset(); closeAll() }
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
                    IGMSettings.FRONTEND -> push(IGMScreen.Frontend())
                    IGMSettings.EMULATOR -> {
                        coreOptions = runner.getCoreOptions()
                        coreCategories = runner.getCoreCategories()
                        push(IGMScreen.Emulator())
                    }
                    IGMSettings.CONTROLS -> push(IGMScreen.Controls())
                    IGMSettings.SHORTCUTS -> push(IGMScreen.Shortcuts())
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                if (settingsSnapshot != null && buildCurrentSettings() != settingsSnapshot) {
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

    private fun effectLabel() = when (screenEffect) {
        ScreenEffect.NONE -> "None"
        ScreenEffect.SCANLINE -> "Scanline"
        ScreenEffect.GRID -> "Grid"
        ScreenEffect.CRT -> "CRT"
    }

    private fun sharpnessLabel() = when (sharpness) {
        Sharpness.SHARP -> "Sharp"
        Sharpness.CRISP -> "Crisp"
        Sharpness.SOFT -> "Soft"
    }

    private fun handleFrontendInput(screen: IGMScreen.Frontend, keyCode: Int): Boolean {
        val count = 5
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { cycleFrontendValue(screen.selectedIndex, -1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleFrontendValue(screen.selectedIndex, 1); true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun cycleFrontendValue(index: Int, direction: Int) {
        when (index) {
            0 -> {
                val modes = ScalingMode.entries
                scalingMode = modes[(scalingMode.ordinal + direction + modes.size) % modes.size]
                renderer.scalingMode = scalingMode
            }
            1 -> {
                val effects = ScreenEffect.entries
                screenEffect = effects[(screenEffect.ordinal + direction + effects.size) % effects.size]
                renderer.screenEffect = screenEffect
            }
            2 -> {
                val vals = Sharpness.entries
                sharpness = vals[(sharpness.ordinal + direction + vals.size) % vals.size]
                renderer.sharpness = sharpness
            }
            3 -> {
                debugHud = !debugHud
                renderer.debugHud = debugHud
            }
            4 -> {
                val idx = FF_SPEEDS.indexOf(maxFfSpeed).coerceAtLeast(0)
                maxFfSpeed = FF_SPEEDS[(idx + direction + FF_SPEEDS.size) % FF_SPEEDS.size]
                if (fastForwarding) renderer.fastForwardFrames = maxFfSpeed
            }
        }
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

    private fun handleControlsInput(screen: IGMScreen.Controls, keyCode: Int): Boolean {
        if (screen.listeningIndex >= 0) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                replaceTop(screen.copy(listeningIndex = -1))
                return true
            }
            input.assign(input.buttons[screen.listeningIndex], keyCode)
            replaceTop(screen.copy(listeningIndex = -1))
            return true
        }
        val count = input.buttons.size
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                replaceTop(screen.copy(listeningIndex = screen.selectedIndex)); true
            }
            KeyEvent.KEYCODE_BUTTON_X -> { input.resetDefaults(); true }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    // --- Shortcuts ---

    private val shortcutCountdownHandler = Handler(Looper.getMainLooper())
    private val SHORTCUT_HOLD_MS = 1500
    private val SHORTCUT_TICK_MS = 100L

    private val shortcutCountdownRunnable = object : Runnable {
        override fun run() {
            val screen = currentScreen as? IGMScreen.Shortcuts ?: return
            if (!screen.listening) return
            val newMs = screen.countdownMs + SHORTCUT_TICK_MS.toInt()
            if (newMs >= SHORTCUT_HOLD_MS) {
                val action = ShortcutAction.entries[screen.selectedIndex]
                shortcuts = shortcuts + (action to screen.heldKeys)
                replaceTop(screen.copy(listening = false, heldKeys = emptySet(), countdownMs = 0))
            } else {
                replaceTop(screen.copy(countdownMs = newMs))
                shortcutCountdownHandler.postDelayed(this, SHORTCUT_TICK_MS)
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

    private fun handleShortcutsInput(screen: IGMScreen.Shortcuts, keyCode: Int): Boolean {
        if (screen.listening) {
            if (screen.heldKeys.contains(keyCode)) return true
            val newKeys = screen.heldKeys + keyCode
            replaceTop(screen.copy(heldKeys = newKeys, countdownMs = 0))
            shortcutCountdownHandler.removeCallbacks(shortcutCountdownRunnable)
            shortcutCountdownHandler.postDelayed(shortcutCountdownRunnable, SHORTCUT_TICK_MS)
            return true
        }
        val count = ShortcutAction.entries.size
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                replaceTop(screen.copy(listening = true, heldKeys = emptySet(), countdownMs = 0))
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                val action = ShortcutAction.entries[screen.selectedIndex]
                shortcuts = shortcuts + (action to emptySet())
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    // --- Save Prompt ---

    private fun handleSavePromptInput(screen: IGMScreen.SavePrompt, keyCode: Int): Boolean {
        val count = 4
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (screen.selectedIndex) {
                    0 -> { saveToScope(0); showOsd("Saved globally") }
                    1 -> { saveToScope(1); showOsd("Saved for $platformName ($platformTag)") }
                    2 -> { saveToScope(2); showOsd("Saved for this game") }
                    3 -> showOsd("Changes discarded")
                }
                settingsSnapshot = null
                pop(); pop()
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                settingsSnapshot = null
                pop(); pop()
                true
            }
            else -> true
        }
    }

    // --- Settings item builders ---

    private fun buildSettingsItems(): List<IGMSettingsItem> = when (val screen = currentScreen) {
        is IGMScreen.Settings -> IGMSettings.CATEGORIES.map { IGMSettingsItem(it) }
        is IGMScreen.Frontend -> listOf(
            IGMSettingsItem("Screen Scaling", scalingLabel()),
            IGMSettingsItem("Screen Effect", effectLabel()),
            IGMSettingsItem("Screen Sharpness", sharpnessLabel()),
            IGMSettingsItem("Debug HUD", if (debugHud) "On" else "Off"),
            IGMSettingsItem("Max FF Speed", "${maxFfSpeed}x")
        )
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
        is IGMScreen.Shortcuts -> ShortcutAction.entries.map { action ->
            val chord = shortcuts[action]
            val label = if (chord.isNullOrEmpty()) "None"
            else chord.joinToString(" + ") { LibretroInput.keyCodeName(it) }
            IGMSettingsItem(action.label, label)
        }
        is IGMScreen.SavePrompt -> listOf(
            IGMSettingsItem("Save for all games"),
            IGMSettingsItem("Save for $platformName ($platformTag)"),
            IGMSettingsItem("Save for this game"),
            IGMSettingsItem("Discard changes")
        )
        else -> emptyList()
    }

    // --- Settings persistence ---

    private fun buildCurrentSettings(): OverrideManager.Settings {
        val controlMap = mutableMapOf<String, Int>()
        for (btn in input.buttons) {
            val keyCode = input.getKeyCodeFor(btn)
            if (keyCode != btn.defaultKeyCode) controlMap[btn.prefKey] = keyCode
        }
        val optionMap = mutableMapOf<String, String>()
        for (opt in coreOptions) optionMap[opt.key] = opt.selected

        return OverrideManager.Settings(
            scalingMode = scalingMode,
            screenEffect = screenEffect,
            sharpness = sharpness,
            debugHud = debugHud,
            maxFfSpeed = maxFfSpeed,
            controls = controlMap,
            shortcuts = shortcuts,
            coreOptions = optionMap
        )
    }

    private fun saveToScope(scopeIndex: Int) {
        val settings = buildCurrentSettings()
        when (scopeIndex) {
            0 -> overrideManager.saveGlobal(settings)
            1 -> overrideManager.saveCore(settings)
            2 -> overrideManager.saveGame(settings)
        }
    }

    private fun loadOverrides() {
        val settings = overrideManager.load()
        scalingMode = settings.scalingMode
        screenEffect = settings.screenEffect
        sharpness = settings.sharpness
        debugHud = settings.debugHud
        maxFfSpeed = settings.maxFfSpeed
        shortcuts = settings.shortcuts

        for ((key, keyCode) in settings.controls) {
            val btn = input.buttons.find { it.prefKey == key } ?: continue
            input.assign(btn, keyCode)
        }

        for ((key, value) in settings.coreOptions) {
            runner.setCoreOption(key, value)
        }
        coreOptions = runner.getCoreOptions()
    }

    // --- OSD / Undo ---

    private fun showOsd(message: String) {
        osdHandler.removeCallbacks(clearOsdRunnable)
        osdMessage = message
        osdHandler.postDelayed(clearOsdRunnable, 3000)
    }

    private fun startUndoTimer() {
        undoHandler.removeCallbacks(clearUndoRunnable)
        undoHandler.postDelayed(clearUndoRunnable, 60_000)
    }

    private fun performUndo() {
        val label = when (undoType) {
            UndoType.SAVE -> "Undo Save"
            UndoType.LOAD -> "Undo Load"
            null -> return
        }
        when (undoType) {
            UndoType.SAVE -> undoSlot?.let { slotManager.performUndoSave(it) }
            UndoType.LOAD -> slotManager.performUndoLoad(runner)
            null -> return
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
        if (cleaned) return
        cleaned = true
        if (sramPath.isNotEmpty()) { File(sramPath).parentFile?.mkdirs(); runner.saveSRAM(sramPath) }
        audio?.stop()
        runner.unloadGame()
        runner.deinit()
    }

    private fun quit() { cleanup(); finish() }

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
        if (!cleaned && sramPath.isNotEmpty()) { File(sramPath).parentFile?.mkdirs(); runner.saveSRAM(sramPath) }
    }

    override fun onResume() { super.onResume(); glSurfaceView?.onResume(); goFullscreen() }
    override fun onDestroy() { super.onDestroy(); cleanup() }


    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
