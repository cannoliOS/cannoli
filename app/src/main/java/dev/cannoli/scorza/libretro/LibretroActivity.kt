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
    private var audio: LibretroAudio? = null
    private var glSurfaceView: GLSurfaceView? = null

    private val inputMask = AtomicInteger(0)
    private var menuVisible by mutableStateOf(false)
    private var menuSelectedIndex by mutableIntStateOf(0)
    private var selectedSlotIndex by mutableIntStateOf(0)
    private var slotThumbnail by mutableStateOf<Bitmap?>(null)
    private var slotExists by mutableStateOf(false)
    private var slotOccupied by mutableStateOf(emptyList<Boolean>())
    private var settingsVisible by mutableStateOf(false)
    private var settingsSelectedIndex by mutableIntStateOf(0)
    private var controlsVisible by mutableStateOf(false)
    private var controlsSelectedIndex by mutableIntStateOf(0)
    private var controlsListeningIndex by mutableIntStateOf(-1)
    private var cleaned = false

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
    private var showWifi = true
    private var showBluetooth = true
    private var showClock = true
    private var showBattery = true
    private var use24h = false

    private val currentSlot get() = slotManager.slots[selectedSlotIndex]

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
        showWifi = intent.getBooleanExtra("show_wifi", true)
        showBluetooth = intent.getBooleanExtra("show_bluetooth", true)
        showClock = intent.getBooleanExtra("show_clock", true)
        showBattery = intent.getBooleanExtra("show_battery", true)
        use24h = intent.getBooleanExtra("use_24h", false)

        slotManager = SaveSlotManager(stateBasePath)

        val inputPrefs = getSharedPreferences("libretro_controls", MODE_PRIVATE)
        input = LibretroInput(inputPrefs)

        runner = LibretroRunner()

        val internalCore = copyCoreToCacheIfNeeded(corePath)
        if (internalCore == null || !runner.loadCore(internalCore)) {
            finish()
            return
        }

        runner.init(systemDir, saveDir)

        val avInfo = runner.loadGame(romPath) ?: run {
            runner.deinit()
            finish()
            return
        }

        if (sramPath.isNotEmpty() && File(sramPath).exists()) {
            runner.loadSRAM(sramPath)
        }

        audio = LibretroAudio(avInfo.sampleRate)
        runner.setAudioCallback(audio!!)
        audio!!.start()

        renderer = LibretroRenderer(runner)

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
                    LibretroScreen(
                        glSurfaceView = glView,
                        gameTitle = gameTitle,
                        menuVisible = menuVisible,
                        menuSelectedIndex = menuSelectedIndex,
                        selectedSlot = currentSlot,
                        slotThumbnail = slotThumbnail,
                        slotExists = slotExists,
                        slotOccupied = slotOccupied,
                        undoLabel = when (undoType) {
                            UndoType.SAVE -> "Undo Save"
                            UndoType.LOAD -> "Undo Load"
                            null -> null
                        },
                        onMenuAction = ::handleMenuAction,
                        settingsVisible = settingsVisible,
                        settingsSelectedIndex = settingsSelectedIndex,
                        controlsVisible = controlsVisible,
                        controlsSelectedIndex = controlsSelectedIndex,
                        controlsListeningIndex = controlsListeningIndex,
                        input = input,
                        showWifi = showWifi,
                        showBluetooth = showBluetooth,
                        showClock = showClock,
                        showBattery = showBattery,
                        use24h = use24h,
                        osdMessage = osdMessage
                    )
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    controlsVisible -> closeControls()
                    settingsVisible -> closeSettings()
                    menuVisible -> closeMenu()
                    else -> openMenu()
                }
            }
        })

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (controlsVisible) return handleControlsInput(keyCode)
        if (settingsVisible) return handleSettingsInput(keyCode)
        if (menuVisible) return handleMenuInput(keyCode)

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            openMenu()
            return true
        }

        val mask = input.keyCodeToRetroMask(keyCode) ?: return super.onKeyDown(keyCode, event)
        inputMask.updateAndGet { it or mask }
        runner.setInput(inputMask.get())
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (controlsVisible || settingsVisible || menuVisible) return true
        val mask = input.keyCodeToRetroMask(keyCode) ?: return super.onKeyUp(keyCode, event)
        inputMask.updateAndGet { it and mask.inv() }
        runner.setInput(inputMask.get())
        return true
    }

    private fun openMenu() {
        menuVisible = true
        menuSelectedIndex = 0
        renderer.paused = true
        refreshSlotInfo()
    }

    private fun closeMenu() {
        menuVisible = false
        renderer.paused = false
    }

    private fun openSettings() {
        settingsVisible = true
        settingsSelectedIndex = 0
    }

    private fun closeSettings() {
        settingsVisible = false
    }

    private fun openControls() {
        controlsVisible = true
        controlsSelectedIndex = 0
        controlsListeningIndex = -1
    }

    private fun closeControls() {
        controlsListeningIndex = -1
        controlsVisible = false
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

    private fun handleMenuInput(keyCode: Int): Boolean {
        val options = InGameMenu.OPTIONS
        val onSlotRow = menuSelectedIndex == InGameMenu.SAVE_STATE || menuSelectedIndex == InGameMenu.LOAD_STATE

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                menuSelectedIndex = ((menuSelectedIndex - 1) + options.size) % options.size
                if (menuSelectedIndex == InGameMenu.SAVE_STATE || menuSelectedIndex == InGameMenu.LOAD_STATE) {
                    refreshSlotInfo()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                menuSelectedIndex = (menuSelectedIndex + 1) % options.size
                if (menuSelectedIndex == InGameMenu.SAVE_STATE || menuSelectedIndex == InGameMenu.LOAD_STATE) {
                    refreshSlotInfo()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (onSlotRow) cycleSlot(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (onSlotRow) cycleSlot(1)
                true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleMenuAction(menuSelectedIndex)
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (undoType != null) performUndo()
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                closeMenu()
                true
            }
            else -> true
        }
    }

    private fun handleSettingsInput(keyCode: Int): Boolean {
        val options = IGMSettings.OPTIONS
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                settingsSelectedIndex = ((settingsSelectedIndex - 1) + options.size) % options.size
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                settingsSelectedIndex = (settingsSelectedIndex + 1) % options.size
                true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                when (settingsSelectedIndex) {
                    IGMSettings.CONTROLS -> openControls()
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                closeSettings()
                true
            }
            else -> true
        }
    }

    private fun handleControlsInput(keyCode: Int): Boolean {
        if (controlsListeningIndex >= 0) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                controlsListeningIndex = -1
                return true
            }
            val button = input.buttons[controlsListeningIndex]
            input.assign(button, keyCode)
            controlsListeningIndex = -1
            return true
        }

        val count = input.buttons.size
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                controlsSelectedIndex = ((controlsSelectedIndex - 1) + count) % count
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                controlsSelectedIndex = (controlsSelectedIndex + 1) % count
                true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                controlsListeningIndex = controlsSelectedIndex
                true
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                input.resetDefaults()
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                closeControls()
                true
            }
            else -> true
        }
    }

    private fun handleMenuAction(index: Int) {
        when (index) {
            InGameMenu.RESUME -> closeMenu()
            InGameMenu.SAVE_STATE -> {
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
                closeMenu()
            }
            InGameMenu.LOAD_STATE -> {
                if (stateBasePath.isNotEmpty() && slotManager.stateExists(currentSlot)) {
                    val slot = currentSlot
                    slotManager.cacheForUndoLoad(runner)
                    undoType = UndoType.LOAD
                    undoSlot = null
                    startUndoTimer()
                    slotManager.loadState(runner, slot)
                    showOsd("Loaded ${slot.label}")
                }
                closeMenu()
            }
            InGameMenu.SETTINGS -> openSettings()
            InGameMenu.RESET -> {
                runner.reset()
                closeMenu()
            }
            InGameMenu.QUIT -> quit()
        }
    }

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
        closeMenu()
    }

    private fun clearUndo() {
        undoType = null
        undoSlot = null
        undoHandler.removeCallbacks(clearUndoRunnable)
        slotManager.clearUndoCache()
    }

    private fun cleanup() {
        if (cleaned) return
        cleaned = true
        if (sramPath.isNotEmpty()) {
            File(sramPath).parentFile?.mkdirs()
            runner.saveSRAM(sramPath)
        }
        audio?.stop()
        runner.unloadGame()
        runner.deinit()
    }

    private fun quit() {
        cleanup()
        finish()
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView?.onPause()
        if (!cleaned && sramPath.isNotEmpty()) {
            File(sramPath).parentFile?.mkdirs()
            runner.saveSRAM(sramPath)
        }
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView?.onResume()
        goFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun copyCoreToCacheIfNeeded(externalPath: String): String? {
        val src = File(externalPath)
        if (!src.exists()) return null
        val dst = File(cacheDir, src.name)
        if (!dst.exists() || dst.length() != src.length()) {
            src.inputStream().use { inp -> dst.outputStream().use { inp.copyTo(it) } }
        }
        return dst.absolutePath
    }

    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
