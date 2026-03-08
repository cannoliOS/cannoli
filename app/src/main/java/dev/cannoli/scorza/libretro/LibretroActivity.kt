package dev.cannoli.scorza.libretro

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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.graphics.Color
import dev.cannoli.scorza.ui.theme.CannoliColors
import dev.cannoli.scorza.ui.theme.CannoliTheme
import dev.cannoli.scorza.ui.theme.LocalCannoliColors
import dev.cannoli.scorza.ui.theme.hexToColor
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class LibretroActivity : ComponentActivity() {

    private lateinit var runner: LibretroRunner
    private lateinit var renderer: LibretroRenderer
    private var audio: LibretroAudio? = null
    private var glSurfaceView: GLSurfaceView? = null

    private val inputMask = AtomicInteger(0)
    private var menuVisible by mutableStateOf(false)
    private var menuSelectedIndex by mutableIntStateOf(0)
    private var cleaned = false

    private var gameTitle: String = ""
    private var corePath: String = ""
    private var romPath: String = ""
    private var sramPath: String = ""
    private var statePath: String = ""
    private var systemDir: String = ""
    private var saveDir: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        goFullscreen()

        gameTitle = intent.getStringExtra("game_title") ?: ""
        corePath = intent.getStringExtra("core_path") ?: run { finish(); return }
        romPath = intent.getStringExtra("rom_path") ?: run { finish(); return }
        sramPath = intent.getStringExtra("sram_path") ?: ""
        statePath = intent.getStringExtra("state_path") ?: ""
        systemDir = intent.getStringExtra("system_dir") ?: ""
        saveDir = intent.getStringExtra("save_dir") ?: ""

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
                        onMenuAction = ::handleMenuAction
                    )
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (menuVisible) closeMenu() else openMenu()
            }
        })

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuVisible) return handleMenuInput(keyCode)

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            openMenu()
            return true
        }

        val mask = LibretroInput.keyCodeToRetroMask(keyCode) ?: return super.onKeyDown(keyCode, event)
        inputMask.updateAndGet { it or mask }
        runner.setInput(inputMask.get())
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val mask = LibretroInput.keyCodeToRetroMask(keyCode) ?: return super.onKeyUp(keyCode, event)
        inputMask.updateAndGet { it and mask.inv() }
        runner.setInput(inputMask.get())
        return true
    }

    private fun openMenu() {
        menuVisible = true
        menuSelectedIndex = 0
        renderer.paused = true
    }

    private fun closeMenu() {
        menuVisible = false
        renderer.paused = false
    }

    private fun handleMenuInput(keyCode: Int): Boolean {
        val options = InGameMenu.OPTIONS
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                menuSelectedIndex = ((menuSelectedIndex - 1) + options.size) % options.size
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                menuSelectedIndex = (menuSelectedIndex + 1) % options.size
                true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleMenuAction(menuSelectedIndex)
                true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                closeMenu()
                true
            }
            else -> true
        }
    }

    private fun handleMenuAction(index: Int) {
        when (index) {
            InGameMenu.RESUME -> closeMenu()
            InGameMenu.SAVE_STATE -> {
                if (statePath.isNotEmpty()) {
                    runner.saveState(statePath)
                }
                closeMenu()
            }
            InGameMenu.LOAD_STATE -> {
                if (statePath.isNotEmpty() && File(statePath).exists()) {
                    runner.loadState(statePath)
                }
                closeMenu()
            }
            InGameMenu.RESET -> {
                runner.reset()
                closeMenu()
            }
            InGameMenu.QUIT -> quit()
        }
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
            src.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
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
