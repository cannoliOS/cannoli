package dev.cannoli.scorza.libretro

import android.content.SharedPreferences
import android.view.KeyEvent

class LibretroInput(private val prefs: SharedPreferences) {

    data class ButtonDef(val retroMask: Int, val label: String, val prefKey: String, val defaultKeyCode: Int)

    val buttons = listOf(
        ButtonDef(RETRO_A, "A", "btn_a", KeyEvent.KEYCODE_BUTTON_A),
        ButtonDef(RETRO_B, "B", "btn_b", KeyEvent.KEYCODE_BUTTON_B),
        ButtonDef(RETRO_X, "X", "btn_x", KeyEvent.KEYCODE_BUTTON_X),
        ButtonDef(RETRO_Y, "Y", "btn_y", KeyEvent.KEYCODE_BUTTON_Y),
        ButtonDef(RETRO_L, "L", "btn_l", KeyEvent.KEYCODE_BUTTON_L1),
        ButtonDef(RETRO_R, "R", "btn_r", KeyEvent.KEYCODE_BUTTON_R1),
        ButtonDef(RETRO_START, "Start", "btn_start", KeyEvent.KEYCODE_BUTTON_START),
        ButtonDef(RETRO_SELECT, "Select", "btn_select", KeyEvent.KEYCODE_BUTTON_SELECT),
        ButtonDef(RETRO_UP, "Up", "btn_up", KeyEvent.KEYCODE_DPAD_UP),
        ButtonDef(RETRO_DOWN, "Down", "btn_down", KeyEvent.KEYCODE_DPAD_DOWN),
        ButtonDef(RETRO_LEFT, "Left", "btn_left", KeyEvent.KEYCODE_DPAD_LEFT),
        ButtonDef(RETRO_RIGHT, "Right", "btn_right", KeyEvent.KEYCODE_DPAD_RIGHT),
    )

    private val keyToRetro = mutableMapOf<Int, Int>()

    init {
        reload()
    }

    fun reload() {
        keyToRetro.clear()
        for (btn in buttons) {
            val keyCode = prefs.getInt(btn.prefKey, btn.defaultKeyCode)
            keyToRetro[keyCode] = btn.retroMask
        }
    }

    fun keyCodeToRetroMask(keyCode: Int): Int? = keyToRetro[keyCode]

    fun getKeyCodeFor(button: ButtonDef): Int {
        return prefs.getInt(button.prefKey, button.defaultKeyCode)
    }

    fun assign(button: ButtonDef, keyCode: Int) {
        prefs.edit().putInt(button.prefKey, keyCode).apply()
        reload()
    }

    fun resetDefaults() {
        val editor = prefs.edit()
        for (btn in buttons) {
            editor.remove(btn.prefKey)
        }
        editor.apply()
        reload()
    }

    companion object {
        const val RETRO_B      = 1 shl 0
        const val RETRO_Y      = 1 shl 1
        const val RETRO_SELECT = 1 shl 2
        const val RETRO_START  = 1 shl 3
        const val RETRO_UP     = 1 shl 4
        const val RETRO_DOWN   = 1 shl 5
        const val RETRO_LEFT   = 1 shl 6
        const val RETRO_RIGHT  = 1 shl 7
        const val RETRO_A      = 1 shl 8
        const val RETRO_X      = 1 shl 9
        const val RETRO_L      = 1 shl 10
        const val RETRO_R      = 1 shl 11

        fun keyCodeName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace("BUTTON_", "")
            .replace("DPAD_", "D-Pad ")
    }
}
