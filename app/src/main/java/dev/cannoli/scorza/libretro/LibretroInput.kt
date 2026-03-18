package dev.cannoli.scorza.libretro

import android.view.KeyEvent

class LibretroInput {

    data class ButtonDef(val retroMask: Int, val label: String, val prefKey: String, val defaultKeyCode: Int)

    val buttons = listOf(
        ButtonDef(RETRO_A, "A", "btn_a", KeyEvent.KEYCODE_BUTTON_A),
        ButtonDef(RETRO_B, "B", "btn_b", KeyEvent.KEYCODE_BUTTON_B),
        ButtonDef(RETRO_X, "X", "btn_x", KeyEvent.KEYCODE_BUTTON_X),
        ButtonDef(RETRO_Y, "Y", "btn_y", KeyEvent.KEYCODE_BUTTON_Y),
        ButtonDef(RETRO_L, "L", "btn_l", KeyEvent.KEYCODE_BUTTON_L1),
        ButtonDef(RETRO_R, "R", "btn_r", KeyEvent.KEYCODE_BUTTON_R1),
        ButtonDef(RETRO_L2, "L2", "btn_l2", KeyEvent.KEYCODE_BUTTON_L2),
        ButtonDef(RETRO_R2, "R2", "btn_r2", KeyEvent.KEYCODE_BUTTON_R2),
        ButtonDef(RETRO_L3, "L3", "btn_l3", KeyEvent.KEYCODE_BUTTON_THUMBL),
        ButtonDef(RETRO_R3, "R3", "btn_r3", KeyEvent.KEYCODE_BUTTON_THUMBR),
        ButtonDef(RETRO_START, "Start", "btn_start", KeyEvent.KEYCODE_BUTTON_START),
        ButtonDef(RETRO_SELECT, "Select", "btn_select", KeyEvent.KEYCODE_BUTTON_SELECT),
        ButtonDef(0, "Menu", "btn_menu", KeyEvent.KEYCODE_BUTTON_MODE),
        ButtonDef(RETRO_UP, "Up", "btn_up", KeyEvent.KEYCODE_DPAD_UP),
        ButtonDef(RETRO_DOWN, "Down", "btn_down", KeyEvent.KEYCODE_DPAD_DOWN),
        ButtonDef(RETRO_LEFT, "Left", "btn_left", KeyEvent.KEYCODE_DPAD_LEFT),
        ButtonDef(RETRO_RIGHT, "Right", "btn_right", KeyEvent.KEYCODE_DPAD_RIGHT),
    )

    private val assignments = mutableMapOf<String, Int>()
    private val keyToRetro = mutableMapOf<Int, Int>()

    init {
        rebuildMap()
    }

    private fun rebuildMap() {
        keyToRetro.clear()
        for (btn in buttons) {
            if (btn.retroMask == 0) continue
            val keyCode = assignments[btn.prefKey] ?: btn.defaultKeyCode
            keyToRetro[keyCode] = btn.retroMask
        }
    }

    fun keyCodeToRetroMask(keyCode: Int): Int? = keyToRetro[keyCode]

    fun getKeyCodeFor(button: ButtonDef): Int {
        return assignments[button.prefKey] ?: button.defaultKeyCode
    }

    fun assign(button: ButtonDef, keyCode: Int) {
        assignments[button.prefKey] = keyCode
        rebuildMap()
    }

    fun swapStartSelect() {
        val startCode = assignments["btn_start"] ?: KeyEvent.KEYCODE_BUTTON_START
        val selectCode = assignments["btn_select"] ?: KeyEvent.KEYCODE_BUTTON_SELECT
        assignments["btn_start"] = selectCode
        assignments["btn_select"] = startCode
        rebuildMap()
    }

    fun resetDefaults() {
        assignments.clear()
        rebuildMap()
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
        const val RETRO_L2     = 1 shl 12
        const val RETRO_R2     = 1 shl 13
        const val RETRO_L3     = 1 shl 14
        const val RETRO_R3     = 1 shl 15

        fun keyCodeName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode)
            .removePrefix("KEYCODE_")
            .replace("BUTTON_", "")
            .split("_")
            .joinToString(" ") { word -> word.lowercase(java.util.Locale.ROOT).replaceFirstChar { it.uppercase() } }
            .replace("Dpad ", "D-Pad ")
    }
}
