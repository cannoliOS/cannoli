package dev.cannoli.scorza.libretro

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
    private var loading by mutableStateOf(true)

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

    private var overlay by mutableStateOf("")
    private var overlayImages = emptyList<String>()

    private var shaderPreset by mutableStateOf("")
    private var shaderPresets = emptyList<String>()
    private var shaderParams by mutableStateOf(emptyList<ShaderParamItem>())

    private var coreOptions by mutableStateOf(emptyList<LibretroRunner.CoreOption>())
    private var coreCategories by mutableStateOf(emptyList<LibretroRunner.CoreOptionCategory>())
    private var controlSource by mutableStateOf(OverrideSource.GLOBAL)
    private var shortcutSource by mutableStateOf(OverrideSource.GLOBAL)
    private var shortcuts by mutableStateOf(mapOf<ShortcutAction, Set<Int>>())
    private val shortcutChordKeys = mutableSetOf<Int>()
    private var coreInfoText by mutableStateOf("")

    private var frontendSnapshot: OverrideManager.Settings? = null
    private var shaderParamsDirty = false
    private var platformBaseline: OverrideManager.Settings? = null
    private var globalControls = emptyMap<String, Int>()

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

    private enum class UndoType { SAVE, LOAD, RESET }
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

    data class ShaderParamItem(
        val id: String, val description: String,
        val value: Float, val min: Float, val max: Float, val step: Float
    )

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

        gameTitle = (intent.getStringExtra("game_title") ?: "").removePrefix("★ ")
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
                            glSurfaceView = glSurfaceView!!,
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
                            input = input,
                            controlSource = controlSource,
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
                            gameInfo = GameInfo(
                                coreName = coreInfoText,
                                romPath = romPath,
                                savePath = sramPath.takeIf { java.io.File(it).exists() },
                                rootPrefix = cannoliRoot
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
                val gameBaseName = if (romPath.isNotEmpty()) File(romPath).nameWithoutExtension else ""
                overrideManager = OverrideManager(cannoliRoot, platformTag, gameBaseName, coreBaseName)
                loadOverrides()
                scanOverlayImages()
                copyBundledShaders()
                scanShaderPresets()

                audioSampleRate = avInfo.sampleRate
                audio = LibretroAudio(avInfo.sampleRate)
                runner.setAudioCallback(audio!!)
                audio!!.start()

                ShaderPipeline.cacheDir = File(cacheDir, "shader_cache")
                renderer = LibretroRenderer(runner).also {
                    it.coreAspectRatio = runner.getAspectRatio()
                    it.scalingMode = scalingMode
                    it.sharpness = sharpness
                    it.screenEffect = screenEffect
                    it.debugHud = debugHud
                    it.overlayPath = resolveOverlayPath()
                    it.shaderPresetPath = resolveShaderPresetPath()
                }

                glSurfaceView = GLSurfaceView(this).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }

                loading = false
            }
        }.start()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // --- Input ---

    private val pressedKeys = mutableSetOf<Int>()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (loading) return true
        val screen = currentScreen ?: return handleGameplayInput(keyCode, event)
        val resolved = resolveGlobal(keyCode)
        return when (screen) {
            is IGMScreen.Menu -> handleMenuInput(screen, resolved)
            is IGMScreen.Settings -> handleCategoryInput(screen, resolved)
            is IGMScreen.Frontend -> handleFrontendInput(screen, resolved)
            is IGMScreen.ShaderSettings -> handleShaderSettingsInput(screen, resolved)
            is IGMScreen.Emulator -> handleEmulatorInput(screen, resolved)
            is IGMScreen.EmulatorCategory -> handleEmulatorCategoryInput(screen, resolved)
            is IGMScreen.Controls -> handleControlsInput(screen, keyCode, resolved)
            is IGMScreen.Shortcuts -> handleShortcutsInput(screen, keyCode, resolved)
            is IGMScreen.SavePrompt -> handleSavePromptInput(screen, resolved)
            is IGMScreen.Info -> {
                if (resolved == KeyEvent.KEYCODE_BUTTON_B || resolved == KeyEvent.KEYCODE_BUTTON_A) { pop(); true } else true
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (loading) return true
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

    private fun resolveGlobal(keyCode: Int): Int {
        for (btn in input.buttons) {
            val assigned = globalControls[btn.prefKey] ?: btn.defaultKeyCode
            if (assigned == keyCode) return btn.defaultKeyCode
        }
        return keyCode
    }

    private fun handleGameplayInput(keyCode: Int, event: KeyEvent): Boolean {
        val menuCode = globalControls["btn_menu"] ?: KeyEvent.KEYCODE_BUTTON_MODE
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == menuCode) { openMenu(); return true }
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
                    showOsd("Shader: ${shaderLabel()}")
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
            menu.infoIndex -> push(IGMScreen.Info())
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

    private fun shaderLabel(): String =
        if (screenEffect == ScreenEffect.NONE || shaderPreset.isEmpty()) "Off"
        else File(shaderPreset).nameWithoutExtension

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
            .filter { it.isFile && it.extension.lowercase() in exts }
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

    private fun handleFrontendInput(screen: IGMScreen.Frontend, keyCode: Int): Boolean {
        val count = frontendItemCount()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { cycleFrontendValue(screen.selectedIndex, -1); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleFrontendValue(screen.selectedIndex, 1); true }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (frontendHasShaderSettings() && screen.selectedIndex == 3) {
                    push(IGMScreen.ShaderSettings())
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun frontendHasShaderSettings() =
        screenEffect == ScreenEffect.SHADER && shaderParams.isNotEmpty()
    private fun frontendItemCount() = if (frontendHasShaderSettings()) 7 else 6

    private fun cycleFrontendValue(index: Int, direction: Int) {
        val settingsRow = if (frontendHasShaderSettings()) 3 else -1
        val base = if (frontendHasShaderSettings()) 4 else 3
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
            settingsRow -> {}
            base -> cycleOverlay(direction)
            base + 1 -> { debugHud = !debugHud; renderer.debugHud = debugHud }
            base + 2 -> { cycleFfSpeed(direction) }
        }
    }

    private fun cycleShader(direction: Int) {
        if (shaderPresets.isEmpty()) {
            screenEffect = ScreenEffect.NONE
            shaderPreset = ""
        } else {
            val currentIndex = if (screenEffect == ScreenEffect.NONE) -1
                else shaderPresets.indexOf(shaderPreset)
            val count = shaderPresets.size
            val newIndex = currentIndex + direction
            if (newIndex in 0 until count) {
                screenEffect = ScreenEffect.SHADER
                shaderPreset = shaderPresets[newIndex]
            } else if (newIndex >= count) {
                screenEffect = ScreenEffect.NONE
                shaderPreset = ""
            } else {
                screenEffect = ScreenEffect.SHADER
                shaderPreset = shaderPresets[count - 1]
            }
        }
        shaderParamsDirty = true
        renderer.clearShaderParamOverrides()
        renderer.screenEffect = screenEffect
        renderer.shaderPresetPath = resolveShaderPresetPath()
        refreshShaderParams()
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
            val screen = currentScreen as? IGMScreen.Controls ?: return
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

    private fun handleControlsInput(screen: IGMScreen.Controls, rawKeyCode: Int, keyCode: Int): Boolean {
        if (screen.listeningIndex >= 0) {
            shortcutCountdownHandler.removeCallbacks(controlListenRunnable)
            val buttonIndex = screen.listeningIndex - 1
            input.assign(input.buttons[buttonIndex], rawKeyCode)
            replaceTop(screen.copy(listeningIndex = -1, listenCountdownMs = 0))
            saveCurrentControls()
            return true
        }
        val count = input.buttons.size + 1
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                replaceTop(screen.copy(selectedIndex = ((screen.selectedIndex - 1) + count) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                replaceTop(screen.copy(selectedIndex = (screen.selectedIndex + 1) % count)); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (screen.selectedIndex == 0) cycleControlSource(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (screen.selectedIndex == 0) cycleControlSource(1)
                true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (screen.selectedIndex == 0) {
                    cycleControlSource(1)
                } else {
                    replaceTop(screen.copy(listeningIndex = screen.selectedIndex, listenCountdownMs = 0))
                    shortcutCountdownHandler.postDelayed(controlListenRunnable, controlListenTickMs)
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                input.resetDefaults()
                saveCurrentControls()
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { pop(); true }
            else -> true
        }
    }

    private fun cycleControlSource(direction: Int) {
        val sources = OverrideSource.entries
        controlSource = sources[(controlSource.ordinal + direction + sources.size) % sources.size]
        overrideManager.saveControlSource(controlSource)
        input.resetDefaults()
        for ((key, kc) in overrideManager.loadControlsForSource(controlSource)) {
            val btn = input.buttons.find { it.prefKey == key } ?: continue
            input.assign(btn, kc)
        }
    }

    private fun saveCurrentControls() {
        val controlMap = mutableMapOf<String, Int>()
        for (btn in input.buttons) controlMap[btn.prefKey] = input.getKeyCodeFor(btn)
        overrideManager.saveControls(controlSource, controlMap)
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
        is IGMScreen.Frontend -> buildList {
            add(IGMSettingsItem("Screen Scaling", scalingLabel()))
            add(IGMSettingsItem("Screen Sharpness", sharpnessLabel()))
            add(IGMSettingsItem("Shader", shaderLabel()))
            if (screenEffect == ScreenEffect.SHADER && shaderParams.isNotEmpty()) {
                add(IGMSettingsItem("Shader Settings"))
            }
            add(IGMSettingsItem("Overlay", overlayLabel()))
            add(IGMSettingsItem("Debug HUD", if (debugHud) "On" else "Off"))
            add(IGMSettingsItem("Max FF Speed", "${maxFfSpeed}x"))
        }
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
        val settings = overrideManager.load()
        scalingMode = settings.scalingMode
        screenEffect = settings.screenEffect
        sharpness = settings.sharpness
        debugHud = settings.debugHud
        maxFfSpeed = settings.maxFfSpeed
        shaderPreset = settings.shaderPreset
        overlay = settings.overlay
        controlSource = settings.controlSource
        shortcutSource = settings.shortcutSource
        shortcuts = settings.shortcuts

        for ((key, keyCode) in settings.controls) {
            val btn = input.buttons.find { it.prefKey == key } ?: continue
            input.assign(btn, keyCode)
        }

        for ((key, value) in settings.coreOptions) {
            runner.setCoreOption(key, value)
        }
        coreOptions = runner.getCoreOptions()
        platformBaseline = overrideManager.loadPlatformBaseline()
        globalControls = overrideManager.loadControlsForSource(OverrideSource.GLOBAL)
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
        if (sramPath.isNotEmpty()) { File(sramPath).parentFile?.mkdirs(); runner.saveSRAM(sramPath) }
        audio?.stop()
        runner.unloadGame()
        runner.deinit()
    }

    private fun quit() { cleanup(); finish() }

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
        if (!loading && !cleaned && sramPath.isNotEmpty()) { File(sramPath).parentFile?.mkdirs(); runner.saveSRAM(sramPath) }
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
